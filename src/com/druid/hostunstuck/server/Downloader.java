package com.druid.hostunstuck.server;

import java.util.List;

/**
 * Workshop installer for the server process. Same job as the game's GameServerWorkshopItems.Install (42.21): one item
 * at a time with high priority, because the game's Steam wrapper (ZNetJNI SteamWorkshop.n_DownloadItem) refuses any
 * DownloadItem while a download is in progress ("download in progress already", seen in the first real run on
 * 2026-10-29) - so neither parallel downloads nor re-requesting a stuck item are possible. What this loop adds: it
 * never crashes on that refusal (it adopts a download left over in the process), it reports byte progress and a
 * heartbeat line ("hostunstuck@...") every second, so the host's watchdog sees a slow download as alive and a frozen
 * server (no line) at once, and restarts the process when a download is stuck. Item states, failed items, reinstall
 * tries and status lines follow the game's loop. No game classes here: the loop runs in an offline simulation (test/SimTest.java).
 */
public final class Downloader {
    public static final String VERSION = "2.0.2";
    /** Reinstall attempts per item before the whole step fails, as in the game. */
    public static final int MAX_TRIES = 3;
    /** Pushes (see pushOldest) after which, if they are needed for most downloads, the loop goes one at a time. */
    public static final int PUSHES_TO_SEQUENTIAL = 3;
    public static final int DL_FAIL = 0, DL_OK = 1, DL_BUSY = 2;

    /** ISteamUGC EItemState flags (SteamWorkshopItem.ItemState in the game). */
    public static final long ST_NONE = 0, ST_INSTALLED = 4, ST_NEEDS_UPDATE = 8, ST_DOWNLOADING = 16, ST_DOWNLOAD_PENDING = 32;

    /** What the loop needs from Steam and the system. */
    public interface Backend {
        long itemState(long id);
        long installTimeStamp(long id);
        /** May be null when Steam does not know the item. */
        String installFolder(long id);
        boolean folderExists(String folder);
        void deleteFolder(String folder);
        /** {@link #DL_OK}, {@link #DL_FAIL} (Steam said no) or {@link #DL_BUSY} (another download is in progress). */
        int download(long id, boolean highPriority);
        /** out[0] = bytes downloaded, out[1] = bytes total; false when Steam has no download info for the item. */
        boolean downloadInfo(long id, long[] out);
        /** Pumps Steam callbacks: onDownloaded / onNotDownloaded are called from inside. */
        void runLoop();
        long now();
        void sleep(long ms);
        void log(String s);
    }

    /** Lines for the host's screen (the game's own status texts plus one structured line of ours). */
    public interface Reporter {
        void progress(int done, int total);
        void reinstall(int done, int total);
        /** The game reports a failed item as its install folder; the Lua side takes the id from it. */
        void failed(long id, String folder);
        void info(String payload);
    }

    public enum State { CHECK, PENDING, READY, FAIL }

    public static final class Item {
        public final long id;
        public long timeCreated, timeUpdated;
        public int size;
        public String title;
        State state = State.CHECK;
        String error;
        int tries, kicks;
        /** Requested with high priority since its last request (directly, pushed or kicked). */
        boolean promoted;
        long startedAt, lastPollAt, lastState = -1, bytes = -1, total;

        public Item(long id) {
            this.id = id;
        }

        public State state() {
            return state;
        }

        public int kicks() {
            return kicks;
        }
    }

    private final Settings cfg;
    private final Backend be;
    private final Reporter rep;
    private List<Item> items;
    private long lastGlobalProgress;
    /** Last sign that Steam works on anything we asked for: a request, the Downloading flag, new bytes, a finished item. */
    private long lastActivity;
    private int kicks, pushes, downloaded;
    private int parallel = 1;

    public Downloader(Settings cfg, Backend be, Reporter rep) {
        this.cfg = cfg;
        this.be = be;
        this.rep = rep;
    }

    public int kicks() {
        return kicks;
    }

    public int pushes() {
        return pushes;
    }

    /** Downloads in flight allowed right now: the setting, or 1 after the loop switched to one at a time. */
    public int parallel() {
        return parallel;
    }

    /** Steam callback: the item finished downloading. The state is checked again before it counts as ready. */
    public void onDownloaded(long id) {
        Item it = find(id);
        if (it == null || it.state != State.PENDING) {
            return;
        }
        be.log("downloaded " + id + " in " + (be.now() - it.startedAt) + " ms");
        lastGlobalProgress = be.now();
        lastActivity = lastGlobalProgress;
        downloaded++;
        it.state = State.CHECK;
    }

    /** Steam callback: the download failed. */
    public void onNotDownloaded(long id, int result) {
        Item it = find(id);
        if (it == null || it.state != State.PENDING) {
            return;
        }
        it.error = "ItemNotDownloaded result=" + result;
        it.state = State.FAIL;
    }

