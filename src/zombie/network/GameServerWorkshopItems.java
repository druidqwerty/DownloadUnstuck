package zombie.network;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.druid.hostunstuck.server.Downloader;
import com.druid.hostunstuck.server.Settings;
import com.druid.hostunstuck.server.SteamBackend;
import zombie.ZomboidFileSystem;
import zombie.core.Translator;
import zombie.core.znet.ISteamWorkshopCallback;
import zombie.core.znet.SteamUGCDetails;
import zombie.core.znet.SteamUtils;
import zombie.core.znet.SteamWorkshop;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

/**
 * Host Unstuck replacement for the game's GameServerWorkshopItems (42.21). The server process loads this copy instead
 * of the game's one when the host's client puts HostUnstuck.jar first on the server's class path (see
 * com.druid.hostunstuck.Inject). Same public API: Install(ArrayList) and the GameServer.workshopInstallFolders /
 * workshopTimeStamps arrays it fills. The item query and the tail are the game's code; the download loop is
 * {@link Downloader}.
 */
public class GameServerWorkshopItems {
    private static void noise(String s) {
        DebugLog.log("Workshop: " + s);
    }

    public static boolean Install(ArrayList<Long> itemIDList) {
        if (!GameServer.server) {
            return false;
        }
        if (itemIDList.isEmpty()) {
            return true;
        }
        Settings cfg = Settings.load(settingsPath());
        noise("[HostUnstuck] " + Downloader.VERSION + " replaces the download loop: " + cfg);
        ArrayList<Downloader.Item> items = new ArrayList<>();
        for (long itemID : itemIDList) {
            items.add(new Downloader.Item(itemID));
        }
        if (GameServer.coop) {
            CoopSlave.status("UI_ServerStatus_Requesting_Workshop_Item_Details");
        }
        if (!QueryItemDetails(items)) {
            return false;
        }
        int result = runLoop(items, cfg);
        if (result < 0 && cfg.maxParallel > 1) {
            noise("[HostUnstuck] the parallel loop threw, running it again one item at a time");
            ArrayList<Downloader.Item> again = new ArrayList<>();
            for (Downloader.Item it : items) {
                Downloader.Item copy = new Downloader.Item(it.id);
                copy.timeCreated = it.timeCreated;
                copy.timeUpdated = it.timeUpdated;
                copy.size = it.size;
                copy.title = it.title;
                again.add(copy);
            }
            cfg.maxParallel = 1;
            result = runLoop(again, cfg);
        }
        if (result <= 0) {
            return false;
        }
        GameServer.workshopInstallFolders = new String[itemIDList.size()];
        GameServer.workshopTimeStamps = new long[itemIDList.size()];
        for (int i = 0; i < itemIDList.size(); ++i) {
            long itemID = itemIDList.get(i);
            String folder = SteamWorkshop.instance.GetItemInstallFolder(itemID);
            if (folder == null) {
                noise("GetItemInstallFolder() failed ID=" + itemID);
                return false;
            }
            noise(itemID + " installed to " + folder);
            GameServer.workshopInstallFolders[i] = folder;
            GameServer.workshopTimeStamps[i] = SteamWorkshop.instance.GetItemInstallTimeStamp(itemID);
        }
        return true;
    }

    /** 1 = all items ready, 0 = an item failed for good (the game stops the server), -1 = our loop threw. */
    private static int runLoop(List<Downloader.Item> items, Settings cfg) {
        SteamBackend be = new SteamBackend();
        Downloader dl = new Downloader(cfg, be, new CoopReporter());
        be.attach(dl);
        try {
            return dl.run(items) ? 1 : 0;
        } catch (Throwable t) {
            DebugType.General.printException(t, LogSeverity.Error);
            return -1;
        } finally {
            be.close();
        }
    }

