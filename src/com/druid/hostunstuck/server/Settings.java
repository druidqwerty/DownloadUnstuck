package com.druid.hostunstuck.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings of the server-side download loop. The host's client writes them to Zomboid/Lua/HostUnstuck_server.ini
 * (one key=value per line) from the mod options; the server process reads the file before the Workshop step.
 * No game classes here so the loop can be simulated offline.
 */
public final class Settings {
    /** Downloads requested from Steam at the same time. 0 = the client does not inject the Java part at all. */
    public int maxParallel = 4;
    /** No byte progress from any download for this long -> the oldest download is requested again. */
    public int stallSeconds = 40;
    /** Re-requests per item before it is treated as failed (then the game's own reinstall path applies). */
    public int maxKicks = 2;
    /** How often each pending download is polled, ms. */
    public int pollMs = 100;
    /** How often the structured "hostunstuck" status line is sent to the host's screen, ms. */
    public int infoMs = 1000;

    public static Settings load(Path file) {
        Settings s = new Settings();
        if (file == null || !Files.isRegularFile(file)) {
            return s;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) {
                    continue;
                }
                s.set(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception e) {
            // unreadable file: defaults
        }
        return s;
    }

    public void set(String key, String value) {
        int v;
        try {
            v = (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return;
        }
        switch (key) {
            case "parallel" -> maxParallel = clamp(v, 0, 32);
            case "stallSeconds" -> stallSeconds = clamp(v, 5, 600);
            case "maxKicks" -> maxKicks = clamp(v, 0, 10);
            case "pollMs" -> pollMs = clamp(v, 20, 2000);
            case "infoMs" -> infoMs = clamp(v, 200, 10000);
            default -> {
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String toString() {
        return "parallel=" + maxParallel + " stallSeconds=" + stallSeconds + " maxKicks=" + maxKicks
                + " pollMs=" + pollMs + " infoMs=" + infoMs;
    }
}
