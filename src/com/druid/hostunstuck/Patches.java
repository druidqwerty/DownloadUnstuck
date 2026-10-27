package com.druid.hostunstuck;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * ZombieBuddy advice around the two methods that start the co-op server process (zombie.network.CoopMaster).
 * The bodies are inlined into the game class, so they only call {@link Inject}.
 */
public class Patches {

    @Patch(className = "zombie.network.CoopMaster", methodName = "launchServer")
    public static class Launch {
        @Patch.OnEnter
        public static void enter() {
            Inject.enter();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            Inject.exit();
        }
    }

    @Patch(className = "zombie.network.CoopMaster", methodName = "softreset")
    public static class SoftReset {
        @Patch.OnEnter
        public static void enter() {
            Inject.enter();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            Inject.exit();
        }
    }
}
