package com.druid.hostunstuck;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import com.druid.hostunstuck.server.Downloader;
import com.druid.hostunstuck.server.Settings;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.ThreadGroups;
import zombie.core.logger.LoggerManager;
import zombie.core.logger.ZipLogs;
import zombie.core.znet.SteamUtils;
import zombie.network.CoopMaster;

/**
 * Client side of the Java part. The Host screen starts the server as a separate JVM from
 * {@code CoopMaster.launchServer(String, String, int, boolean)}. When enabled and all SHA-256 gates match,
 * {@link Patches} intercepts that private method and launches the child JVM with {@code -javaagent:<HostUnstuck.jar>},
 * so {@link ServerAgent} transforms the 8 target classes in-place inside {@code projectzomboid.jar}'s signed
 * {@link java.security.ProtectionDomain}.
 */
public final class Inject {
    public static final String VERSION = Downloader.VERSION;
    static final String TARGET = HashGate.TARGET;
    static final String PLAYER_DB_TARGET = HashGate.PLAYER_DB_TARGET;
    static final String PLAYER_DB_DATA_TARGET = HashGate.PLAYER_DB_DATA_TARGET;
    static final String SERVER_PLAYER_DB_TARGET = HashGate.SERVER_PLAYER_DB_TARGET;
    static final String SERVER_PLAYER_DB_NETDATA_TARGET = HashGate.SERVER_PLAYER_DB_NETDATA_TARGET;
    static final String COOP_MASTER_TARGET = HashGate.COOP_MASTER_TARGET;

    private static Path jar;
    private static String status = "not initialised";
    private static boolean ready;
    private static volatile boolean enabled = true;
    private static int launches;
    private static List<String> lastCommand;

    private static VarHandle fServerName;
    private static VarHandle fAdminUsername;
    private static VarHandle fAdminPassword;
    private static VarHandle fServerTerminationReason;
    private static VarHandle fServerResponded;
    private static VarHandle fServerProcess;
    private static VarHandle fServerCommandStream;
    private static VarHandle fServerThread;
    private static VarHandle fTimeoutWatchThread;
    private static MethodHandle mGetGarbageCollector;
    private static MethodHandle mReadServer;
    private static MethodHandle mWatchServer;

    private Inject() {
    }

    public static synchronized void init() {
        ready = false;
        try {
            jar = Path.of(Inject.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            HashGate.Result gate = HashGate.verify(jar, true);
            if (!gate.workshopOk) {
                status = "the game's download code changed since this build: Java part off";
            } else if (!gate.playerSaveOk) {
                status = "the game's player-save code changed since this build: Java part off";
            } else if (!gate.coopMasterOk) {
                status = "the game's co-op launcher changed since this build: Java part off";
            } else if (jar.toString().indexOf('=') >= 0) {
                status = "jar path contains '=' (unsupported by -javaagent): Java part off";
            } else {
                resolveCoopMasterHandles();
                ready = true;
                status = "ready " + VERSION;
            }
        } catch (Throwable t) {
            status = "error: " + t;
        }
        log(status + " (jar " + jar + ")");
    }

    private static void resolveCoopMasterHandles() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(CoopMaster.class, MethodHandles.lookup());
        fServerName = lookup.findVarHandle(CoopMaster.class, "serverName", String.class);
        fAdminUsername = lookup.findVarHandle(CoopMaster.class, "adminUsername", String.class);
        fAdminPassword = lookup.findVarHandle(CoopMaster.class, "adminPassword", String.class);
        fServerTerminationReason = lookup.findVarHandle(CoopMaster.class, "serverTerminationReason",
                CoopMaster.TerminationReason.class);
        fServerResponded = lookup.findVarHandle(CoopMaster.class, "serverResponded", boolean.class);
        fServerProcess = lookup.findVarHandle(CoopMaster.class, "serverProcess", Process.class);
        fServerCommandStream = lookup.findVarHandle(CoopMaster.class, "serverCommandStream", PrintStream.class);
        fServerThread = lookup.findVarHandle(CoopMaster.class, "serverThread", Thread.class);
        fTimeoutWatchThread = lookup.findVarHandle(CoopMaster.class, "timeoutWatchThread", Thread.class);
        mGetGarbageCollector = lookup.findVirtual(CoopMaster.class, "getGarbageCollector",
                MethodType.methodType(String.class));
        mReadServer = lookup.findVirtual(CoopMaster.class, "readServer", MethodType.methodType(void.class));
        mWatchServer = lookup.findVirtual(CoopMaster.class, "watchServer", MethodType.methodType(void.class));
    }

