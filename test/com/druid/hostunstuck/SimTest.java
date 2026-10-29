package com.druid.hostunstuck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.druid.hostunstuck.server.Downloader;
import com.druid.hostunstuck.server.Settings;

/** Offline simulation of the server download loop against a fake Steam. Run: java -cp build/sim com.druid.hostunstuck.SimTest */
public final class SimTest {

    /** Fake Steam: a download queue with per-item overhead, a bandwidth and a concurrency of its own. */
    static final class FakeSteam implements Downloader.Backend {
        static final class Entry {
            long id;
            int size;
            boolean installed, needsUpdate;
            long installedStamp = 1000, detailsStamp = 1000;
            boolean hangsUntilKick, hangsForever, failOnce, refuse;
            int downloads;
            long startedAt, bytes;
            boolean queued, active, high;
        }

        long clock;
        int concurrency = 4;
        /** Per-item setup before the first byte; the real Steam shows no Downloading flag during it (3.7-4.2 s in the logs). */
        long overheadMs = 5000;
        long bytesPerSec = 1_500_000;
        /** A Steam that starts only high-priority requests (the unknown the first in-game test answers). */
        boolean lowPriorityNeverStarts;
        /** The real game's Steam wrapper: one download at a time, any other request is refused. */
        boolean nativeSingle = true;
        int busyRefusals;
        /** Emulates the host's watchdog killing the server process. */
        long killAtMs = Long.MAX_VALUE;
        final Map<Long, Entry> entries = new HashMap<>();
        final Deque<Entry> queue = new ArrayDeque<>();
        final List<Entry> active = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        Downloader dl;
        int deletes;

        Entry add(long id, int size) {
            Entry e = new Entry();
            e.id = id;
            e.size = size;
            entries.put(id, e);
            return e;
        }

        @Override
        public long itemState(long id) {
            Entry e = entries.get(id);
            long st = 0;
            if (e.installed) {
                st |= Downloader.ST_INSTALLED;
            }
            if (e.needsUpdate) {
                st |= Downloader.ST_NEEDS_UPDATE;
            }
            if (e.queued || e.active) {
                st |= Downloader.ST_DOWNLOAD_PENDING;
            }
            if (e.active && !e.hangsUntilKick && !e.hangsForever && clock - e.startedAt >= overheadMs) {
                st |= Downloader.ST_DOWNLOADING;
            }
            return st;
        }

        @Override
        public long installTimeStamp(long id) {
            return entries.get(id).installedStamp;
        }

        @Override
        public String installFolder(long id) {
            return "W/" + id;
        }

        @Override
        public boolean folderExists(String folder) {
            return entries.get(Long.parseLong(folder.substring(2))).installed;
        }

        @Override
        public void deleteFolder(String folder) {
            deletes++;
            entries.get(Long.parseLong(folder.substring(2))).installed = false;
        }

        @Override
        public int download(long id, boolean highPriority) {
            Entry e = entries.get(id);
            // like ZNetJNI's n_DownloadItem: any request while a download runs throws "download in progress already"
            if (nativeSingle && (!queue.isEmpty() || !active.isEmpty())) {
                busyRefusals++;
                return Downloader.DL_BUSY;
            }
            if (e.refuse) {
                return Downloader.DL_FAIL;
            }
            e.downloads++;
            if (highPriority && e.hangsUntilKick) {
                e.hangsUntilKick = false;
            }
            if (!e.queued && !e.active) {
                e.queued = true;
                e.bytes = 0;
                e.high = highPriority;
                if (highPriority) {
                    queue.addFirst(e);
                } else {
                    queue.addLast(e);
                }
            } else if (highPriority && e.queued) {
                e.high = true;
                queue.remove(e);
                queue.addFirst(e);
            }
            return Downloader.DL_OK;
        }

        @Override
        public boolean downloadInfo(long id, long[] out) {
            Entry e = entries.get(id);
            if (!e.active) {
                return false;
            }
            out[0] = e.bytes;
            out[1] = e.size;
            return true;
        }

