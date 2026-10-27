-- Host Unstuck: watchdog for the Workshop download step of the "Host" screen.
-- This file is the state machine only (no UI, no game calls except the hooks below), so it can be
-- simulated outside the game. HU_B_Screen.lua plugs it into CoopOptionsScreen, HU_C_Options.lua adds settings.
--
-- How the game works (42.21): CoopServer:launch() starts a separate server process. That process checks
-- every Workshop item in the server's WorkshopItems list in order and downloads missing or updated ones one at a
-- time, with no timeout and no retry. After each finished item it sends the status "Downloaded X of Y" (translated
-- on the server side, which is why Russian arrives as "????????? X ?? Y"). When Steam stalls, the counter stops and
-- nothing else ever happens; Stop + Start spawns a new process that re-checks the installed items in a few seconds
-- and continues from the stuck one. This watchdog does exactly that automatically.
--
-- 2.0: with ZombieBuddy the Java part of the mod replaces the server's download loop (several items at once, a
-- stuck download is requested again). That loop reports through the "hostunstuck" message; here it only means
-- "bytes still moving = not stalled" and a richer second line. Without ZombieBuddy everything works as in 1.0.
HostUnstuck = HostUnstuck or {}
local HU = HostUnstuck
HU.VERSION = "2.0.0"

HU.defaults = { enabled = true, stallSeconds = 40, maxRestarts = 8, launchTimeout = 120, parallel = true, maxParallel = 4, maxKicks = 2 }
HU.settings = HU.settings or {}
for k, v in pairs(HU.defaults) do
    if HU.settings[k] == nil then HU.settings[k] = v end
end

HU.ASSUMED_SPEED = 1.5 * 1024 * 1024 -- bytes/s: a big item gets size/speed extra seconds before it counts as stalled
HU.RELAUNCH_DELAY_MS = 2500          -- pause between "process terminated" and pressing Start again
HU.ABORT_GRACE_MS = 15000            -- if the process does not die after abort(), abort again
HU.WORKSHOP_BAR_WEIGHT = 0.5         -- the Workshop step fills this much of the progress bar; vanilla steps add the rest
HU.JAVA_DEATHS_LIMIT = 2             -- server deaths during the Workshop step with the Java loop active -> Java part off

HU.javaDeaths = HU.javaDeaths or 0   -- per game session, not per launch
HU.javaDisabled = HU.javaDisabled or false

-- hooks filled in by HU_B_Screen.lua (or by the simulation harness)
HU.hooks = HU.hooks or {}
-- HU.hooks.now()                -> milliseconds
-- HU.hooks.abort()              -> kill the server process
-- HU.hooks.start()              -> press Start; returns true when the launch really began
-- HU.hooks.loadItems(name)      -> array of Workshop id strings in the server's list order (may be empty)
-- HU.hooks.queryDetails(ids)    -> asynchronous; results come back through HU.onDetails
-- HU.hooks.onEvent(kind)        -> UI notification ("restarting", "relaunched", "gaveup")
-- HU.hooks.writeSettings()      -> writes Zomboid/Lua/HostUnstuck_server.ini for the server process
-- HU.hooks.javaEnable(on)       -> crash guard: switch the Java part off for the rest of the session

local function now()
    if HU.hooks.now then return HU.hooks.now() end
    return getTimestampMs()
end

local function log(msg)
    print("[HostUnstuck] " .. tostring(msg))
end

function HU.fmtInt(n)
    return string.format("%d", math.floor((tonumber(n) or 0) + 0.5))
end

function HU.reset()
    HU.s = {
        phase = "idle",      -- idle | launching | query | download | after | restarting | gaveup
        phaseStart = 0,
        done = 0, total = 0, lastCountChange = 0,
        stuckOn = nil, stuckRestarts = 0, -- backoff: restarts triggered while the counter showed stuckOn
        restarts = 0,
        userAborted = false, autoStart = false,
        restartPending = false, abortAt = nil, relaunchAt = nil,
        failedItem = nil, expectReinstall = false,
        lastStuckItem = nil, reason = nil,
        javaActive = false, java = nil,   -- Java loop in the server: parsed "hostunstuck" line
        items = HU.s and HU.s.items or nil,
        details = HU.s and HU.s.details or {},
    }
