"""Simulation of Host Unstuck outside the game (lupa).

Stubs the handful of game globals the mod touches, loads HU_A_Core.lua + HU_B_Screen.lua, then plays scripted
server message sequences against a fake clock and checks what the watchdog does.
Run:  python -I -X utf8 test/sim_test.py   (from the HostUnstuck folder)
"""
import os
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LUA_DIR = os.path.join(ROOT, "media", "lua", "client", "HostUnstuck")

STUBS = r"""
clock = 0
calls = { abort = 0, launch = 0 }
function getTimestampMs() return clock end
function getText(key, ...)  -- "<key> <arg1> <arg2> ...": keys have no placeholders in this harness
    local s = key
    local args = { ... }
    for i = 1, #args do s = s .. " " .. tostring(args[i]) end
    return s
end
function getSteamModeActive() return true end
UIFont = { Small = "small", Large = "large" }
luautils = {}
function luautils.split(str, sep)
    local out = {}
    for piece in string.gmatch(str .. sep, "(.-)" .. sep) do table.insert(out, piece) end
    return out
end
Events = { OnCoopServerMessage = {}, OnMainMenuEnter = {} }
registered = {}
function Events.OnCoopServerMessage.Add(fn) table.insert(registered, fn) end
function Events.OnCoopServerMessage.Remove(fn)
    for i = #registered, 1, -1 do if registered[i] == fn then table.remove(registered, i) end end
end
function Events.OnMainMenuEnter.Add(fn) end
CoopServer = {}
function CoopServer:abort() calls.abort = calls.abort + 1 end
function CoopServer:launch() calls.launch = calls.launch + 1 end
CoopConnection = { servername = "MPMAIN" }
serverItems = {}
function getServerSettingsManager()
    return {
        getSettingsCount = function() return 1 end,
        getSettingsByIndex = function()
            return {
                getName = function() return "MPMAIN" end,
                getServerOptions = function() return { getOptionByName = function() return { getValue = function() return table.concat(serverItems, ";") end } end } end,
            }
        end,
    }
end
ArrayList = { new = function() local t = { n = 0 } ; t.add = function(self, v) self.n = self.n + 1; self[self.n] = v end; return t end }
queried = nil
function querySteamWorkshopItemDetails(list, fn, arg) queried = list end
function print(...) end
written = nil
function getFileWriter(name, create, append)
    local w = { name = name, text = "" }
    w.write = function(self, s) self.text = self.text .. s end
    w.close = function(self) written = self end
    return w
end
javaCalls = { enable = 0, last = nil }
function HostUnstuck_java() return "ready 2.0.0" end
function HostUnstuck_javaActive() return true end
function HostUnstuck_javaEnable(on) javaCalls.enable = javaCalls.enable + 1; javaCalls.last = on; return true end
mainMenuFns = {}
Events.OnMainMenuEnter.Add = function(fn) table.insert(mainMenuFns, fn) end
PZAPI = { ModOptions = {} }
function PZAPI.ModOptions:create(id, name)
    local o = { values = {} }
    local function opt(key, default)
        local x = { v = default }
        x.getValue = function(self) return self.v end
        o.values[key] = x
        return x
    end
    o.addDescription = function() end
    o.addTickBox = function(self, key, _, default) return opt(key, default) end
    o.addSlider = function(self, key, _, lo, hi, step, default) return opt(key, default) end
    optionsPage = o
    return o
end

-- minimal vanilla screen
CoopOptionsScreen = {}
local inst = { serverStatus = "", uiStatus = "idle", progress = 0, nextProgress = 0, width = 800, drawn = nil }
inst.serverProgressBar = { p = 0, setProgress = function(self, p) self.p = p end, getBottom = function() return 100 end }
local function btn() return { enabled = true, setEnable = function(self, b) self.enabled = b end } end
inst.startButton, inst.abortButton, inst.backButton = btn(), btn(), btn()
function inst:getIsVisible() return true end
function inst:drawTextCentre(text) self.drawn = text end
setmetatable(inst, { __index = CoopOptionsScreen }) -- instances look methods up on the class, as in the game
CoopOptionsScreen.instance = inst
function CoopOptionsScreen.onCoopServerMessage(tag, cookie, payload)
    if tag == "status" then inst.serverStatus = payload end
    if tag == "process-status" and payload == "terminated" then
        inst.uiStatus = "idle"; inst.startButton:setEnable(true); inst.abortButton:setEnable(false); inst.backButton:setEnable(true)
        inst.serverStatus = "stopped"
    end
end
function CoopOptionsScreen:prerender() end
function CoopOptionsScreen:onStartButtonDown(button, x, y)
    CoopServer:launch()
    self.uiStatus = "launching"; self.startButton:setEnable(false); self.abortButton:setEnable(true)
end
function CoopOptionsScreen:onAbortButtonDown(button, x, y) CoopServer:abort(); self.abortButton:setEnable(false) end
inst.startButton.onclick = CoopOptionsScreen.onStartButtonDown
inst.abortButton.onclick = CoopOptionsScreen.onAbortButtonDown
Events.OnCoopServerMessage.Add(CoopOptionsScreen.onCoopServerMessage)
"""

