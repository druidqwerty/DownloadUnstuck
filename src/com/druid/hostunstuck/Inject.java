package com.druid.hostunstuck;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import com.druid.hostunstuck.server.Downloader;
import com.druid.hostunstuck.server.Settings;
import zombie.ZomboidFileSystem;

/**
 * Client side of the Java part. The Host screen starts the server as a separate JVM with
 * "-Djava.class.path=" + System.getProperty("java.class.path") (CoopMaster.launchServer). While that method runs,
 * this class puts HostUnstuck.jar in front of the property, so the server loads our zombie.network.GameServerWorkshopItems
 * instead of the game's one. The property is restored right after. Nothing else in the game reads it at that time.
 *
 * Safety: the jar carries the SHA-256 of the game's class it was built against (vanilla.sha256). When the installed
 * game has a different class (a game update), the Java part switches itself off and the game's own loop runs.
 */
public final class Inject {
    public static final String VERSION = Downloader.VERSION;
    static final String TARGET = "zombie/network/GameServerWorkshopItems.class";

    private static Path jar;
    private static String status = "not initialised";
    private static boolean ready;
    private static volatile boolean enabled = true;
    private static int depth;
    private static String savedClassPath;
    private static int launches;

    private Inject() {
    }

    public static synchronized void init() {
        try {
            jar = Path.of(Inject.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            String expected = readResource("vanilla.sha256");
            String actual = vanillaHash(jar);
            if (actual == null) {
                status = "the game's GameServerWorkshopItems.class was not found: parallel downloads off";
            } else if (expected == null || !actual.equalsIgnoreCase(expected)) {
                status = "the game's download code changed since this build (" + actual.substring(0, 12) + " vs "
                        + (expected == null ? "?" : expected.substring(0, 12)) + "): parallel downloads off";
            } else {
                ready = true;
                status = "ready " + VERSION;
            }
        } catch (Throwable t) {
            status = "error: " + t;
        }
        log(status + " (jar " + jar + ")");
    }

    /** SHA-256 of the game's own copy of the class, i.e. the one that is not inside our jar. */
    public static String vanillaHash(Path ownJar) throws IOException {
        String own = ownJar == null ? null : ownJar.toUri().toString().replace("file:///", "file:/");
        Enumeration<URL> urls = ClassLoader.getSystemResources(TARGET);
        while (urls.hasMoreElements()) {
            URL u = urls.nextElement();
            String s = u.toString().replace("file:///", "file:/");
            if (own != null && s.contains(own)) {
                continue;
            }
            try (InputStream in = u.openStream()) {
                return sha256(in.readAllBytes());
            }
        }
        return null;
    }

    static String readResource(String name) throws IOException {
        try (InputStream in = Inject.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "?";
        }
    }

    /** Entered CoopMaster.launchServer / softreset (both overloads: the depth counter keeps it to one change). */
    public static synchronized void enter() {
        if (++depth != 1) {
            return;
        }
        if (!ready || !enabled) {
            log("launch: Java part " + (ready ? "disabled" : "not ready") + ", the game's own download loop runs");
            return;
        }
        Settings s = Settings.load(settingsPath());
        if (s.maxParallel < 1) {
            log("launch: parallel downloads switched off in the options, the game's own download loop runs");
            return;
        }
        savedClassPath = System.getProperty("java.class.path", "");
        String cp = jar + File.pathSeparator + savedClassPath;
        System.setProperty("java.class.path", cp);
        launches++;
        log("launch " + launches + ": server class path = " + cp + " (" + s + ")");
    }

    public static synchronized void exit() {
        if (--depth != 0) {
            return;
        }
        if (savedClassPath != null) {
            System.setProperty("java.class.path", savedClassPath);
            savedClassPath = null;
        }
    }

    public static String status() {
        return status + (enabled ? "" : "; switched off by the crash guard until the game restarts");
    }

    public static boolean isActive() {
        return ready && enabled;
    }

    /** The Lua side switches the Java part off for the rest of the session after repeated server deaths. */
    public static boolean setEnabled(boolean on) {
        enabled = on;
        log(on ? "enabled" : "disabled until the game restarts");
        return ready;
    }

    static Path settingsPath() {
        try {
            return Path.of(ZomboidFileSystem.instance.getCacheDir(), "Lua", "HostUnstuck_server.ini");
        } catch (Throwable t) {
            return null;
        }
    }

    static void log(String s) {
        System.out.println("[HostUnstuck] java: " + s);
    }
}
