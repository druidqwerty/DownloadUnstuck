package com.druid.hostunstuck;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Set;
import com.druid.hostunstuck.server.Downloader;

/**
 * Java agent installed in the child co-op server JVM ({@code -javaagent:HostUnstuck.jar}).
 *
 * <p>In Project Zomboid Build 42.22.2+, {@code projectzomboid.jar} is JAR-signed. Prepending an unsigned JAR
 * to {@code java.class.path} causes a {@link SecurityException} (package signer mismatch) as soon as a shadowed
 * {@code zombie.*} class sits alongside a signed vanilla class in the same package. Transforming the 8 target
 * classes in-place via {@link ClassFileTransformer} preserves {@code projectzomboid.jar}'s {@link ProtectionDomain}
 * and signers while keeping zero unsigned {@code zombie.*} classes in {@code HostUnstuck.jar}.
 */
public final class ServerAgent {
    private static final String SHADOW_PREFIX = "/com/druid/hostunstuck/shadows/";
    private static final Set<String> SHADOWS = Set.of(
            "zombie/network/GameServerWorkshopItems",
            "zombie/network/GameServerWorkshopItems$ItemQuery",
            "zombie/savefile/PlayerDB",
            "zombie/savefile/PlayerDB$IPlayerStore",
            "zombie/savefile/PlayerDB$PlayerData",
            "zombie/savefile/PlayerDB$SQLPlayerStore",
            "zombie/savefile/ServerPlayerDB",
            "zombie/savefile/ServerPlayerDB$NetworkCharacterData"
    );

    private ServerAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        Path ownJar;
        HashGate.Result gate;
        try {
            ownJar = Path.of(ServerAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            gate = HashGate.verify(ownJar, false);
        } catch (Throwable t) {
            log("hash gate error, falling back to vanilla: " + t);
            return;
        }
        if (!gate.workshopOk) {
            log("the game's download code changed since this build: shadow transformer off");
            return;
        }
        if (!gate.playerSaveOk) {
            log("the game's player-save code changed since this build: shadow transformer off");
            return;
        }
        final boolean skipWorkshop = "no-workshop".equalsIgnoreCase(agentArgs);
        final boolean serverDb4221 = gate.serverDb4221;
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className == null || classBeingRedefined != null || !SHADOWS.contains(className)) {
                    return null;
                }
                if (skipWorkshop && className.startsWith("zombie/network/GameServerWorkshopItems")) {
                    return null;
                }
                try {
                    if (!verifyBuffer(className, classfileBuffer, serverDb4221)) {
                        log("hash mismatch on load for " + className + ", keeping vanilla bytecode");
                        return null;
                    }
                    byte[] replacement = readShadow(className);
                    if (replacement == null) {
                        log("missing shadow resource for " + className + ", keeping vanilla bytecode");
                        return null;
                    }
                    log("transformed " + className + " (" + classfileBuffer.length + " -> " + replacement.length + " bytes)");
                    return replacement;
                } catch (Throwable t) {
                    log("failed to transform " + className + ": " + t);
                    return null;
                }
            }
        });
        log("active " + Downloader.VERSION + (serverDb4221 ? " (42.21 player-save compat)" : ""));
    }

    private static boolean verifyBuffer(String className, byte[] buf, boolean serverDb4221) throws IOException {
        return switch (className) {
            case "zombie/network/GameServerWorkshopItems" -> HashGate.matchesBytes("vanilla.sha256", buf);
            case "zombie/savefile/PlayerDB" -> HashGate.matchesBytes("playerdb.sha256", buf);
            case "zombie/savefile/PlayerDB$PlayerData" -> HashGate.matchesBytes("playerdb_data.sha256", buf);
            case "zombie/savefile/ServerPlayerDB" ->
                    HashGate.matchesBytes("serverplayerdb.sha256", buf)
                    || (serverDb4221 && HashGate.SERVER_PLAYER_DB_4221_SHA256.equalsIgnoreCase(HashGate.sha256(buf)));
            case "zombie/savefile/ServerPlayerDB$NetworkCharacterData" ->
                    serverDb4221 || HashGate.matchesBytes("serverplayerdb_netdata.sha256", buf);
            default -> true;
        };
    }

    private static byte[] readShadow(String internalName) throws IOException {
        try (InputStream in = ServerAgent.class.getResourceAsStream(SHADOW_PREFIX + internalName + ".class.bin")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void log(String s) {
        System.out.println("[HostUnstuck] agent: " + s);
    }
}