DRIVER = r"""
function msg(tag, payload) for _, fn in ipairs(registered) do fn(tag, nil, payload) end end
function status(p) msg("status", p) end
function terminated() msg("process-status", "terminated") end
function advance(ms)  -- run the screen every 100 ms like the game does
    local target = clock + ms
    while clock < target do
        clock = math.min(clock + 100, target)
        CoopOptionsScreen.instance:prerender()
    end
end
function pressStart() CoopOptionsScreen.instance:onStartButtonDown(CoopOptionsScreen.instance.startButton) end
function pressAbort() CoopOptionsScreen.instance:onAbortButtonDown(CoopOptionsScreen.instance.abortButton) end
function S() return HostUnstuck.s end
function I() return CoopOptionsScreen.instance end
"""


def make_runtime():
    lua = lupa.LuaRuntime(unpack_returned_tuples=True)
    lua.execute(STUBS)
    for name in ("HU_A_Core.lua", "HU_B_Screen.lua", "HU_C_Options.lua"):
        with open(os.path.join(LUA_DIR, name), encoding="utf-8") as f:
            lua.execute(f.read())
    lua.execute(DRIVER)
    return lua


def boot(lua, n_items=308):
    lua.execute("serverItems = {}")
    for i in range(n_items):
        lua.execute(f"table.insert(serverItems, '{3000000000 + i}')")
    lua.execute("pressStart()")
    lua.execute("status('UI_ServerStatus_Initialising')")
    lua.execute(f"status('WorkshopItemsCount {n_items}')")
    lua.execute("status('UI_ServerStatus_Downloading_Workshop_Items')")
    lua.execute("status('UI_ServerStatus_Requesting_Workshop_Item_Details')")


def progress(lua, n, total=308, garbled=False, step_ms=0):
    text = f"????????? {n} ?? {total}" if garbled else f"Downloaded {n} of {total}"
    lua.execute(f"status('{text}')")
    if step_ms:
        lua.execute(f"advance({step_ms})")


def check(cond, what):
    if not cond:
        raise AssertionError(what)
    print("  ok:", what)


def test_normal_run():
    print("normal run: no restart, translated status, honest bar")
    lua = make_runtime()
    boot(lua)
    check(lua.eval("S().phase") == "query", "query phase after the count message")
    check(lua.eval("queried.n") == 308, "details queried for all 308 ids")
    for n in range(0, 308):
        progress(lua, n, step_ms=5000)
    check(lua.eval("S().phase") == "download", "download phase")
    progress(lua, 308)
    check(lua.eval("S().phase") == "after", "after phase as soon as the counter reads 308 of 308")
    check(lua.eval("I().serverStatus") == "UI_HU_Status_Downloaded 308 308", "status translated: " + lua.eval("I().serverStatus"))
    check(abs(lua.eval("I().serverProgressBar.p") - 0.5) < 1e-9, "bar at the workshop weight when all items are done")
    lua.execute("advance(90000)")
    check(lua.eval("calls.abort") == 0, "no abort while the server makes its backup and UPnP before the next status (90 s)")
    lua.execute("status('UI_ServerStatus_Initialising')")
    check(lua.eval("S().phase") == "after", "after phase once the Workshop step is over")
    lua.execute("advance(600000)")
    check(lua.eval("calls.abort") == 0, "no abort during a healthy run (10 min of world loading)")
    check(lua.eval("calls.launch") == 1, "one launch")


