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
            boolean queued, active;
        }

        long clock;
        int concurrency = 4;
        long overheadMs = 5000;
        long bytesPerSec = 1_500_000;
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
            if (e.queued) {
                st |= Downloader.ST_DOWNLOAD_PENDING;
            }
            if (e.active) {
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
        public boolean download(long id, boolean highPriority) {
            Entry e = entries.get(id);
            if (e.refuse) {
                return false;
            }
            e.downloads++;
            if (highPriority && e.hangsUntilKick) {
                e.hangsUntilKick = false;
            }
            if (!e.queued && !e.active) {
                e.queued = true;
                e.bytes = 0;
                if (highPriority) {
                    queue.addFirst(e);
                } else {
                    queue.addLast(e);
                }
            } else if (highPriority && e.queued) {
                queue.remove(e);
                queue.addFirst(e);
            }
            return true;
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
            while (active.size() < concurrency && !queue.isEmpty()) {
                Entry e = queue.pollFirst();
                e.queued = false;
                e.active = true;
                e.startedAt = clock;
                active.add(e);
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

        System.out.println("fresh download, Steam handles 4 at once: parallel is faster than one by one");
        Run par = new Run().items(300, 1_000_000, false).go();
        Run seq = new Run().items(300, 1_000_000, false);
        seq.cfg.maxParallel = 1;
        seq.go();
        check(par.result && seq.result, "both ok");
        checkMonotonic(par);
        check(par.dl.kicks() == 0 && seq.dl.kicks() == 0, "no re-requests in a healthy run");
        check(par.seconds() * 3 < seq.seconds(), "parallel " + par.seconds() + " s vs sequential " + seq.seconds() + " s");
        check(seq.steam.entries.values().stream().allMatch(e -> e.downloads == 1), "sequential: every item requested once");
        check(par.rec.infos.size() > 10 && par.rec.infos.get(3).startsWith("v=2;done="), "info lines sent: " + par.rec.infos.get(3));

        System.out.println("Steam serialises the queue: no false stalls with 4 requested at once");
        Run ser = new Run().items(120, 2_000_000, false);
        ser.steam.concurrency = 1;
        ser.go();
        check(ser.result, "ok");
        check(ser.dl.kicks() == 0, "no re-requests while another download makes progress");
        checkMonotonic(ser);

        System.out.println("one item hangs in DownloadPending until it is requested again");
        Run hang = new Run().items(60, 1_000_000, false);
        hang.entry(20).hangsUntilKick = true;
        hang.go();
        check(hang.result, "ok");
        check(hang.dl.kicks() == 1 && hang.entry(20).downloads == 2, "exactly one re-request, for the hung item");
        check(hang.rec.failed.isEmpty(), "no failure reported");
        checkMonotonic(hang);

        System.out.println("an item never moves: re-requests, then the game's reinstall path, then the step fails");
        Run dead = new Run().items(30, 1_000_000, false);
        dead.entry(5).hangsForever = true;
        dead.go();
        check(!dead.result, "run returns false (the server stops, the client restarts it)");
        check(dead.rec.failed.size() == 3 && dead.rec.failed.stream().allMatch(id -> id == dead.items.get(5).id), "the failed item reported 3 times");
        check(dead.rec.reinstalls == 2 && dead.steam.deletes == 2, "two reinstall rounds");
        check(dead.dl.kicks() == 6, "2 re-requests per try: " + dead.dl.kicks());
        long stallBudget = dead.cfg.stallSeconds * 3L * 3L;
        check(dead.seconds() < stallBudget + 60, "gave up within " + dead.seconds() + " s");

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
        check(s.maxParallel == 9 && s.stallSeconds == 5 && s.maxKicks == 10 && s.pollMs == 100, "clamped: " + s);

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
