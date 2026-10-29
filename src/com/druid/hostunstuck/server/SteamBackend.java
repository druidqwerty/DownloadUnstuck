package com.druid.hostunstuck.server;

import java.io.File;
import zombie.ZomboidFileSystem;
import zombie.core.znet.ISteamWorkshopCallback;
import zombie.core.znet.SteamUtils;
import zombie.core.znet.SteamWorkshop;
import zombie.debug.DebugLog;

/** The real Steam behind {@link Downloader}: one callback object for all downloads, routed by item id. */
public final class SteamBackend implements Downloader.Backend, ISteamWorkshopCallback {
    private Downloader dl;

    public void attach(Downloader dl) {
        this.dl = dl;
    }

    public void close() {
        SteamWorkshop.instance.RemoveCallback(this);
    }

    @Override
    public long itemState(long id) {
        return SteamWorkshop.instance.GetItemState(id);
    }

    @Override
    public long installTimeStamp(long id) {
        return SteamWorkshop.instance.GetItemInstallTimeStamp(id);
    }

    @Override
    public String installFolder(long id) {
        return SteamWorkshop.instance.GetItemInstallFolder(id);
    }

    @Override
    public boolean folderExists(String folder) {
        return folder != null && new File(folder).exists();
    }

    @Override
    public void deleteFolder(String folder) {
        ZomboidFileSystem.deleteDirectory(folder);
    }

    @Override
    public int download(long id, boolean highPriority) {
        try {
            return SteamWorkshop.instance.DownloadItem(id, highPriority, this) ? Downloader.DL_OK : Downloader.DL_FAIL;
        } catch (RuntimeException e) {
            // ZNetJNI throws "download in progress already" for any request while one download runs
            String m = String.valueOf(e.getMessage());
            if (m.contains("in progress")) {
                return Downloader.DL_BUSY;
            }
            log("DownloadItem(" + id + ") threw " + e);
            return Downloader.DL_FAIL;
        }
    }

    @Override
    public boolean downloadInfo(long id, long[] out) {
        return SteamWorkshop.instance.GetItemDownloadInfo(id, out);
    }

    @Override
    public void runLoop() {
        SteamUtils.runLoop();
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void log(String s) {
        DebugLog.log("Workshop: [HostUnstuck] " + s);
    }

    // ISteamWorkshopCallback

    @Override
    public void onItemDownloaded(long itemID) {
        if (dl != null) {
            dl.onDownloaded(itemID);
        }
    }

    @Override
    public void onItemNotDownloaded(long itemID, int result) {
        if (dl != null) {
            dl.onNotDownloaded(itemID, result);
        }
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
    public void onItemQueryCompleted(long handle, int numResults) {
    }

    @Override
    public void onItemQueryNotCompleted(long handle, int result) {
    }
}