def test_garbled_russian():
    print("garbled RU payload is parsed")
    lua = make_runtime()
    boot(lua)
    progress(lua, 259, garbled=True)
    check(lua.eval("S().done") == 259 and lua.eval("S().total") == 308, "259 of 308 parsed from ????????? 259 ?? 308")
    check(lua.eval("I().serverStatus") == "UI_HU_Status_Downloaded 259 308", "status uses the mod's own key: " + lua.eval("I().serverStatus"))
    check(lua.eval("HostUnstuck.currentItem()") == "3000000259", "current item is the 260th id of the server list")
    check("3000000259" in lua.eval("HostUnstuck.statusLine()"), "second line names the item: " + lua.eval("HostUnstuck.statusLine()"))


def test_stall_restart_backoff_giveup():
    print("stall → abort → relaunch, backoff on the same counter, give up")
    lua = make_runtime()
    boot(lua)
    for n in range(0, 135):
        progress(lua, n, step_ms=1000)
    # stall at 134 (item 135)
    lua.execute("advance(39000)")
    check(lua.eval("calls.abort") == 0, "no abort before stallSeconds")
    lua.execute("advance(2000)")
    check(lua.eval("calls.abort") == 1, "abort after 40 s without progress")
    check(lua.eval("S().phase") == "restarting", "restarting phase")
    check(lua.eval("I().serverStatus") == "UI_HU_Status_Restarting 1 8", "restarting status: " + lua.eval("I().serverStatus"))
    lua.execute("terminated()")
    check(lua.eval("I().startButton.enabled") == False, "Start stays disabled while the relaunch is pending")
    lua.execute("advance(2000)")
    check(lua.eval("calls.launch") == 1, "not relaunched before the delay")
    lua.execute("advance(1000)")
    check(lua.eval("calls.launch") == 2, "relaunched after the delay")
    check(lua.eval("S().phase") == "launching" and lua.eval("S().restarts") == 1, "launching again, restarts=1")
    check(lua.eval("I().serverStatus") == "UI_HU_Status_Relaunch 1 8", "relaunch status: " + lua.eval("I().serverStatus"))
    # second process re-checks quickly and stalls on the same item
    lua.execute("status('UI_ServerStatus_Initialising')")
    lua.execute("status('WorkshopItemsCount 308')")
    lua.execute("status('UI_ServerStatus_Requesting_Workshop_Item_Details')")
    for n in range(0, 135):
        progress(lua, n)
    lua.execute("advance(100)")
    check(lua.eval("S().stuckOn") == 134 and lua.eval("S().stuckRestarts") == 1, "backoff bookkeeping")
    check(lua.eval("HostUnstuck.stallLimitMs()") == 80000, "limit doubled to 80 s on the same counter value")
    lua.execute("advance(60000)")
    check(lua.eval("calls.abort") == 1, "no second abort at 60 s")
    lua.execute("advance(21000)")
    check(lua.eval("calls.abort") == 2, "second abort after 80 s")
    # progress past the stuck item resets the backoff
    lua.execute("terminated(); advance(3000)")
    lua.execute("status('WorkshopItemsCount 308')")
    for n in range(0, 140):
        progress(lua, n)
    lua.execute("advance(100)")
    check(lua.eval("HostUnstuck.stallLimitMs()") == 40000, "limit back to 40 s once the counter moved past the stuck value")
    # exhaust the budget
    restarts = lua.eval("S().restarts")
    check(restarts == 2, "restarts counted: %d" % restarts)
    for i in range(10):
        lua.execute("advance(%d)" % (lua.eval("HostUnstuck.stallLimitMs()") + 1000))
        if lua.eval("S().phase") == "gaveup":
            break
        lua.execute("terminated(); advance(3000)")
        lua.execute("status('WorkshopItemsCount 308')")
        for n in range(0, 140):
            progress(lua, n)
        lua.execute("advance(100)")
    check(lua.eval("S().phase") == "gaveup", "gave up eventually")
    check(lua.eval("S().restarts") == 8, "exactly maxRestarts restarts: %d" % lua.eval("S().restarts"))
    check(lua.eval("I().serverStatus").startswith("UI_HU_Status_GaveUp 8 3000000139"), "give-up status names the item: " + lua.eval("I().serverStatus"))