    private Item find(long id) {
        if (items != null) {
            for (Item it : items) {
                if (it.id == id) {
                    return it;
                }
            }
        }
        return null;
    }

    /** Returns false when an item failed MAX_TRIES times (the game then stops the server, as it does itself). */
    public boolean run(List<Item> list) {
        this.items = list;
        int n = list.size();
        int done = 0;
        boolean[] counted = new boolean[n];
        long t0 = be.now();
        lastGlobalProgress = t0;
        lastActivity = t0;
        long lastInfo = Long.MIN_VALUE / 2;
        parallel = 1; // the game's Steam wrapper allows one download at a time (see the class comment)
        rep.progress(0, n);
        while (true) {
            be.runLoop();
            long t = be.now();
            for (Item it : list) {
                if (it.state == State.PENDING) {
                    poll(it, t);
                }
            }
            for (Item it : list) {
                if (it.state != State.FAIL) {
                    continue;
                }
                it.tries++;
                String folder = be.installFolder(it.id);
                be.log("item " + it.id + " failed (" + it.error + "), try " + it.tries + "/" + MAX_TRIES);
                rep.failed(it.id, folder);
                if (it.tries >= MAX_TRIES) {
                    return false;
                }
                rep.reinstall(done, n);
                if (folder != null) {
                    be.deleteFolder(folder);
                }
                it.state = State.CHECK;
                it.error = null;
                it.kicks = 0;
            }
            int inFlight = 0;
            for (Item it : list) {
                if (it.state == State.PENDING) {
                    inFlight++;
                }
            }
            for (Item it : list) {
                if (it.state != State.CHECK) {
                    continue;
                }
                if (inFlight >= parallel) {
                    break;
                }
                check(it, t, parallel == 1);
                if (it.state == State.PENDING) {
                    inFlight++;
                }
            }
            boolean busy = false;
            for (int i = 0; i < n; i++) {
                Item it = list.get(i);
                if (it.state == State.READY) {
                    if (!counted[i]) {
                        counted[i] = true;
                        done++;
                        lastGlobalProgress = t;
                        rep.progress(done, n);
                    }
                } else {
                    busy = true;
                }
            }
            if (!busy) {
                break;
            }
            if (inFlight > 0 && t - lastActivity > cfg.promoteMs) {
                pushOldest(t);
            }
            if (inFlight > 0 && t - lastGlobalProgress > cfg.stallSeconds * 1000L) {
                kickOldest(t);
            }
            if (t - lastInfo >= cfg.infoMs) {
                rep.info(info(done, n, t - t0));
                lastInfo = t;
            }
            be.sleep(cfg.pollMs);
        }
        rep.info(info(done, n, be.now() - t0));
        be.log("all " + n + " items ready in " + (be.now() - t0) / 1000 + " s, " + kicks + " re-requests, " + pushes
                + " pushes, " + downloaded + " downloads, parallel x" + parallel + " at the end");
        return true;
    }

    private void check(Item it, long t, boolean highPriority) {
        long st = be.itemState(it.id);
        if ((st & ST_INSTALLED) != 0 && it.timeCreated != 0 && it.timeUpdated != be.installTimeStamp(it.id)) {
            be.log("installed but timeUpdated does not match, reinstalling ID=" + it.id);
            String folder = be.installFolder(it.id);
            if (folder != null && be.folderExists(folder)) {
                be.deleteFolder(folder);
            }
            st |= ST_NEEDS_UPDATE;
        }
        String folder = be.installFolder(it.id);
        if (st == ST_NONE || (st & ST_NEEDS_UPDATE) != 0 || folder == null || !be.folderExists(folder)) {
            int r = be.download(it.id, true);
            if (r == DL_BUSY) {
                for (Item other : items) {
                    if (other.state == State.PENDING) {
                        return; // ours is in flight; this one waits for its turn
                    }
                }
                // a download left over in this process (e.g. by a loop that threw): it is most likely this item, wait for it
                be.log("Steam is busy with a download we did not start, waiting for it as ID=" + it.id);
                r = DL_OK;
            }
            if (r == DL_OK) {
                it.state = State.PENDING;
                it.promoted = true;
                it.startedAt = t;
                it.lastPollAt = t;
                it.bytes = -1;
                it.total = 0;
                it.lastState = -1;
                lastActivity = t;
                return;
            }
            it.error = "DownloadItemFalse";
            it.state = State.FAIL;
            return;
        }
        if ((st & ST_INSTALLED) != 0) {
            it.state = State.READY;
            return;
        }
        it.error = "UnknownItemState " + st;
        it.state = State.FAIL;
    }

