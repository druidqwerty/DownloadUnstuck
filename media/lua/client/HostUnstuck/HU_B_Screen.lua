-- Download Unstuck: plugs the watchdog (HU_A_Core.lua) into the vanilla Host screen (OptionScreens/CoopOptionsScreen.lua).
-- Everything here runs in the main menu of the hosting player. The server process is untouched by the Lua side;
-- the optional Java part (ZombieBuddy) replaces its download loop and reports through the "hostunstuck" message.
if not HostUnstuck or not CoopOptionsScreen then return end
local HU = HostUnstuck
if CoopOptionsScreen.__HostUnstuck then return end
CoopOptionsScreen.__HostUnstuck = true

local origMessage = CoopOptionsScreen.onCoopServerMessage
local origPrerender = CoopOptionsScreen.prerender
local origStart = CoopOptionsScreen.onStartButtonDown
local origAbort = CoopOptionsScreen.onAbortButtonDown

local function log(msg)
    print("[HostUnstuck] " .. tostring(msg))
end

local function maxRestarts()
    return tonumber(HU.settings.maxRestarts) or HU.defaults.maxRestarts
end

----------------------------------------------------------------------------------------------------------------
-- hooks used by the core
----------------------------------------------------------------------------------------------------------------
HU.hooks.now = function()
    return getTimestampMs()
end

HU.hooks.abort = function()
    CoopServer:abort()
end

HU.hooks.start = function()
    local inst = CoopOptionsScreen.instance
    if not inst or not inst:getIsVisible() or inst.uiStatus ~= "idle" then return false end
    inst:onStartButtonDown(inst.startButton)
    return inst.uiStatus == "launching"
end

