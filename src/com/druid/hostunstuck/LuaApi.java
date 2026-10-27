package com.druid.hostunstuck;

import se.krka.kahlua.integration.annotations.LuaMethod;

/** Lua side: HostUnstuck_java() -> status text, HostUnstuck_javaEnable(bool) -> crash guard. */
public class LuaApi {
    @LuaMethod(name = "HostUnstuck_java", global = true)
    public static String status() {
        return Inject.status();
    }

    @LuaMethod(name = "HostUnstuck_javaActive", global = true)
    public static boolean active() {
        return Inject.isActive();
    }

    @LuaMethod(name = "HostUnstuck_javaEnable", global = true)
    public static boolean enable(boolean on) {
        return Inject.setEnabled(on);
    }
}
