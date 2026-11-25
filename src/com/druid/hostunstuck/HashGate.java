package com.druid.hostunstuck;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;

/**
 * SHA-256 verification against the installed game's {@code projectzomboid.jar}.
 * Shared by the client ({@link Inject}) and the server-side {@link ServerAgent}.
 */
final class HashGate {
    static final String TARGET = "zombie/network/GameServerWorkshopItems.class";
    static final String PLAYER_DB_TARGET = "zombie/savefile/PlayerDB.class";
    static final String PLAYER_DB_DATA_TARGET = "zombie/savefile/PlayerDB$PlayerData.class";
    static final String SERVER_PLAYER_DB_TARGET = "zombie/savefile/ServerPlayerDB.class";
    static final String SERVER_PLAYER_DB_NETDATA_TARGET = "zombie/savefile/ServerPlayerDB$NetworkCharacterData.class";
    static final String COOP_MASTER_TARGET = "zombie/network/CoopMaster.class";

    /** Verified 42.21 ServerPlayerDB.class hash (source-identical to 42.22.2; only outer class hash recorded). */
    static final String SERVER_PLAYER_DB_4221_SHA256 =
            "aeb328c4dfbd436c372b3c2d350ac533dcd4e692b6ee58a5f8c24807ecf31379";

    private HashGate() {
    }

    static final class Result {
        final boolean workshopOk;
        final boolean playerSaveOk;
        final boolean serverDb4221;
        final boolean coopMasterOk;

        Result(boolean workshopOk, boolean playerSaveOk, boolean serverDb4221, boolean coopMasterOk) {
            this.workshopOk = workshopOk;
            this.playerSaveOk = playerSaveOk;
            this.serverDb4221 = serverDb4221;
            this.coopMasterOk = coopMasterOk;
        }
    }

    static Result verify(Path ownJar, boolean checkCoopMaster) throws IOException {
        boolean workshopOk = matchesBuild(ownJar, TARGET, "vanilla.sha256");
        boolean playerDbOk = matchesBuild(ownJar, PLAYER_DB_TARGET, "playerdb.sha256")
                && matchesBuild(ownJar, PLAYER_DB_DATA_TARGET, "playerdb_data.sha256");
        boolean serverDb4222 = matchesBuild(ownJar, SERVER_PLAYER_DB_TARGET, "serverplayerdb.sha256")
                && matchesBuild(ownJar, SERVER_PLAYER_DB_NETDATA_TARGET, "serverplayerdb_netdata.sha256");
        boolean serverDb4221 = false;
        if (playerDbOk && !serverDb4222) {
            String actualServerDb = vanillaHash(ownJar, SERVER_PLAYER_DB_TARGET);
            serverDb4221 = actualServerDb != null && SERVER_PLAYER_DB_4221_SHA256.equalsIgnoreCase(actualServerDb);
        }
        boolean playerSaveOk = playerDbOk && (serverDb4222 || serverDb4221);
        boolean coopMasterOk = !checkCoopMaster || matchesBuild(ownJar, COOP_MASTER_TARGET, "coopmaster.sha256");
        return new Result(workshopOk, playerSaveOk, serverDb4221, coopMasterOk);
    }

    static boolean matchesBytes(String resource, byte[] classBytes) throws IOException {
        String expected = readResource(resource);
        return matchesExpected(expected, sha256(classBytes));
    }

    static boolean matchesBuild(Path ownJar, String target, String resource) throws IOException {
        String expected = readResource(resource);
        String actual = vanillaHash(ownJar, target);
        return matchesExpected(expected, actual);
    }

    static boolean matchesExpected(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        for (String line : expected.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && !candidate.startsWith("#") && candidate.equalsIgnoreCase(actual)) {
                return true;
            }
        }
        return false;
    }

    /** SHA-256 of the game's own copy of the class (skipping {@code ownJar} if present). */
    static String vanillaHash(Path ownJar, String target) throws IOException {
        String own = ownJar == null ? null : ownJar.toUri().toString().replace("file:///", "file:/");
        Enumeration<URL> urls = ClassLoader.getSystemResources(target);
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
        try (InputStream in = HashGate.class.getResourceAsStream(name)) {
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
}