        @Override
        public void runLoop() {
            if (clock > killAtMs) {
                throw new Killed();
            }
            List<Entry> finished = new ArrayList<>();
            for (Entry e : active) {
                if (e.hangsUntilKick || e.hangsForever) {
                    continue;
                }
                long t = clock - e.startedAt - overheadMs;
                if (t < 0) {
                    continue;
                }
                e.bytes = Math.min(e.size, t * bytesPerSec / 1000);
                if (e.bytes >= e.size) {
                    finished.add(e);
                }
            }
            for (Entry e : finished) {
                active.remove(e);
                e.active = false;
                if (e.failOnce) {
                    e.failOnce = false;
                    dl.onNotDownloaded(e.id, 16);
                } else {
                    e.installed = true;
                    e.needsUpdate = false;
                    e.installedStamp = e.detailsStamp;
                    dl.onDownloaded(e.id);
                }
            }
            while (active.size() < concurrency) {
                Entry next = null;
                for (Entry e : queue) {
                    if (e.high || !lowPriorityNeverStarts) {
                        next = e;
                        break;
                    }
                }
                if (next == null) {
                    break;
                }
                queue.remove(next);
                next.queued = false;
                next.active = true;
                next.startedAt = clock;
                active.add(next);
            }
        }

        @Override
        public long now() {
            return clock;
        }

        @Override
        public void sleep(long ms) {
            clock += ms;
        }

        @Override
        public void log(String s) {
            log.add(s);
        }
    }

    static final class Killed extends RuntimeException {
    }

    static final class Recorder implements Downloader.Reporter {
        final List<int[]> progress = new ArrayList<>();
        final List<Long> failed = new ArrayList<>();
        int reinstalls;
        final List<String> infos = new ArrayList<>();

        @Override
        public void progress(int done, int total) {
            progress.add(new int[]{done, total});
        }

        @Override
        public void reinstall(int done, int total) {
            reinstalls++;
        }

        @Override
        public void failed(long id, String folder) {
            failed.add(id);
        }

        @Override
        public void info(String payload) {
            infos.add(payload);
        }
    }

    static final class Run {
        FakeSteam steam = new FakeSteam();
        Recorder rec = new Recorder();
        Settings cfg = new Settings();
        List<Downloader.Item> items = new ArrayList<>();
        boolean result;
        Downloader dl;

        Run items(int n, int size, boolean installed) {
            for (int i = 0; i < n; i++) {
                long id = 3_000_000_000L + items.size();
                FakeSteam.Entry e = steam.add(id, size);
                e.installed = installed;
                Downloader.Item it = new Downloader.Item(id);
                it.timeCreated = 1;
                it.timeUpdated = e.detailsStamp;
                it.size = size;
                items.add(it);
            }
            return this;
        }

        FakeSteam.Entry entry(int index) {
            return steam.entries.get(items.get(index).id);
        }

        Run go() {
            dl = new Downloader(cfg, steam, rec);
            steam.dl = dl;
            result = dl.run(items);
            return this;
        }

        long seconds() {
            return steam.clock / 1000;
        }
    }

    static int failures;

    static void check(boolean cond, String what) {
        if (!cond) {
            failures++;
            System.out.println("  FAIL: " + what);
        } else {
            System.out.println("  ok: " + what);
        }
    }

    static void checkMonotonic(Run r) {
        int last = -1;
        boolean ok = true;
        for (int[] p : r.rec.progress) {
            ok &= p[0] >= last && p[1] == r.items.size();
            last = p[0];
        }
        check(ok && last == r.items.size(), "progress counter 0.." + r.items.size() + " never goes back (" + r.rec.progress.size() + " lines)");
    }

