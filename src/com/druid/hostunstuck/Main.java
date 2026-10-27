package com.druid.hostunstuck;

import zombie.network.GameServer;

/** Called by ZombieBuddy when the mod's jar is loaded (client: main menu; server: after the Workshop step). */
public class Main {
    public static void main(String[] args) {
        if (GameServer.server) {
            System.out.println("[HostUnstuck] java: server process, nothing to do here");
            return;
        }
        Inject.init();
    }
}
