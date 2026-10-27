package com.druid.hostunstuck;

import java.nio.file.Path;

/**
 * Run by tools/build.sh with the game's own JVM and the class path the Host screen would give the server:
 * java -Djava.class.path=HostUnstuck.jar;projectzomboid.jar com.druid.hostunstuck.SelfTest
 * Checks that the server would load our GameServerWorkshopItems and that the jar matches the installed game.
 */
public final class SelfTest {
    public static void main(String[] args) throws Exception {
        System.out.println("class path: " + System.getProperty("java.class.path"));
        Class<?> c = Class.forName("zombie.network.GameServerWorkshopItems", false, SelfTest.class.getClassLoader());
        String src = c.getProtectionDomain().getCodeSource().getLocation().toString();
        Path own = Path.of(SelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        boolean shadow = src.replace('\\', '/').endsWith("/" + own.getFileName().toString());
        System.out.println("GameServerWorkshopItems from: " + src + (shadow ? " (ours)" : " (THE GAME'S: shadowing failed)"));
        String expected = Inject.readResource("vanilla.sha256");
        String actual = Inject.vanillaHash(own);
        boolean gate = expected != null && expected.equalsIgnoreCase(actual);
        System.out.println("game class sha256 " + actual + (gate ? " = build" : " != build " + expected));
        if (!shadow || !gate) {
            System.out.println("SELFTEST FAILED");
            System.exit(1);
        }
        System.out.println("SELFTEST OK");
    }
}