    /**
     * Called from {@link Patches.Launch} before {@code CoopMaster.launchServer(String, String, int, boolean)}.
     * Returns {@code true} if the server was launched with {@code -javaagent:<HostUnstuck.jar>} (skipping vanilla),
     * or {@code false} to fall through to vanilla {@code CoopMaster.launchServer}.
     */
    public static boolean launchWithAgent(CoopMaster self, String serverName, String username, int memory,
            boolean softreset) throws IOException {
        if (!ready || !enabled) {
            log("launch: Java part " + (ready ? "disabled" : "not ready") + ", the game's own download loop runs");
            return false;
        }
        Settings s = Settings.load(settingsPath());
        if (s.maxParallel < 1) {
            log("launch: parallel downloads switched off in the options, the game's own download loop runs");
            return false;
        }
        String agentJar = jar.toString();
        if (agentJar.indexOf('=') >= 0) {
            log("launch: jar path contains '=', falling back to vanilla");
            return false;
        }
        String javaPath = Paths.get(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
        if (SteamUtils.isSteamModeEnabled()) {
            username = "admin";
        }
        String gc;
        try {
            gc = (String) mGetGarbageCollector.invokeExact(self);
        } catch (Throwable t) {
            log("getGarbageCollector failed, falling back to vanilla: " + t);
            return false;
        }
        ArrayList<String> command = new ArrayList<>();
        command.add(javaPath);
        command.add("-Xms" + memory + "m");
        command.add("-Xmx" + memory + "m");
        command.add("-Djava.library.path=" + System.getProperty("java.library.path"));
        command.add("-Djava.class.path=" + System.getProperty("java.class.path"));
        command.add("-Duser.dir=" + System.getProperty("user.dir"));
        command.add("-Duser.home=" + System.getProperty("user.home"));
        command.add("-Dzomboid.steam=" + (SteamUtils.isSteamModeEnabled() ? "1" : "0"));
        command.add("-Djava.awt.headless=true");
        command.add("-XX:-OmitStackTraceInFastThrow");
        if (gc != null) {
            command.add(gc);
        }
        if (softreset) {
            command.add("-Dsoftreset");
        }
        if (Core.debug) {
            command.add("-Ddebug");
        }
        command.add("-javaagent:" + agentJar);
        command.add("zombie.network.GameServer");
        command.add("-coop");
        if (Core.antiCheats) {
            command.add("-anti-cheats");
        }
        command.add("-servername");
        fServerName.set(self, serverName);
        command.add(serverName);
        command.add("-adminusername");
        fAdminUsername.set(self, username);
        command.add(username);
        command.add("-adminpassword");
        command.add((String) fAdminPassword.get(self));
        command.add("-cachedir=" + ZomboidFileSystem.instance.getCacheDir());
        command.add("-useTimeStampedLogFileNames=" + (LoggerManager.getUseTimeStampedLogFileNames() ? 1 : 0));
        command.add("-debugLogFile=" + LoggerManager.getDebugLogFilePrefix());

        synchronized (Inject.class) {
            launches++;
            lastCommand = List.copyOf(command);
            log("launch " + launches + ": -javaagent:" + agentJar + " (" + s + ")");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        ZipLogs.addZipFile(false);
        fServerTerminationReason.set(self, CoopMaster.TerminationReason.NormalTermination);
        fServerResponded.set(self, false);
        Process proc = pb.start();
        fServerProcess.set(self, proc);
        fServerCommandStream.set(self, new PrintStream(proc.getOutputStream()));
        Thread serverThread = new Thread(ThreadGroups.Workers, () -> invokeVoid(mReadServer, self));
        serverThread.setUncaughtExceptionHandler(GameWindow::uncaughtException);
        fServerThread.set(self, serverThread);
        serverThread.start();
        Thread timeoutWatchThread = new Thread(ThreadGroups.Workers, () -> invokeVoid(mWatchServer, self));
        timeoutWatchThread.setUncaughtExceptionHandler(GameWindow::uncaughtException);
        fTimeoutWatchThread.set(self, timeoutWatchThread);
        timeoutWatchThread.start();
        return true;
    }

    private static void invokeVoid(MethodHandle mh, CoopMaster self) {
        try {
            mh.invokeExact(self);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** SHA-256 of the game's own copy of the class, i.e. the one that is not inside our jar. */
    public static String vanillaHash(Path ownJar) throws IOException {
        return HashGate.vanillaHash(ownJar, TARGET);
    }

    public static String vanillaHash(Path ownJar, String target) throws IOException {
        return HashGate.vanillaHash(ownJar, target);
    }

    static String readResource(String name) throws IOException {
        return HashGate.readResource(name);
    }

    static String sha256(byte[] data) {
        return HashGate.sha256(data);
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

    public static synchronized List<String> lastCommand() {
        return lastCommand;
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