    public static void main(String[] args) {
        System.out.println("all installed: nothing downloaded");
        Run r = new Run().items(300, 1_000_000, true).go();
        check(r.result, "ok");
        check(r.steam.entries.values().stream().allMatch(e -> e.downloads == 0), "no DownloadItem calls");
        checkMonotonic(r);
        check(r.seconds() < 5, "finished in " + r.seconds() + " s");

        System.out.println("fresh download: one at a time like the game, never refused, no re-requests");
        Run seq = new Run().items(300, 1_000_000, false).go();
        check(seq.result, "ok");
        checkMonotonic(seq);
        check(seq.steam.busyRefusals == 0, "no request while another runs (busy refusals: " + seq.steam.busyRefusals + ")");
        check(seq.steam.entries.values().stream().allMatch(e -> e.downloads == 1), "every item requested once");
        check(seq.dl.kicks() == 0 && seq.dl.pushes() == 0 && seq.dl.parallel() == 1, "no kicks, no pushes, x1");
        check(seq.rec.infos.size() > 10 && seq.rec.infos.get(3).startsWith("v=2;done="), "heartbeat lines sent: " + seq.rec.infos.get(3));

        System.out.println("old settings file with parallel=4: still one at a time, no refusals");
        Run old = new Run().items(40, 1_000_000, false);
        old.cfg.maxParallel = 4;
        old.go();
        check(old.result && old.steam.busyRefusals == 0 && old.dl.parallel() == 1, "ok, x1, refusals " + old.steam.busyRefusals);

        System.out.println("a download left over in the process (first loop threw): adopted, not a crash");
        Run orphan = new Run().items(20, 1_000_000, true);
        orphan.entry(2).installedStamp = 999;
        orphan.steam.download(orphan.items.get(2).id, true); // the leftover request of the loop that threw
        orphan.go();
        check(orphan.result, "ok");
        check(orphan.steam.busyRefusals == 1 && orphan.entry(2).installedStamp == 1000, "one refusal, the item still installed");
        checkMonotonic(orphan);

        System.out.println("an item never moves: no re-request possible, the loop waits, the host's watchdog kills the server");
        Run dead = new Run().items(30, 1_000_000, false);
        dead.entry(5).hangsForever = true;
        dead.steam.killAtMs = 300_000;
        boolean killed = false;
        try {
            dead.go();
        } catch (Killed k) {
            killed = true;
        }
        check(killed, "the loop was still waiting when the process was killed (no crash, no false give-up)");
        check(dead.rec.failed.isEmpty() && dead.steam.deletes == 0, "nothing reported failed, no folder deleted");
        check(dead.rec.progress.get(dead.rec.progress.size() - 1)[0] == 5, "the counter stopped at 5 (what the watchdog sees)");
        check(dead.rec.infos.size() > 250, "heartbeat kept coming while stuck (" + dead.rec.infos.size() + " lines)");

        System.out.println("installed but updated on the Workshop: folder removed and downloaded again");
        Run upd = new Run().items(10, 1_000_000, true);
        upd.entry(3).installedStamp = 999;
        upd.go();
        check(upd.result, "ok");
        check(upd.steam.deletes == 1 && upd.entry(3).downloads == 1 && upd.entry(3).installedStamp == 1000, "one item reinstalled");

        System.out.println("Steam reports a failed download once: reinstall and retry succeed");
        Run once = new Run().items(10, 1_000_000, false);
        once.entry(7).failOnce = true;
        once.go();
        check(once.result, "ok");
        check(once.rec.failed.size() == 1 && once.rec.reinstalls == 1 && once.entry(7).downloads == 2, "one failure, one reinstall, second download fine");
        checkMonotonic(once);

        System.out.println("DownloadItem refused: three tries, then the step fails");
        Run refuse = new Run().items(10, 1_000_000, false);
        refuse.entry(0).refuse = true;
        refuse.go();
        check(!refuse.result && refuse.rec.failed.size() == 3, "fails after 3 tries");

        System.out.println("settings file parsing");
        Settings s = new Settings();
        s.set("parallel", "9");
        s.set("stallSeconds", "3");
        s.set("maxKicks", "99");
        s.set("junk", "1");
        s.set("pollMs", "abc");
        s.set("promoteMs", "10");
        check(s.maxParallel == 9 && s.stallSeconds == 5 && s.maxKicks == 10 && s.pollMs == 100 && s.promoteMs == 1000, "clamped: " + s);

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
