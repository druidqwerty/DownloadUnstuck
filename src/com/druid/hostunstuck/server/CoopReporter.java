package com.druid.hostunstuck.server;

import zombie.core.Translator;
import zombie.network.CoopSlave;
import zombie.network.GameServer;

/**
 * Server-side status reporter for {@link Downloader}. Kept outside package {@code zombie.network}
 * because {@code projectzomboid.jar} is signed in Build 42.22.2+: any new unsigned class in
 * {@code zombie.network} would throw a {@link SecurityException} on package-signer mismatch.
 */
public final class CoopReporter implements Downloader.Reporter {
    @Override
    public void count(int total) {
        if (GameServer.coop && CoopSlave.instance != null) {
            CoopSlave.instance.sendMessage("status", null,
                    Translator.getText("UI_ServerStatus_Downloaded_Workshop_Items_Count", total));
        }
    }

    @Override
    public void progress(int done, int total) {
        if (GameServer.coop && CoopSlave.instance != null) {
            CoopSlave.instance.sendMessage("status", null,
                    Translator.getText("UI_ServerStatus_Downloaded_Workshop_Items_Progress", done, total));
        }
    }

    @Override
    public void reinstall(int done, int total) {
        if (GameServer.coop && CoopSlave.instance != null) {
            CoopSlave.instance.sendMessage("status", null,
                    Translator.getText("UI_ServerStatus_Downloaded_Workshop_Reinstall_Progress", done, total));
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