def test_big_item_gets_time():
    print("big item: size-based extra time")
    lua = make_runtime()
    boot(lua)
    lua.execute("HostUnstuck.s.details['3000000010'] = { title = 'Big Map', size = 600 * 1048576 }")
    for n in range(0, 11):
        progress(lua, n)
    lua.execute("advance(100)")
    limit = lua.eval("HostUnstuck.stallLimitMs()")
    check(abs(limit - (40000 + 400000)) < 1, "limit = 40 s + 600 MB / 1.5 MB/s = 440 s (got %s)" % limit)
    lua.execute("advance(300000)")
    check(lua.eval("calls.abort") == 0, "no restart at 5 min for a 600 MB item")
    check("Big Map" in lua.eval("HostUnstuck.statusLine()"), "title shown: " + lua.eval("HostUnstuck.statusLine()"))


def test_user_abort():
    print("user presses Stop: no relaunch")
    lua = make_runtime()
    boot(lua)
    progress(lua, 10)
    lua.execute("pressAbort()")
    lua.execute("terminated(); advance(10000)")
    check(lua.eval("calls.launch") == 1 and lua.eval("S().phase") == "idle", "idle after a manual stop")


def test_death_during_download():
    print("process dies during the Workshop step: relaunch within the budget")
    lua = make_runtime()
    boot(lua)
    progress(lua, 10)
    lua.execute("terminated()")
    check(lua.eval("S().phase") == "restarting", "restart scheduled after an unexpected death")
    lua.execute("advance(3000)")
    check(lua.eval("calls.launch") == 2 and lua.eval("S().restarts") == 1, "relaunched once")
    lua.execute("status('UI_ServerStatus_LoadingWorld')")
    lua.execute("terminated(); advance(5000)")
    check(lua.eval("calls.launch") == 2 and lua.eval("S().phase") == "idle", "a death after the Workshop step is left alone")


def test_failed_item_and_reinstall():
    print("failed item: path status then Reinstalling")
    lua = make_runtime()
    boot(lua)
    progress(lua, 5)
    lua.execute(r"status('G:\\Steam\\steamapps\\common\\ProjectZomboid\\steamapps\\workshop\\content\\108600\\3000000005')")
    check(lua.eval("S().failedItem") == "3000000005", "failed item id taken from the path")
    check(lua.eval("I().serverStatus").startswith("UI_HU_Status_ItemFailed"), "failure status shown")
    lua.execute("status('Reinstalling 5 of 308')")
    check(lua.eval("I().serverStatus") == "UI_HU_Status_Reinstalling 5 308", "reinstall status: " + lua.eval("I().serverStatus"))
    lua.execute("status('Downloaded 6 of 308')")
    check(lua.eval("I().serverStatus") == "UI_HU_Status_Downloaded 6 308", "back to normal progress: " + lua.eval("I().serverStatus"))


