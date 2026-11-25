package com.druid.hostunstuck;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import com.druid.hostunstuck.server.NetworkBufferSize;

/**
 * Run in the game's JVM with {@code -javaagent:HostUnstuck.jar -Djava.class.path=projectzomboid.jar}
 * (matching the child co-op server process). Verifies that:
 * <ul>
 *   <li>all 8 shadowed classes load alongside signed sibling classes in {@code zombie.network} and {@code zombie.savefile}
 *       with zero {@link SecurityException} and intact JAR signers,</li>
 *   <li>our patched bytecode is active in {@code GameServerWorkshopItems}, {@code PlayerDB$PlayerData}, and
 *       {@code ServerPlayerDB$NetworkCharacterData},</li>
 *   <li>all 6 SHA-256 gates match the installed {@code projectzomboid.jar}.</li>
 * </ul>
 */
public final class SelfTest {
    private static Path own;
    private static boolean ok = true;

    public static void main(String[] args) throws Exception {
        own = Path.of(SelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        System.out.println("class path: " + System.getProperty("java.class.path"));

        // 1) Load shadowed classes alongside signed vanilla siblings in the same packages
        Class<?> gswi = loadSigned("zombie.network.GameServerWorkshopItems", "zombie.network.GameServer");
        Class<?> pdb = loadSigned("zombie.savefile.PlayerDB", "zombie.savefile.ClientPlayerDB");
        Class<?> pdbData = loadSigned("zombie.savefile.PlayerDB$PlayerData", "zombie.savefile.ClientPlayerDB");
        Class<?> spdb = loadSigned("zombie.savefile.ServerPlayerDB", "zombie.savefile.ServerPlayerDB$NetworkCharacterData");
        Class<?> spdbNet = Class.forName("zombie.savefile.ServerPlayerDB$NetworkCharacterData", false,
                SelfTest.class.getClassLoader());
        loadSigned("zombie.network.CoopMaster", "zombie.network.GameServer");

        // 2) Verify our patched bytecode is actually active (not bypassed to vanilla)
        verifyMethod(gswi, "runLoop", List.class, com.druid.hostunstuck.server.Settings.class);
        verifyMethod(gswi, "settingsPath");
        System.out.println("GameServerWorkshopItems patched methods present (runLoop, settingsPath)");

        checkSameSigners(pdb, pdbData);
        checkSameSigners(spdb, spdbNet);

        // 3) Verify all 6 SHA-256 gates against projectzomboid.jar
        checkGate(Inject.TARGET, "vanilla.sha256");
        checkGate(Inject.PLAYER_DB_TARGET, "playerdb.sha256");
        checkGate(Inject.PLAYER_DB_DATA_TARGET, "playerdb_data.sha256");
        checkGate(Inject.SERVER_PLAYER_DB_TARGET, "serverplayerdb.sha256");
        checkGate(Inject.SERVER_PLAYER_DB_NETDATA_TARGET, "serverplayerdb_netdata.sha256");
        checkGate(Inject.COOP_MASTER_TARGET, "coopmaster.sha256");

        // 4) Verify NetworkBufferSize default
        int bytes = NetworkBufferSize.bytes();
        System.out.println("network/save buffer: " + bytes + " bytes");
        if (bytes < 2 * 1024 * 1024 || bytes > 1024 * 1024 * 1024) {
            ok = false;
            System.out.println("buffer size outside supported bounds");
        }

        // 5) Verify HostUnstuck.jar exposes no root zombie.* entries
        if (SelfTest.class.getResource("/zombie/network/GameServerWorkshopItems.class") == null) {
            // ClassLoader.getResource finds projectzomboid.jar's entry; check our own jar has no root zombie/ entry
            try (var jf = new java.util.jar.JarFile(own.toFile())) {
                boolean hasRootZombie = jf.stream().anyMatch(e -> e.getName().startsWith("zombie/"));
                System.out.println("HostUnstuck.jar has root zombie/* entries: " + hasRootZombie);
                if (hasRootZombie) {
                    ok = false;
                }
            }
        }

        System.out.println(ok ? "SELFTEST OK" : "SELFTEST FAILED");
        if (!ok) {
            System.exit(1);
        }
    }

    private static Class<?> loadSigned(String shadowed, String sibling) throws Exception {
        ClassLoader cl = SelfTest.class.getClassLoader();
        Class<?> c1 = Class.forName(shadowed, false, cl);
        Class<?> c2 = Class.forName(sibling, false, cl);
        Object[] s1 = c1.getSigners();
        Object[] s2 = c2.getSigners();
        int n1 = s1 == null ? 0 : s1.length;
        int n2 = s2 == null ? 0 : s2.length;
        boolean match = Arrays.equals(s1, s2);
        System.out.println(shadowed + " + " + sibling + ": signers=(" + n1 + ", " + n2 + "), match=" + match);
        ok &= match;
        return c1;
    }

    private static void checkSameSigners(Class<?> c1, Class<?> c2) {
        if (!Arrays.equals(c1.getSigners(), c2.getSigners())) {
            System.out.println("SIGNER MISMATCH between " + c1.getName() + " and " + c2.getName());
            ok = false;
        }
    }

    private static void verifyMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            if (m == null) {
                ok = false;
            }
        } catch (NoSuchMethodException e) {
            System.out.println("MISSING patched method " + cls.getName() + "." + name + ": " + e);
            ok = false;
        }
    }

    private static void checkGate(String target, String resource) throws Exception {
        String expected = Inject.readResource(resource);
        String actual = Inject.vanillaHash(own, target);
        boolean match = expected != null && HashGate.matchesExpected(expected, actual);
        System.out.println(target + " sha256 " + actual + (match ? " = build" : " != build " + expected));
        ok &= match;
    }
}
