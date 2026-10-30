package com.druid.hostunstuck;

import java.nio.file.Path;
import com.druid.hostunstuck.server.NetworkBufferSize;

/**
 * Run with the same jar-first class path the Host screen gives the server.
 * Verifies every class we replace and the hash gate against the installed game.
 */
public final class SelfTest {
    private static Path own;
    private static boolean ok = true;

    public static void main(String[] args) throws Exception {
        own = Path.of(SelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        System.out.println("class path: " + System.getProperty("java.class.path"));
        checkShadow("zombie.network.GameServerWorkshopItems");
        checkShadow("zombie.savefile.PlayerDB");
        checkShadow("zombie.savefile.PlayerDB$PlayerData");
        checkShadow("zombie.savefile.ServerPlayerDB");
        checkShadow("zombie.savefile.ServerPlayerDB$NetworkCharacterData");
        checkGate(Inject.TARGET, "vanilla.sha256");
        checkGate(Inject.PLAYER_DB_TARGET, "playerdb.sha256");
        checkGate(Inject.SERVER_PLAYER_DB_TARGET, "serverplayerdb.sha256");
        int bytes = NetworkBufferSize.bytes();
        System.out.println("network/save buffer: " + bytes + " bytes");
        if (bytes < 2 * 1024 * 1024 || bytes > 1024 * 1024 * 1024) {
            ok = false;
            System.out.println("buffer size outside supported bounds");
        }
        System.out.println(ok ? "SELFTEST OK" : "SELFTEST FAILED");
        if (!ok) {
            System.exit(1);
        }
    }

    private static void checkShadow(String name) throws Exception {
        Class<?> c = Class.forName(name, false, SelfTest.class.getClassLoader());
        String src = c.getProtectionDomain().getCodeSource().getLocation().toString();
        boolean shadow = src.replace('\\', '/').endsWith("/" + own.getFileName().toString());
        System.out.println(name + " from: " + src + (shadow ? " (ours)" : " (THE GAME'S: shadowing failed)"));
        ok &= shadow;
    }

    private static void checkGate(String target, String resource) throws Exception {
        String expected = Inject.readResource(resource);
        String actual = Inject.vanillaHash(own, target);
        boolean match = expected != null && expected.equalsIgnoreCase(actual);
        System.out.println(target + " sha256 " + actual + (match ? " = build" : " != build " + expected));
        ok &= match;
    }
}