-- Workshop ids in the server's list order (the server processes them in this order).
HU.hooks.loadItems = function(serverName)
    local ids = {}
    local mgr = getServerSettingsManager()
    for i = 1, mgr:getSettingsCount() do
        local settings = mgr:getSettingsByIndex(i - 1)
        if settings:getName() == serverName then
            local str = settings:getServerOptions():getOptionByName("WorkshopItems"):getValue() or ""
            for _, id in ipairs(luautils.split(str, ";")) do
                id = string.gsub(id, "%s", "")
                if id ~= "" then table.insert(ids, id) end
            end
            break
        end
    end
    log(#ids .. " Workshop items in server '" .. tostring(serverName) .. "'")
    return ids
end

-- Titles and sizes through the client's own Steam connection (same call the server settings screen uses).
HU.hooks.queryDetails = function(ids)
    if not getSteamModeActive() then return end
    local list = ArrayList.new()
    for _, id in ipairs(ids) do list:add(id) end
    querySteamWorkshopItemDetails(list, HU.onDetails, HU)
end

-- Settings for the server process: Zomboid/Lua/HostUnstuck_server.ini, read by the Java loop before the Workshop step.
HU.hooks.writeSettings = function()
    local w = getFileWriter("HostUnstuck_server.ini", true, false)
    if not w then return end
    local parallel = 0
    if HU.settings.parallel and not HU.javaDisabled then
        parallel = tonumber(HU.settings.maxParallel) or HU.defaults.maxParallel
    end
    w:write("parallel=" .. HU.fmtInt(parallel) .. "\n")
    w:write("stallSeconds=" .. HU.fmtInt(HU.settings.stallSeconds or HU.defaults.stallSeconds) .. "\n")
    w:write("maxKicks=" .. HU.fmtInt(HU.settings.maxKicks or HU.defaults.maxKicks) .. "\n")
    w:close()
end

HU.hooks.javaEnable = function(on)
    if HostUnstuck_javaEnable then HostUnstuck_javaEnable(on) end
end

-- nil without ZombieBuddy / the Java part; otherwise its status text ("ready 2.0.0", "... off")
function HU.javaStatus()
    if HostUnstuck_java then return HostUnstuck_java() end
    return nil
end

HU.hooks.onEvent = function(kind)
    local inst = CoopOptionsScreen.instance
    if not inst then return end
    local s = HU.s
    if kind == "restarting" then
        inst.serverStatus = getText("UI_HU_Status_Restarting", HU.fmtInt(s.restarts), HU.fmtInt(maxRestarts()))
        inst.abortButton:setEnable(false)
    elseif kind == "relaunched" then
        inst.serverStatus = getText("UI_HU_Status_Relaunch", HU.fmtInt(s.restarts), HU.fmtInt(maxRestarts()))
    elseif kind == "gaveup" then
        inst.serverStatus = getText("UI_HU_Status_GaveUp", HU.fmtInt(s.restarts), HU.itemLabel(s.lastStuckItem))
    end
end

----------------------------------------------------------------------------------------------------------------
-- status text and progress bar
----------------------------------------------------------------------------------------------------------------
local function setBar(inst, p)
    inst.progress = p
    inst.nextProgress = p
    inst.serverProgressBar:setProgress(p)
end

function HU.applyStatus(kind, a, b)
    local inst = CoopOptionsScreen.instance
    if not inst or not kind then return end
    local s = HU.s
    if kind == "progress" or kind == "reinstall" then
        local key = (kind == "progress") and "UI_HU_Status_Downloaded" or "UI_HU_Status_Reinstalling"
        inst.serverStatus = getText(key, HU.fmtInt(a), HU.fmtInt(b))
        local p = (b and b > 0) and (a / b) or 0
        setBar(inst, p * HU.WORKSHOP_BAR_WEIGHT)
    elseif kind == "count" then
        inst.serverStatus = getText("UI_HU_Status_Count", HU.fmtInt(a))
    elseif kind == "fail" then
        inst.serverStatus = getText("UI_HU_Status_ItemFailed", HU.itemLabel(a))
    elseif kind == "relaunch-scheduled" then
        inst.serverStatus = getText("UI_HU_Status_Restarting", HU.fmtInt(s.restarts), HU.fmtInt(maxRestarts()))
        inst.startButton:setEnable(false)
        inst.backButton:setEnable(false)
    elseif kind == "gaveup" then
        inst.serverStatus = getText("UI_HU_Status_GaveUp", HU.fmtInt(s.restarts), HU.itemLabel(s.lastStuckItem))
    end
end

local function shortLabel(id)
    local d = HU.s.details[id]
    if d and d.title and d.title ~= "" then return d.title end
    return tostring(id)
end

-- second, smaller line under the progress bar
function HU.statusLine()
    local s = HU.s
    local phase = s.phase
    if phase == "idle" or phase == "after" then return nil end
    local parts = {}
    if phase == "download" then
        if s.javaActive and s.java then
            local names = {}
            for _, e in ipairs(s.java.ids or {}) do
                local label = shortLabel(e.id)
                if (e.total or 0) > 0 then
                    label = label .. " " .. string.format("%.1f", (e.bytes or 0) / 1048576) .. "/" .. getText("UI_HU_Line_Size", string.format("%.1f", e.total / 1048576))
                end
                table.insert(names, label)
            end
            -- "par" is what the server loop really uses (1 after it switched to one at a time)
            local par = tonumber(s.java.par) or tonumber(HU.settings.maxParallel) or HU.defaults.maxParallel
            table.insert(parts, getText("UI_HU_Line_Parallel", HU.fmtInt(par), HU.fmtInt(s.java.active or 0), table.concat(names, ", ")))
            table.insert(parts, getText("UI_HU_Line_Downloaded", string.format("%.1f", (s.java.bytes or 0) / 1048576)))
            if (s.java.kicks or 0) > 0 then table.insert(parts, getText("UI_HU_Line_Kicks", HU.fmtInt(s.java.kicks))) end
        else
            local index = math.min(s.done + 1, math.max(s.total, 1))
            table.insert(parts, getText("UI_HU_Line_Item", HU.fmtInt(index), HU.fmtInt(s.total), HU.itemLabel(HU.currentItem())))
        end
        if HU.settings.enabled then
            local waited, limit = HU.waitInfo()
            table.insert(parts, getText("UI_HU_Line_Wait", HU.fmtInt(waited), HU.fmtInt(limit)))
        end
    elseif phase == "query" then
        if HU.settings.enabled then
            local waited, limit = HU.waitInfo()
            table.insert(parts, getText("UI_HU_Line_Query", HU.fmtInt(waited), HU.fmtInt(limit)))
        end
    elseif phase == "launching" then
        if HU.settings.enabled then
            local waited, limit = HU.waitInfo()
            table.insert(parts, getText("UI_HU_Line_Launch", HU.fmtInt(waited), HU.fmtInt(limit)))
        end
        local js = HU.javaStatus()
        if js and HU.settings.parallel then table.insert(parts, getText("UI_HU_Line_Java", js)) end
    elseif phase == "gaveup" then
        table.insert(parts, HU.itemLabel(s.lastStuckItem))
    end
    if HU.javaDisabled then
        table.insert(parts, getText("UI_HU_Line_JavaOff", HU.fmtInt(HU.javaDeaths)))
    end
    if not HU.settings.enabled then
        table.insert(parts, getText("UI_HU_Line_Off"))
    elseif s.restarts > 0 or phase == "restarting" then
        table.insert(parts, getText("UI_HU_Line_Restarts", HU.fmtInt(s.restarts), HU.fmtInt(maxRestarts())))
    end
    if #parts == 0 then return nil end
    return table.concat(parts, "   |   ")
end

local function drawLine(self)
    local text = HU.statusLine()
    if not text then return end
    local y = self.serverProgressBar:getBottom() + 6
    self:drawTextCentre(text, self.width / 2, y, 0.85, 0.85, 0.85, 1, UIFont.Small)
end

----------------------------------------------------------------------------------------------------------------
-- vanilla hooks
----------------------------------------------------------------------------------------------------------------
local function onMessage(tag, cookie, payload)
    local kind, a, b
    local ok, err = pcall(function() kind, a, b = HU.onServerMessage(tag, cookie, payload) end)
    if not ok then log("message error: " .. tostring(err)) end
    origMessage(tag, cookie, payload)
    if kind then
        local ok2, err2 = pcall(HU.applyStatus, kind, a, b)
        if not ok2 then log("status error: " .. tostring(err2)) end
    end
end
CoopOptionsScreen.onCoopServerMessage = onMessage

function CoopOptionsScreen:prerender()
    origPrerender(self)
    local ok, err = pcall(HU.tick)
    if not ok then log("tick error: " .. tostring(err)) end
    pcall(drawLine, self)
end

function CoopOptionsScreen:onStartButtonDown(button, x, y)
    local auto = HU.s.autoStart
    -- the settings file must exist before the server process starts (it reads it during the Workshop step)
    if not auto and HU.hooks.writeSettings then pcall(HU.hooks.writeSettings) end
    origStart(self, button, x, y)
    if self.uiStatus == "launching" and getSteamModeActive() then
        local ok, err = pcall(HU.onStart, auto, CoopConnection.servername)
        if not ok then log("start error: " .. tostring(err)) end
    end
end

function CoopOptionsScreen:onAbortButtonDown(button, x, y)
    pcall(HU.onUserAbort)
    origAbort(self, button, x, y)
end

-- The screen may already exist (Lua reloaded after the mod list changed): re-point what it captured by value.
local inst = CoopOptionsScreen.instance
if inst then
    Events.OnCoopServerMessage.Remove(origMessage)
    Events.OnCoopServerMessage.Add(onMessage)
    if inst.startButton then inst.startButton.onclick = CoopOptionsScreen.onStartButtonDown end
    if inst.abortButton then inst.abortButton.onclick = CoopOptionsScreen.onAbortButtonDown end
    inst.prerender = CoopOptionsScreen.prerender
    inst.onStartButtonDown = CoopOptionsScreen.onStartButtonDown
    inst.onAbortButtonDown = CoopOptionsScreen.onAbortButtonDown
end

log("loaded " .. HU.VERSION .. ", Java part: " .. tostring(HU.javaStatus() or "absent (no ZombieBuddy)"))
