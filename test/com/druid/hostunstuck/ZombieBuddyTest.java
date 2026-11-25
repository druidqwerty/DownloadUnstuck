package com.druid.hostunstuck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import zombie.ZomboidFileSystem;
import zombie.network.CoopMaster;

/**
 * Headless ZombieBuddy integration test: runs under {@code -javaagent:ZombieBuddy.jar=patches_jar=...:com.druid.hostunstuck}
 * against the real 42.22.2 {@code projectzomboid.jar}. Verifies that:
 * <ul>
 *   <li>{@link Inject#init()} passes all 6 SHA-256 gates and resolves all {@code CoopMaster} MethodHandles,</li>
 *   <li>{@link Patches.Launch} transforms {@code CoopMaster.launchServer(String, String, int, boolean)} cleanly,</li>
 *   <li>calling {@code CoopMaster.instance.launchServer(3)} intercepts via {@code launchServer(4)}, injects
 *       {@code -javaagent:<HostUnstuck.jar>} before {@code zombie.network.GameServer}, skips vanilla, and
 *       propagates {@link IOException} cleanly when {@code java.home} is invalid,</li>
 *   <li>disabling {@link Inject#setEnabled(boolean)} falls through to vanilla {@code CoopMaster.launchServer}.</li>
 * </ul>
 */
public final class ZombieBuddyTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path tempCache = Files.createTempDirectory("hostunstuck_zbtest_");
        ZomboidFileSystem.instance.setCacheDir(tempCache.toString());

        // patches_jar= loads in Phase.PREMAIN; in the real game Loader.loadMods runs Main.main in Phase.MAIN
        Main.main(new String[0]);

        String status = Inject.status();
        System.out.println("Inject.status() = " + status + ", active = " + Inject.isActive());
        check(Inject.isActive(), "Inject is active after ZombieBuddy Main.main");
        check(status.startsWith("ready "), "status starts with 'ready ': " + status);

        String savedJavaHome = System.getProperty("java.home");
        String bogusJavaHome = tempCache.resolve("no_such_jre").toString();
        System.setProperty("java.home", bogusJavaHome);
        try {
            // 1) Active: CoopMaster.launchServer(3) -> launchServer(4) -> Inject.launchWithAgent
            boolean threw = false;
            try {
                CoopMaster.instance.launchServer("TestSrv", "TestUser", 2048);
            } catch (IOException expected) {
                threw = true;
            }
            check(threw, "IOException from bogus java.home propagated out of CoopMaster.launchServer");
            List<String> cmd = Inject.lastCommand();
            check(cmd != null, "Inject.launchWithAgent ran and recorded command");
            if (cmd != null) {
                int agentIdx = -1;
                int mainIdx = cmd.indexOf("zombie.network.GameServer");
                for (int i = 0; i < cmd.size(); i++) {
                    if (cmd.get(i).startsWith("-javaagent:") && cmd.get(i).endsWith("HostUnstuck.jar")) {
                        agentIdx = i;
                        break;
                    }
                }
                check(agentIdx > 0 && mainIdx > agentIdx,
                        "-javaagent:<HostUnstuck.jar> placed before zombie.network.GameServer (agentIdx="
                                + agentIdx + ", mainIdx=" + mainIdx + ")");
                check(cmd.contains("-servername") && cmd.contains("TestSrv"), "command has -servername TestSrv");
            }
            check("TestSrv".equals(CoopMaster.instance.getServerName()),
                    "CoopMaster.serverName updated via VarHandle");

            // 2) softreset(3) -> launchServer(4, softreset=true)
            threw = false;
            try {
                CoopMaster.instance.softreset("ResetSrv", "TestUser", 2048);
            } catch (IOException expected) {
                threw = true;
            }
            check(threw, "IOException propagated on softreset too");
            cmd = Inject.lastCommand();
            check(cmd != null && cmd.contains("-Dsoftreset"), "softreset command includes -Dsoftreset");

            // 3) Disabled: falls through to vanilla CoopMaster.launchServer (lastCommand stays unchanged)
            List<String> before = Inject.lastCommand();
            Inject.setEnabled(false);
            threw = false;
            try {
                CoopMaster.instance.launchServer("VanillaSrv", "TestUser", 2048);
            } catch (IOException expected) {
                threw = true;
            }
            check(threw, "vanilla CoopMaster.launchServer ran and threw on bogus java.home");
            check(Inject.lastCommand() == before, "Inject.launchWithAgent skipped when disabled");
            check("VanillaSrv".equals(CoopMaster.instance.getServerName()),
                    "vanilla CoopMaster.launchServer updated serverName");
        } finally {
            System.setProperty("java.home", savedJavaHome);
            Inject.setEnabled(true);
            deleteTree(tempCache);
        }

        System.out.println(failures == 0 ? "ZOMBIEBUDDY TEST PASSED" : ("ZOMBIEBUDDY TEST FAILED: " + failures));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void check(boolean cond, String what) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) {
            failures++;
        }
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