def test_launch_timeout():
    print("server never reaches the Workshop step: restart after launchTimeout")
    lua = make_runtime()
    lua.execute("pressStart()")
    lua.execute("status('UI_ServerStatus_Initialising')")
    check(lua.eval("S().phase") == "launching", "first Initialising keeps the launching phase")
    lua.execute("advance(119000)")
    check(lua.eval("calls.abort") == 0, "no abort before launchTimeout")
    lua.execute("advance(2000)")
    check(lua.eval("calls.abort") == 1, "abort after 120 s without the Workshop step")


def test_disabled():
    print("watchdog off: only cosmetics")
    lua = make_runtime()
    lua.execute("HostUnstuck.settings.enabled = false")
    boot(lua)
    progress(lua, 3)
    lua.execute("advance(600000)")
    check(lua.eval("calls.abort") == 0, "never aborts when disabled")
    check(lua.eval("I().serverStatus") == "UI_HU_Status_Downloaded 3 308", "status still translated: " + lua.eval("I().serverStatus"))
    check("UI_HU_Line_Off" in lua.eval("HostUnstuck.statusLine()"), "second line says it is off")
    lua.execute("terminated(); advance(5000)")
    check(lua.eval("calls.launch") == 1, "no relaunch when disabled")


def java_info(lua, done, total, active, nbytes, ids, kicks=0, par=4):
    lua.execute(f"msg('hostunstuck', 'v=2;done={done};total={total};active={active};bytes={nbytes};kicks={kicks};pushes=0;par={par};elapsed=1;ids={ids}')")


def test_java_bytes_count_as_progress():
    print("Java loop: moving bytes are progress; no size allowance, the server's own re-request gets 20 s")
    lua = make_runtime()
    boot(lua)
    progress(lua, 10)
    ids = "3000000010:500000/500000000,3000000011:0/1000000"  # a 477 MB item in flight
    for i in range(120):
        java_info(lua, 10, 308, 2, 1000000 * (i + 1), ids)
        lua.execute("advance(1000)")
    check(lua.eval("calls.abort") == 0, "no restart in 2 min with the counter stuck but bytes moving")
    check(lua.eval("S().javaActive") == True, "Java mode detected")
    line = lua.eval("HostUnstuck.statusLine()")
    check("UI_HU_Line_Parallel 4 2" in line and "UI_HU_Line_Downloaded 114.4" in line, "second line shows the parallel info (120e6 B = 114.4 MiB): " + line)
    check(lua.eval("HostUnstuck.currentItem()") == "3000000010", "current item = first in flight")
    check(lua.eval("HostUnstuck.stallLimitMs()") == 60000, "limit = 40 s + 20 s for the re-request, whatever the size in flight")
    for i in range(55):
        java_info(lua, 10, 308, 2, 120000000, ids, kicks=1 if i >= 40 else 0)
        lua.execute("advance(1000)")
    check(lua.eval("calls.abort") == 0, "no restart at 56 s without new bytes: the re-request at 40 s still has time")
    for i in range(7):
        java_info(lua, 10, 308, 2, 120000000, ids, kicks=1)
        lua.execute("advance(1000)")
    check(lua.eval("calls.abort") == 1 and lua.eval("S().reason") == "stall", "restart at 60 s without new bytes")


def test_java_silence_restarts_fast():
    print("Java loop: no report for 30 s = server frozen inside Steam (the 2026-10-27 hang) -> restart")
    lua = make_runtime()
    boot(lua)
    progress(lua, 10)
    lua.execute("HostUnstuck.s.details['3000000010'] = { title = 'Big Map', size = 600 * 1048576 }")
    java_info(lua, 10, 308, 1, 0, "3000000010:0/0")
    lua.execute("advance(29000)")
    check(lua.eval("calls.abort") == 0, "no restart after 29 s of silence")
    lua.execute("advance(2000)")
    check(lua.eval("calls.abort") == 1 and lua.eval("S().reason") == "silent", "restart after 30 s without any report, big item or not")
    plain = make_runtime()
    boot(plain)
    progress(plain, 10)
    plain.execute("advance(35000)")
    check(plain.eval("calls.abort") == 0, "the game's own loop reports only per item: silence alone is no reason there")