    private void poll(Item it, long t) {
        if (t - it.lastPollAt < cfg.pollMs) {
            return;
        }
        it.lastPollAt = t;
        long st = be.itemState(it.id);
        if (st != it.lastState) {
            it.lastState = st;
            be.log("state " + stateString(st) + " ID=" + it.id);
        }
        if ((st & ST_DOWNLOADING) != 0) {
            lastActivity = t;
        }
        long[] p = new long[2];
        if (be.downloadInfo(it.id, p) && p[0] != it.bytes) {
            if (p[0] > it.bytes) {
                lastGlobalProgress = t;
                lastActivity = t;
            }
            it.bytes = p[0];
            it.total = p[1];
        }
    }

    /**
     * Steam has worked on none of our downloads for promoteMs (no Downloading flag, no new bytes): the oldest one that
     * was requested with normal priority is requested again with high priority. Steam normally takes about 4 s to start
     * an item, so this only fires when it leaves queued downloads waiting. When most downloads need such a push, the
     * loop goes on one item at a time, every request with high priority, as the game itself does.
     */
    private void pushOldest(long t) {
        lastActivity = t;
        Item oldest = null;
        for (Item it : items) {
            if (it.state == State.PENDING && !it.promoted && (oldest == null || it.startedAt < oldest.startedAt)) {
                oldest = it;
            }
        }
        if (oldest == null) {
            return;
        }
        oldest.promoted = true;
        pushes++;
        be.log("Steam works on none of our downloads for " + cfg.promoteMs / 1000 + " s, requesting ID=" + oldest.id
                + " with high priority (push " + pushes + ")");
        int r = be.download(oldest.id, true);
        if (r == DL_FAIL) {
            oldest.error = "DownloadItemFalse";
            oldest.state = State.FAIL;
            return;
        }
        if (parallel > 1 && pushes >= PUSHES_TO_SEQUENTIAL && pushes * 2 >= downloaded) {
            parallel = 1;
            be.log("Steam does not start queued downloads by itself here (" + pushes + " pushes for " + downloaded
                    + " downloads): one item at a time from now on");
        }
    }

    /** Nothing moved for stallSeconds: request the oldest pending item again (high priority), or give it up. */
    private void kickOldest(long t) {
        Item oldest = null;
        for (Item it : items) {
            if (it.state == State.PENDING && (oldest == null || it.startedAt < oldest.startedAt)) {
                oldest = it;
            }
        }
        if (oldest == null) {
            return;
        }
        lastGlobalProgress = t;
        int r = be.download(oldest.id, true);
        if (r == DL_BUSY) {
            // the only possible answer for an item Steam still holds: the host's watchdog restarts the server
            be.log("no progress for " + cfg.stallSeconds + " s on ID=" + oldest.id
                    + "; Steam still holds it and cannot be asked again, waiting for the host to restart the server");
            return;
        }
        if (r == DL_FAIL) {
            oldest.error = "DownloadItemFalse";
            oldest.state = State.FAIL;
            return;
        }
        if (oldest.kicks >= cfg.maxKicks) {
            be.log("no progress for " + cfg.stallSeconds + " s after " + oldest.kicks + " re-requests, giving up on ID=" + oldest.id);
            oldest.error = "stalled";
            oldest.state = State.FAIL;
            return;
        }
        oldest.kicks++;
        kicks++;
        be.log("no progress for " + cfg.stallSeconds + " s, requested ID=" + oldest.id + " again (" + oldest.kicks + "/" + cfg.maxKicks + ")");
        oldest.promoted = true;
        lastActivity = t;
    }

    /** One line for the host's screen: key=value pairs, ASCII only (the server's stdout garbles anything else). */
    private String info(int done, int total, long elapsedMs) {
        StringBuilder sb = new StringBuilder(160);
        int active = 0;
        long bytes = 0;
        StringBuilder ids = new StringBuilder();
        for (Item it : items) {
            if (it.bytes > 0) {
                bytes += it.bytes;
            }
            if (it.state != State.PENDING) {
                continue;
            }
            active++;
            if (active <= 4) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(it.id).append(':').append(Math.max(it.bytes, 0)).append('/').append(it.total);
            }
        }
        sb.append("v=2;done=").append(done).append(";total=").append(total).append(";active=").append(active)
                .append(";bytes=").append(bytes).append(";kicks=").append(kicks).append(";pushes=").append(pushes)
                .append(";par=").append(parallel).append(";elapsed=").append(elapsedMs / 1000)
                .append(";ids=").append(ids);
        return sb.toString();
    }

    public static String stateString(long st) {
        if (st == 0) {
            return "None";
        }
        StringBuilder sb = new StringBuilder();
        String[] names = {"Subscribed", "LegacyItem", "Installed", "NeedsUpdate", "Downloading", "DownloadPending"};
        for (int i = 0; i < names.length; i++) {
            if ((st & (1L << i)) != 0) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }
}