    private static Path settingsPath() {
        try {
            return Path.of(ZomboidFileSystem.instance.getCacheDir(), "Lua", "HostUnstuck_server.ini");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean QueryItemDetails(List<Downloader.Item> items) {
        long[] itemIDs = new long[items.size()];
        for (int i = 0; i < items.size(); ++i) {
            itemIDs[i] = items.get(i).id;
        }
        ItemQuery query = new ItemQuery();
        query.handle = SteamWorkshop.instance.CreateQueryUGCDetailsRequest(itemIDs, query);
        if (query.handle == 0L) {
            return false;
        }
        while (true) {
            SteamUtils.runLoop();
            if (query.completed) {
                break;
            }
            if (query.notCompleted) {
                return false;
            }
            try {
                Thread.sleep(33L);
            } catch (Exception ex) {
                DebugType.General.printException(ex, LogSeverity.Error);
            }
        }
        for (SteamUGCDetails details : query.details) {
            for (Downloader.Item it : items) {
                if (it.id != details.getID()) {
                    continue;
                }
                it.timeCreated = details.getTimeCreated();
                it.timeUpdated = details.getTimeUpdated();
                it.size = details.getFileSize();
                it.title = details.getTitle();
                break;
            }
        }
        return true;
    }

    /** The game's status lines (translated on the server, as before) plus our structured "hostunstuck" line. */
    private static final class CoopReporter implements Downloader.Reporter {
        @Override
        public void count(int total) {
            if (GameServer.coop && CoopSlave.instance != null) {
                CoopSlave.instance.sendMessage("status", null, Translator.getText("UI_ServerStatus_Downloaded_Workshop_Items_Count", total));
            }
        }

        @Override
        public void progress(int done, int total) {
            if (GameServer.coop && CoopSlave.instance != null) {
                CoopSlave.instance.sendMessage("status", null, Translator.getText("UI_ServerStatus_Downloaded_Workshop_Items_Progress", done, total));
            }
        }

        @Override
        public void reinstall(int done, int total) {
            if (GameServer.coop && CoopSlave.instance != null) {
                CoopSlave.instance.sendMessage("status", null, Translator.getText("UI_ServerStatus_Downloaded_Workshop_Reinstall_Progress", done, total));
            }
        }

        @Override
        public void failed(long id, String folder) {
            CoopSlave.status(folder != null ? folder : "workshop/content/108600/" + id);
        }

        @Override
        public void info(String payload) {
            if (GameServer.coop && CoopSlave.instance != null) {
                CoopSlave.instance.sendMessage("hostunstuck", null, payload);
            }
        }
    }

    private static final class ItemQuery implements ISteamWorkshopCallback {
        long handle;
        ArrayList<SteamUGCDetails> details;
        boolean completed;
        boolean notCompleted;

        @Override
        public void onItemQueryCompleted(long handle, int numResults) {
            noise("onItemQueryCompleted handle=" + handle + " numResult=" + numResults);
            if (handle != this.handle) {
                return;
            }
            SteamWorkshop.instance.RemoveCallback(this);
            ArrayList<SteamUGCDetails> list = new ArrayList<>();
            for (int i = 0; i < numResults; ++i) {
                SteamUGCDetails d = SteamWorkshop.instance.GetQueryUGCResult(handle, i);
                if (d != null) {
                    list.add(d);
                }
            }
            this.details = list;
            SteamWorkshop.instance.ReleaseQueryUGCRequest(handle);
            this.completed = true;
        }

        @Override
        public void onItemQueryNotCompleted(long handle, int result) {
            noise("onItemQueryNotCompleted handle=" + handle + " result=" + result);
            if (handle != this.handle) {
                return;
            }
            SteamWorkshop.instance.RemoveCallback(this);
            SteamWorkshop.instance.ReleaseQueryUGCRequest(handle);
            this.notCompleted = true;
        }

        @Override
        public void onItemCreated(long itemID, boolean bUserNeedsToAcceptWorkshopLegalAgreement) {
        }

        @Override
        public void onItemNotCreated(int result) {
        }

        @Override
        public void onItemUpdated(boolean bUserNeedsToAcceptWorkshopLegalAgreement) {
        }

        @Override
        public void onItemNotUpdated(int result) {
        }

        @Override
        public void onItemSubscribed(long itemID) {
        }

        @Override
        public void onItemNotSubscribed(long itemID, int result) {
        }

        @Override
        public void onItemDownloaded(long itemID) {
        }

        @Override
        public void onItemNotDownloaded(long itemID, int result) {
        }
    }
}