end
HU.reset()

function HU.setPhase(phase)
    local s = HU.s
    if s.phase ~= phase then
        s.phase = phase
        s.phaseStart = now()
    end
end

local function emit(kind)
    if HU.hooks.onEvent then pcall(HU.hooks.onEvent, kind) end
end

-- Workshop id of the item the server is working on: items 1..done are ready, so it is item done+1.
-- With the Java loop several items are in flight; the first of them is reported as the current one.
function HU.currentItem()
    local s = HU.s
    if s.javaActive and s.java and s.java.ids and s.java.ids[1] then return s.java.ids[1].id end
    if s.items and s.items[s.done + 1] then return s.items[s.done + 1] end
    return nil
end

function HU.itemLabel(id)
    if not id then return "?" end
    local d = HU.s.details[id]
    if d and d.title and d.title ~= "" then
        if d.size and d.size > 0 then
            return d.title .. " (" .. getText("UI_HU_Line_Size", string.format("%.1f", d.size / 1048576)) .. ", " .. tostring(id) .. ")"
        end
        return d.title .. " (" .. tostring(id) .. ")"
    end
    return tostring(id)
end

-- Sort a status payload sent by the server process.
-- Returns kind[, a[, b]]: "query" | "progress", done, total | "count", total | "fail", itemId | "terminated-status" | "other-status" | "other"
function HU.classify(payload)
    if type(payload) ~= "string" then return "other" end
    if string.sub(payload, 1, 15) == "UI_ServerStatus" then
        if payload == "UI_ServerStatus_Requesting_Workshop_Item_Details" or payload == "UI_ServerStatus_Downloading_Workshop_Items" then
            return "query"
        end
        if payload == "UI_ServerStatus_Terminated" then return "terminated-status" end
        return "other-status", payload
    end
    -- a failed item is reported as its install folder, e.g. "...\workshop\content\108600\3818327441"
    if string.find(payload, "[/\\]") then
        local id = string.match(payload, "(%d+)%s*$")
        return "fail", id
    end
    local a, b = string.match(payload, "^%D*(%d+)%D+(%d+)%D*$")
    if a then return "progress", tonumber(a), tonumber(b) end
    local c = string.match(payload, "^%D*(%d+)%D*$")
    if c then return "count", tonumber(c) end
    return "other"
end

-- "v=2;done=12;total=308;active=4;bytes=123;kicks=0;elapsed=15;ids=id:bytes/total,..." from the Java loop.
function HU.parseJavaInfo(payload)
    if type(payload) ~= "string" then return nil end
    local info = { ids = {} }
    for key, value in string.gmatch(payload, "(%w+)=([^;]*)") do
        if key == "ids" then
            for id, b, t in string.gmatch(value, "(%d+):(%d+)/(%d+)") do
                table.insert(info.ids, { id = id, bytes = tonumber(b) or 0, total = tonumber(t) or 0 })
            end
        else
            info[key] = tonumber(value) or value
        end
    end
    if info.v == nil then return nil end
    return info
end

function HU.onJavaInfo(payload)
    local s = HU.s
    local info = HU.parseJavaInfo(payload)
    if not info then return nil end
    local t = now()
    if not s.javaActive then
        s.javaActive = true
        log("Java loop reports from the server: " .. payload)
    end
    local old = s.java
    if s.phase == "launching" or s.phase == "query" then HU.setPhase("download") end
    if (info.bytes or 0) > ((old and old.bytes) or 0) or (info.done or 0) ~= s.done then
        s.lastCountChange = t
    end
    s.java = info
    return "java"
end

