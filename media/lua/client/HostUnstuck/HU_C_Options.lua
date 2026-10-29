-- Download Unstuck: Options > Mods page. Values live in Zomboid/Lua/ModOptions.ini (handled by PZAPI) and are copied
-- into HostUnstuck.settings on Apply and when the main menu opens; the server-side ones also go to
-- Zomboid/Lua/HostUnstuck_server.ini for the Java loop.
if not HostUnstuck or not PZAPI or not PZAPI.ModOptions then return end
local HU = HostUnstuck
local D = HU.defaults

local options = PZAPI.ModOptions:create("HostUnstuck", "Download Unstuck")
options:addDescription("UI_HU_Opt_Info")
local enabled = options:addTickBox("enabled", "UI_HU_Opt_Enabled", D.enabled, "UI_HU_Opt_Enabled_tt")
local stallSeconds = options:addSlider("stallSeconds", "UI_HU_Opt_StallSeconds", 15, 300, 5, D.stallSeconds, "UI_HU_Opt_StallSeconds_tt")
local maxRestarts = options:addSlider("maxRestarts", "UI_HU_Opt_MaxRestarts", 1, 30, 1, D.maxRestarts, "UI_HU_Opt_MaxRestarts_tt")
local launchTimeout = options:addSlider("launchTimeout", "UI_HU_Opt_LaunchTimeout", 30, 600, 10, D.launchTimeout, "UI_HU_Opt_LaunchTimeout_tt")
options:addDescription("UI_HU_Opt_JavaInfo")
local parallel = options:addTickBox("parallel", "UI_HU_Opt_Parallel", D.parallel, "UI_HU_Opt_Parallel_tt")

local function num(option, default, lo, hi)
    local v = tonumber(option:getValue())
    if v == nil then return default end
    return math.max(lo, math.min(hi, math.floor(v + 0.5)))
end

local function applySettings()
    HU.settings.enabled = enabled:getValue() and true or false
    HU.settings.stallSeconds = num(stallSeconds, D.stallSeconds, 15, 300)
    HU.settings.maxRestarts = num(maxRestarts, D.maxRestarts, 1, 30)
    HU.settings.launchTimeout = num(launchTimeout, D.launchTimeout, 30, 600)
    HU.settings.parallel = parallel:getValue() and true or false
    HU.settings.maxParallel = 1
    print("[HostUnstuck] options: enabled=" .. tostring(HU.settings.enabled)
        .. " stallSeconds=" .. HU.settings.stallSeconds .. " maxRestarts=" .. HU.settings.maxRestarts
        .. " launchTimeout=" .. HU.settings.launchTimeout .. " javaLoop=" .. tostring(HU.settings.parallel))
    if HU.hooks.writeSettings then pcall(HU.hooks.writeSettings) end
end

options.apply = applySettings
Events.OnMainMenuEnter.Add(applySettings)