def test_java_line_shows_real_parallelism():
    print("Java loop: the second line shows what the server loop really uses")
    lua = make_runtime()
    boot(lua)
    progress(lua, 3)
    java_info(lua, 3, 308, 1, 1000, "3000000003:1000/2000000", par=1)
    line = lua.eval("HostUnstuck.statusLine()")
    check("UI_HU_Line_Parallel 1 1" in line, "x1 after the loop switched to one at a time: " + line)


def test_lines_of_a_killed_process_are_ignored():
    print("lines that arrive after the abort do not move the state back")
    lua = make_runtime()
    boot(lua)
    for n in range(0, 20):
        progress(lua, n)
    lua.execute("advance(41000)")
    check(lua.eval("calls.abort") == 1 and lua.eval("S().phase") == "restarting", "stall -> abort, restarting")
    progress(lua, 308)
    java_info(lua, 308, 308, 0, 5000, "")
    check(lua.eval("S().phase") == "restarting" and lua.eval("S().done") == 19, "still restarting, the counter is not moved by the killed process")
    lua.execute("advance(16000)")
    check(lua.eval("calls.abort") == 2, "abort repeated when the process is still alive after 15 s")
    lua.execute("terminated(); advance(3000)")
    check(lua.eval("calls.launch") == 2 and lua.eval("S().phase") == "launching", "relaunched once it is gone")


def test_java_crash_guard():
    print("Java loop: two server deaths during the Workshop step switch the Java part off")
    lua = make_runtime()
    boot(lua)
    progress(lua, 5)
    java_info(lua, 5, 308, 4, 1000, "3000000005:0/0")
    lua.execute("terminated()")
    check(lua.eval("HostUnstuck.javaDeaths") == 1 and lua.eval("javaCalls.enable") == 0, "first death counted, Java part still on")
    lua.execute("advance(3000)")
    check(lua.eval("calls.launch") == 2, "relaunched")
    lua.execute("status('WorkshopItemsCount 308')")
    progress(lua, 5)
    java_info(lua, 5, 308, 4, 1000, "3000000005:0/0")
    lua.execute("terminated()")
    check(lua.eval("HostUnstuck.javaDisabled") == True and lua.eval("javaCalls.enable") == 1 and lua.eval("javaCalls.last") == False, "second death: HostUnstuck_javaEnable(false) called once")
    lua.execute("advance(3000)")
    check(lua.eval("calls.launch") == 3, "relaunched again, now with the game's own loop")
    check("parallel=0" in lua.eval("written.text"), "settings file for the next launch says parallel=0: " + lua.eval("written.text").replace("\n", " "))
    lua.execute("status('WorkshopItemsCount 308')")
    progress(lua, 5)
    check("UI_HU_Line_JavaOff 2" in lua.eval("HostUnstuck.statusLine()"), "second line says the Java part is off")
    lua.execute("terminated()")
    check(lua.eval("HostUnstuck.javaDeaths") == 2, "deaths without the Java loop are not counted")


def test_settings_file():
    print("server settings file written on Start and on Apply")
    lua = make_runtime()
    boot(lua)
    text = lua.eval("written.text")
    check(text == "parallel=4\nstallSeconds=40\nmaxKicks=2\n", "defaults written on Start: " + text.replace("\n", " "))
    lua.execute("optionsPage.values.parallel.v = false; optionsPage.values.stallSeconds.v = 90; mainMenuFns[1]()")
    text = lua.eval("written.text")
    check(text == "parallel=0\nstallSeconds=90\nmaxKicks=2\n", "options applied and written: " + text.replace("\n", " "))
    check(lua.eval("HostUnstuck.settings.stallSeconds") == 90 and lua.eval("HostUnstuck.settings.parallel") == False, "settings table updated")


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failed = 0
    for t in tests:
        try:
            t()
        except Exception as e:  # noqa
            failed += 1
            print("  FAIL:", t.__name__, "->", repr(e))
    print("%d tests, %d failed" % (len(tests), failed))
    sys.exit(1 if failed else 0)