-- Called for every OnCoopServerMessage. Returns what the UI should show (see HU.applyStatus in HU_B_Screen.lua).
function HU.onServerMessage(tag, cookie, payload)
    local s = HU.s
    if tag == "process-status" and payload == "terminated" then
        return HU.onTerminated()
    end
    if s.phase == "idle" then return nil end
    if tag == "hostunstuck" then return HU.onJavaInfo(payload) end
    if tag ~= "status" then return nil end
    local kind, a, b = HU.classify(payload)
    local t = now()
    if kind == "count" then
        s.total = a
        HU.setPhase("query")
        return "count", a
    elseif kind == "query" then
        HU.setPhase("query")
        return "query"
    elseif kind == "progress" then
        HU.setPhase("download")
        local wasReinstall = s.expectReinstall
        s.expectReinstall = false
        if a ~= s.done or b ~= s.total then
            s.done, s.total = a, b
            s.lastCountChange = t
        end
        if wasReinstall then return "reinstall", a, b end
        return "progress", a, b
    elseif kind == "fail" then
        s.failedItem = a
        s.expectReinstall = true
        s.lastCountChange = t -- the server is actively retrying this item
        return "fail", a
    elseif kind == "terminated-status" then
        HU.setPhase("after")
        return "other"
    elseif kind == "other-status" then
        -- the first "Initialising" comes before the Workshop step; everything else means the step is over
        if not (s.phase == "launching" and a == "UI_ServerStatus_Initialising") then
            HU.setPhase("after")
        end
        return "other"
    end
    return nil
end

-- Results of HU.hooks.queryDetails: info is a Java ArrayList of SteamUGCDetails.
function HU.onDetails(_, status, info)
    if status ~= "Completed" or not info then return end
    local ok, err = pcall(function()
        for i = 1, info:size() do
            local d = info:get(i - 1)
            local id = d:getIDString()
            if id then
                HU.s.details[id] = { title = d:getTitle(), size = d:getFileSize() }
            end
        end
    end)
    if not ok then log("item details failed: " .. tostring(err)) end
end

-- Largest known size among the items being downloaded (bytes), for the stall allowance.
function HU.inFlightSize()
    local s = HU.s
    local size = 0
    if s.javaActive and s.java and s.java.ids then
        for _, e in ipairs(s.java.ids) do
            local d = s.details[e.id]
            local n = math.max(e.total or 0, (d and d.size) or 0)
            if n > size then size = n end
        end
        return size
    end
    local item = HU.currentItem()
    local d = item and s.details[item]
    return (d and d.size) or 0
end

function HU.stallLimitMs()
    local s = HU.s
    local limit = (tonumber(HU.settings.stallSeconds) or HU.defaults.stallSeconds) * 1000
    local size = HU.inFlightSize()
    if size > 0 then
        limit = limit + size / HU.ASSUMED_SPEED * 1000
    end
    if s.stuckOn ~= nil and s.stuckOn == s.done and s.stuckRestarts > 0 then
        limit = limit * (2 ^ s.stuckRestarts)
    end
    return limit
end

function HU.abort()
    if HU.hooks.abort then pcall(HU.hooks.abort) end
end

function HU.requestRestart(reason)
    local s = HU.s
    local max = tonumber(HU.settings.maxRestarts) or HU.defaults.maxRestarts
    s.lastStuckItem = HU.currentItem()
    s.reason = reason
    if s.restarts >= max then
        log("giving up after " .. s.restarts .. " restarts (" .. reason .. "), item " .. tostring(s.lastStuckItem))
        HU.setPhase("gaveup")
        emit("gaveup")
        return false
    end
    s.restarts = s.restarts + 1
    if s.phase == "download" then
        if s.stuckOn == s.done then
            s.stuckRestarts = s.stuckRestarts + 1
        else
            s.stuckOn = s.done
            s.stuckRestarts = 1
        end
    end
    log("restart " .. s.restarts .. "/" .. max .. ": " .. reason .. " at " .. s.done .. "/" .. s.total .. ", item " .. tostring(s.lastStuckItem))
    s.restartPending = true
    s.abortAt = now()
    HU.setPhase("restarting")
    emit("restarting")
    HU.abort()
    return true
end

-- The server died while our Java loop was running: twice in one session and the Java part is switched off.
local function noteJavaDeath()
    local s = HU.s
    if not s.javaActive or HU.javaDisabled then return end
    HU.javaDeaths = HU.javaDeaths + 1
    log("server died with the Java loop active (" .. HU.javaDeaths .. "/" .. HU.JAVA_DEATHS_LIMIT .. ")")
    if HU.javaDeaths >= HU.JAVA_DEATHS_LIMIT then
        HU.javaDisabled = true
        log("Java part switched off for this session; the game's own download loop is used from now on")
        if HU.hooks.javaEnable then pcall(HU.hooks.javaEnable, false) end
    end
end

