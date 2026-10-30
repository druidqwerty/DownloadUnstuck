package com.druid.hostunstuck.server;

import java.nio.file.Path;
import zombie.ZomboidFileSystem;

/** Maximum player-save/network-character buffer for the hosted server. */
public final class NetworkBufferSize {
    private static volatile int cachedBytes = -1;

    private NetworkBufferSize() {
    }

    public static int bytes() {
        int current = cachedBytes;
        if (current < 0) {
            current = Settings.load(settingsPath()).netBufferSizeMB * 1024 * 1024;
            cachedBytes = current;
        }
        return current;
    }

    private static Path settingsPath() {
        try {
            return Path.of(ZomboidFileSystem.instance.getCacheDir(), "Lua", "HostUnstuck_server.ini");
        } catch (Throwable t) {
            return null;
        }
    }
}
