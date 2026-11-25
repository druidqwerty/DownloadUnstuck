package com.druid.hostunstuck;

import java.io.IOException;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.network.CoopMaster;

/**
 * ZombieBuddy advice on {@code CoopMaster.launchServer(String, String, int, boolean)}, which both
 * {@code launchServer(3)} and {@code softreset(3)} delegate to. Launches the child server JVM with
 * {@code -javaagent:<HostUnstuck.jar>} and skips the vanilla body when active; falls through to vanilla otherwise.
 */
public class Patches {

    @Patch(className = "zombie.network.CoopMaster", methodName = "launchServer", strictMatch = true)
    public static class Launch {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.This CoopMaster self,
                @Patch.Argument(0) String serverName,
                @Patch.Argument(1) String username,
                @Patch.Argument(2) int memory,
                @Patch.Argument(3) boolean softreset) throws IOException {
            return Inject.launchWithAgent(self, serverName, username, memory, softreset);
        }
    }
}