function HU.onTerminated()
    local s = HU.s
    if s.restartPending then
        s.restartPending = false
        s.abortAt = nil
        s.relaunchAt = now() + HU.RELAUNCH_DELAY_MS
        return "relaunch-scheduled"
    end
    local duringWorkshop = (s.phase == "query" or s.phase == "download")
    if HU.settings.enabled and duringWorkshop and not s.userAborted then
        noteJavaDeath()
        -- the process died in the middle of the Workshop step (item failed three times, Steam dropped): retry within the budget
        local max = tonumber(HU.settings.maxRestarts) or HU.defaults.maxRestarts
        s.lastStuckItem = HU.currentItem()
        if s.restarts < max then
            s.restarts = s.restarts + 1
            s.reason = "died"
            s.relaunchAt = now() + HU.RELAUNCH_DELAY_MS
            HU.setPhase("restarting")
            log("server died during the Workshop step, restart " .. s.restarts .. "/" .. max)
            return "relaunch-scheduled"
        end
        HU.setPhase("gaveup")
        emit("gaveup")
        return "gaveup"
    end
    HU.setPhase("idle")
    return "terminated"
end

-- Start pressed (auto = pressed by the watchdog itself).
function HU.onStart(auto, serverName)
    local s = HU.s
    if not auto then
        local items, details = s.items, s.details
        HU.reset()
        s = HU.s
        s.details = details or {}
        if HU.hooks.loadItems then
            local ok, list = pcall(HU.hooks.loadItems, serverName)
            s.items = ok and list or nil
            if not ok then log("could not read the server's Workshop list: " .. tostring(list)) end
        else
            s.items = items
        end
        if s.items and #s.items > 0 and HU.hooks.queryDetails then
            pcall(HU.hooks.queryDetails, s.items)
        end
    end
    if HU.hooks.writeSettings then
        local ok, err = pcall(HU.hooks.writeSettings)
        if not ok then log("could not write the server settings file: " .. tostring(err)) end
    end
    s.done, s.total = 0, 0
    s.userAborted = false
    s.restartPending = false
    s.relaunchAt = nil
    s.abortAt = nil
    s.expectReinstall = false
    s.failedItem = nil
    s.javaActive = false
    s.java = nil
    s.lastCountChange = now()
    HU.setPhase("launching")
end

function HU.onUserAbort()
    local s = HU.s
    s.userAborted = true
    s.restartPending = false
    s.relaunchAt = nil
    if s.phase ~= "idle" then HU.setPhase("after") end
end

function HU.relaunch()
    local s = HU.s
    s.autoStart = true
    local ok, started = pcall(HU.hooks.start)
    s.autoStart = false
    if ok and started then
        emit("relaunched")
    else
        log("could not press Start again: " .. tostring(started))
        HU.setPhase("gaveup")
        emit("gaveup")
    end
end

-- Call often (every frame is fine); does its own throttling.
function HU.tick()
    local s = HU.s
    local t = now()
    if s.relaunchAt and t >= s.relaunchAt then
        s.relaunchAt = nil
        HU.relaunch()
        return
    end
    if s.nextTick and t < s.nextTick then return end
    s.nextTick = t + 250
    if not HU.settings.enabled then return end
    if s.phase == "restarting" then
        if s.abortAt and t - s.abortAt > HU.ABORT_GRACE_MS then
            s.abortAt = t
            HU.abort()
        end
    elseif s.phase == "launching" or s.phase == "query" then
        local limit = (tonumber(HU.settings.launchTimeout) or HU.defaults.launchTimeout) * 1000
        if t - s.phaseStart > limit then HU.requestRestart(s.phase .. "-timeout") end
    elseif s.phase == "download" then
        if t - s.lastCountChange > HU.stallLimitMs() then HU.requestRestart("stall") end
    end
end

-- Numbers for the second status line: seconds waited and the current limit.
function HU.waitInfo()
    local s = HU.s
    local t = now()
    if s.phase == "download" then
        return math.floor((t - s.lastCountChange) / 1000), math.floor(HU.stallLimitMs() / 1000)
    elseif s.phase == "launching" or s.phase == "query" then
        return math.floor((t - s.phaseStart) / 1000), tonumber(HU.settings.launchTimeout) or HU.defaults.launchTimeout
    end
    return 0, 0
end
