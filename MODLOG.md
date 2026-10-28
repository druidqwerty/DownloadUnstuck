# Host Unstuck — MODLOG

Goal: PZ B42 client-side mod that fixes the "Host" screen hanging on "Downloaded X of Y" while the co-op
server downloads Workshop items. 1.0 (Lua): watches the counter, restarts the server (Stop + Start) when it
stalls, readable status (RU arrives garbled from the server process), the item being downloaded, an honest bar.
2.0 (Java, ZombieBuddy): replaces the server's download loop with a parallel one that re-requests stuck items.

## 2.0.0-rc2 (2026-10-29): review before the first in-game run, offline tests green, NOT run in the game

User (2026-10-29): "Проверяй надо ли доделать или улучшить HostUnstuck. если надо, то делай". The game has not run
since 2026-10-28 01:58 (console.txt), so rc1 was never tested. Game still 42.21 (buildid 25485521, jar 2026-10-12,
class hash = gate 4d2ababb…), ZombieBuddy still 2.3.4.

### Findings
- **`require=\ZombieBuddy` contradicted the design** (README, Steam draft, options all say ZB is optional). ZB's loader
  (`/tmp/zbsrc/.../JavaModInfo.java`, `Loader.loadMods`) reads only `javaJarFile`/`javaPkgName`/`ZBVersion*` of active
  mods and never looks at `require`; BetterVehicleDynamics, ImmersiveLootingB42, pz3dMinimap ship jars without it.
  Removed: without ZB the Lua watchdog works, with ZB the jar loads as before.
- **The real hang was a frozen server thread.** `Logs/logs_2026-10-27_23-38/2026-10-27_23-34_DebugLog-server.txt`
  ends at 23:37:32.249 = its mtime = the last poll line ("Installed|DownloadPending" for 3818327441); vanilla logs a
  poll every 100 ms, the process lived 41 s more. So the thread was stuck inside a Steam call: no Java re-request can
  help, only killing the process. rc1's Lua limit with the Java loop was stallSeconds + size of the biggest item in
  flight (a 500 MB item = +333 s). Fixed: no `hostunstuck` line for 30 s → restart at once (`HU.JAVA_SILENCE_MS`);
  with the Java loop bytes are the only progress, limit = stallSeconds + 20 s (`HU.JAVA_KICK_GRACE_MS`), so the
  server's own re-request at stallSeconds gets 20 s (rc1: Lua restarted at the same moment, the README's "re-request
  before anything is restarted" was not true).
- **After "Downloaded N of N" the server needs 8.5 s** (ZipBackup "Start making backup" 5.3 s + UPnP 3.2 s, all four
  logged runs) before "UI_ServerStatus_Initialising"; rc1 kept the stall timer running (40 s), a big save or a slow
  router would have killed a healthy server. Fixed: N of N → phase "after".
- Steam timings from the logs (`tools/vanilla_log_timings.py <coop-console log>`, 58 vanilla downloads): request → Downloading flag median
  3.7 s, max 4.2 s; bytes are reported (`GetItemDownloadInfo` 0/0 during setup, then real bytes, 100 MB ≈ 10 s).
- The game itself always downloads one item with `DownloadItem(id, true)` (server and `ConnectToServerState`), so
  "normal priority downloads start by themselves" is unverified. Added the push: Steam works on none of our downloads
  (no Downloading flag, no new bytes, no request/finish) for `promoteMs` 8 s → the oldest normal-priority item is
  requested with high priority; after ≥3 pushes with pushes × 2 ≥ finished downloads the loop goes one at a time
  (high priority, = vanilla). SimTest "lazy Steam": 396 s vs 348 s one-by-one (rc1 would have waited 40 s per item).
- Lines still in the pipe of a killed process could move the phase out of "restarting" (and so skip the 15 s
  re-abort); ignored now.

### Pre-flight API audit (rc2)
| API | verdict | evidence |
|---|---|---|
| `CoopOptionsScreen` fields/methods (`instance`, `uiStatus`, `startButton`/`abortButton`/`backButton`, `serverProgressBar`, `progress`/`nextProgress`, `serverStatus`, `onStartButtonDown`, `onAbortButtonDown`, `prerender`, `onCoopServerMessage`) | OK | vanilla `client/OptionScreens/CoopOptionsScreen.lua` 194–240, 249–257, 494–552, 638–707 |
| `Events.OnCoopServerMessage.Add(CoopOptionsScreen.onCoopServerMessage)` happens in `create()` (line 240), after our file replaced the field | OK | Lua load order: game files first, then mods (decomp `LuaManager.LoadDirBase` 1142–1218) |
| `getServerSettingsManager():getSettingsCount/getSettingsByIndex`, `settings:getServerOptions():getOptionByName("WorkshopItems"):getValue()` | OK | CoopOptionsScreen.lua 325–329, ServerSettingsScreen.lua 1423 |
| `querySteamWorkshopItemDetails(list, fn, arg)` → `fn(arg, "Completed", ArrayList)`, `getIDString/getTitle` | OK | ServerSettingsScreen.lua 1450, 1503–1510 (308 ids in one query work: server log numResult=308) |
| `getFileWriter(name, true, false)` | OK | ISLayoutManager.lua 172 |
| `CoopMaster.launchServer` builds `-Djava.class.path=` from the property, passes `-cachedir=` | OK | decomp `/tmp/pzsrc/zombie/network/CoopMaster.java` 95–137 |
| `SteamWorkshop.DownloadItem(id, prio, cb)` adds the callback once (`contains` check) | OK | decomp `zombie/core/znet/SteamWorkshop.java` 195–199 |
| Only `GameServer` references `GameServerWorkshopItems` (no inner-class mixing) | OK | grep decomp4 |
| ZB `@Patch`/`@Patch.OnEnter/OnExit`, `@LuaMethod(global=true)` | OK | ZB `doc/ModdingGuide.md` 151–227 |
| Local layout `42/` + empty `common/` | OK | SmoothRide, ZombieSyncMP use the same and load (default.txt, console.txt) |
Residual unknowns for the test: whether Steam starts normal-priority requests by itself and overlaps the 3.7 s setup.

### Status (2026-10-29)
- `tools/build.sh` green: javac clean, SimTest ALL OK (10 scenarios), SelfTest OK, Lua sim 15/15. Jar sha256
  f884bfb8…; installed to `C:\Users\druid\Zomboid\mods\HostUnstuck` (== dist). Version strings stay 2.0.0 (git tag
  v2.0.0-rc2), so a passing test can be released as 2.0.0 without a rebuild.
- In-game checks below still apply; additionally in `coop-console.txt`: `push N` lines (Steam left requests waiting),
  `one item at a time from now on` (normal priority does not work here), and the final
  `all N items ready in S s, K re-requests, P pushes, D downloads, parallel xX at the end`.

## 2.0.0 (2026-10-28 02:30): Java part written, offline tests green, NOT run in the game

User (2026-10-28 00:30): "сделай сразу вторую версию, а потом прогоним тест как я буду готов"; GitHub may hold
tests/experiments (nothing sensitive), Steam only verified mods; releases are also to be duplicated on Boosty.

### Findings
- ZombieBuddy (ZB) 2.3.4 = the Java mod loader on this machine: `G:\...\ProjectZomboid\ZombieBuddy.jar` +
  `zbNative.dll`, `-agentlib:zbNative` in `ProjectZomboid64.json`. Mods declare `javaJarFile=media/java/X.jar`,
  `javaPkgName=`, `require=\ZombieBuddy`, `ZBVersionMin=`; ZB appends the jar to the system class loader at
  `ZomboidFileSystem.loadMods` time, applies `@Patch` advice to classes of that package, runs `Main.main`,
  exposes `@LuaMethod(global=true)` statics. Docs: Workshop 3619862853 `doc/*.md`. Decompiled sources in
  `/tmp/zbsrc` (cfr at `workspace/pz-sloprevo/cfr.jar`). A new/changed jar triggers ZB's approval dialog once.
- ZB does **not** reach the co-op server child: `CoopMaster.launchServer` builds the command itself
  (`java -Xms -Xmx -Djava.library.path -Djava.class.path=<client's property> -Duser.dir ... zombie.network.GameServer -coop ...`),
  no agent flags, environment inherited. (`ProjectZomboid64.bat` sets `_JAVA_OPTIONS=-agentlib:zbNative`, but the
  user uses the normal launch: no `server` dirs in `~/.zombie_buddy/PZOptPreloader/<pid>/`.)
- Chicken-and-egg: Workshop items are installed (`GameServer.main` line ~912) before the server loads mods, so a
  server-side ZB mod cannot patch the loop. Solution: **class-path shadowing** — the client patch sets
  `System.setProperty("java.class.path", ourJar + ";" + old)` around `launchServer`/`softreset`; the child JVM honors
  `-Djava.class.path=` (the game's own launcher relies on it) and loads our `zombie.network.GameServerWorkshopItems`
  first (projectzomboid.jar is not sealed). Verified by `SelfTest` with the game's jre64 (25.0.1).
- Vanilla loop (`GameServerWorkshopItems.Install`): one UGC details query, then `for` over items with `break` at
  the first non-Ready one (strictly sequential), `DownloadItem(id, highPriority=true)`, 100 ms polls, no timeout, no
  retry; Fail → status = install folder path, `installTries++`, ≥3 → return false (process exits), else delete
  folder + CheckItemState. `GetItemInstallFolder` can return null (vanilla would NPE in `new File(null)`).
  Steam docs: `bHighPriority=true` pauses other downloads → the parallel loop uses `false` and only re-requests
  with `true`.
- `CoopSlave.status(x)` is null-safe, `CoopSlave.instance.sendMessage(tag, cookie, payload)` is not; `Translator.getText`
  on the server translates to the host's language (hence the garbling). Unknown tags are ignored by the vanilla
  screen (`CoopOptionsScreen.onCoopServerMessage` checks `tag == ...`); `CoopMaster.update` prints every message as
  `SERVER: ...` to the client console.
- `getFileWriter(name, true, false)` writes to `<Zomboid>/Lua/<name>`; the server's `ZomboidFileSystem.instance.getCacheDir()`
  is set from `-cachedir=` before the Workshop step, so `<cachedir>/Lua/HostUnstuck_server.ini` is shared.

### Design
- `src/com/druid/hostunstuck/server/Downloader.java` (game-free): `Backend` + `Reporter` interfaces, states
  CHECK/PENDING/READY/FAIL, up to `parallel` downloads in flight (list order), byte-progress polling, global stall
  timer (`stallSeconds` without progress from any download → re-request the oldest pending item, `maxKicks` per item,
  then Fail → vanilla reinstall path, 3 tries → return false), vanilla status lines + `hostunstuck@v=2;done=;total=;active=;bytes=;kicks=;elapsed=;ids=id:b/t,...` every `infoMs`.
- `src/zombie/network/GameServerWorkshopItems.java`: same public API as the game's; details query copied; runs the
  Downloader with `SteamBackend` (one `ISteamWorkshopCallback` for all items, routed by id); if the loop throws →
  one more run with `parallel=1`; fills `GameServer.workshopInstallFolders/TimeStamps` like vanilla.
- `src/com/druid/hostunstuck/{Inject,Main,Patches,LuaApi,SelfTest}.java`: client; `Inject.init` hashes the game's
  class (`vanilla.sha256` resource, written by build.sh) → gate; `Patches` = ZB advice on `CoopMaster.launchServer`
  and `softreset` (depth counter: both overloads get the advice); Lua globals `HostUnstuck_java()`,
  `HostUnstuck_javaActive()`, `HostUnstuck_javaEnable(bool)`.
- Lua: `HU_A_Core.lua` parses the `hostunstuck` tag (`HU.parseJavaInfo`), bytes moving = progress, stall allowance
  = biggest in-flight item, crash guard (2 server deaths with the Java loop active → `HostUnstuck_javaEnable(false)`,
  `HU.javaDisabled`); `HU_B_Screen.lua` writes `HostUnstuck_server.ini` before each launch
  (`parallel=`, `stallSeconds=`, `maxKicks=`; `parallel=0` when off/disabled), richer second line;
  `HU_C_Options.lua` adds the parallel tick box + "downloads at the same time" slider.

### Files
| file | status |
|---|---|
| `mod.info` | done — 2.0.0, `require=\ZombieBuddy`, `javaJarFile`, `javaPkgName`, `ZBVersionMin=2.3.4` |
| `media/lua/client/HostUnstuck/HU_A_Core.lua` | done — 2.0 state machine (hostunstuck tag, crash guard) |
| `media/lua/client/HostUnstuck/HU_B_Screen.lua` | done — hooks incl. writeSettings/javaEnable, second line |
| `media/lua/client/HostUnstuck/HU_C_Options.lua` | done — 6 options |
| `media/lua/shared/Translate/{EN,RU}/UI.json` | done |
| `src/com/druid/hostunstuck/server/{Downloader,Settings,SteamBackend}.java` | done |
| `src/zombie/network/GameServerWorkshopItems.java` | done — shadow of the 42.21 class (sha256 4d2ababb…) |
| `src/com/druid/hostunstuck/{Inject,Main,Patches,LuaApi,SelfTest}.java` | done |
| `test/com/druid/hostunstuck/SimTest.java` | done — 9 scenarios, ALL OK (parallel 435 s vs sequential 1740 s for 300×1 MB) |
| `test/sim_test.py` | done — 12 scenarios pass (loads HU_C too, PZAPI stub) |
| `tools/build.sh` | done — jar, dist/HostUnstuck (42/ layout), SimTest, SelfTest, Lua sim |
| README.md / README_RU.txt / LICENSE / docs/banner.png | done (2.0.0 experimental) |
| GitHub repo druidqwerty/HostUnstuck | done — commit 2b8b810, pre-release v2.0.0-rc1 (zip sha256 2e68d3fe…, verified anonymously) |
| Steam staging `~/Zomboid/Workshop/HostUnstuck/` (workshop.txt EN 4592 B, workshop_russian.txt 6503 B of 7999, preview.png 512×512, Contents = dist) | drafted 02:10, "Live test" sections say TODO; **not uploaded** (user rule: Steam only after the in-game test) |
| `docs/preview.png` (cover, source `workspace-modder/hostunstuck-preview/preview.html`, Chrome headless 2x + PIL) | done, README shows it |

### Status (2026-10-28 02:30)
- `tools/build.sh` green: javac clean, SimTest ALL OK, SelfTest OK (class shadowing + hash gate with the game's
  JVM), Lua sim 12/12. Jar sha256 see `build/`.
- Installed `dist/HostUnstuck` (42/ layout) to `C:\Users\druid\Zomboid\mods\HostUnstuck`; the old flat 1.0.0 copy is
  in `workspace-modder/scratch/hostunstuck-flat-1.0.0/`. Not enabled in the game yet.
- Next: user enables Host Unstuck in the main-menu Mods list (ZombieBuddy already enabled), restarts the game,
  approves the jar in ZB's dialog, hosts the big modpack server. Then release (GitHub release 2.0.0 + Steam EN/RU
  pages with the "My mods" list, add Host Unstuck to the SmoothRide/ZombieSync lists, Boosty post by the user).

### In-game checks
1. `console.txt`: `[HostUnstuck] java: ready 2.0.0 (jar ...)`, `[HostUnstuck] loaded 2.0.0, Java part: ready 2.0.0`,
   on Start: `[HostUnstuck] java: launch 1: server class path = ...HostUnstuck.jar;...projectzomboid.jar (parallel=4 ...)`,
   then `SERVER: hostunstuck@v=2;...` lines once per second.
2. `coop-console.txt`: `Workshop: [HostUnstuck] 2.0.0 replaces the download loop: parallel=4 ...`, `state ...`,
   `downloaded <id> in <ms>`, `all N items ready in S s, K re-requests`. Compare S with the vanilla 25+ min.
3. Host screen: "Загружено X из 308" + second line "параллельно x4, качается 4: ...  |  скачано N МБ  |  без прогресса: a / b с".
4. Options page renders (6 options); the 4K layout of the second line.
5. If `java: the game's download code changed...` appears: the game updated; rebuild (`tools/build.sh`) after reading
   the new `GameServerWorkshopItems` decompile.

## Findings (2026-10-28, game 42.21.0) — 1.0

- Host flow: `CoopOptionsScreen.lua` → `CoopServer:launch()` → `zombie.network.CoopMaster.launchServer` starts
  `java zombie.network.GameServer -coop ...`. Server stdout lines `tag[cookie]@payload` become the Lua event
  `OnCoopServerMessage(tag, cookie, payload)`. Stop = `CoopServer:abort()` = `Process.destroy()`, then the reader
  thread posts `process-status@terminated`.
- Server side `zombie.network.GameServerWorkshopItems.Install`: one UGC query for all ids, then a loop that advances
  only the first non-Ready item (strictly sequential), `DownloadItem(id, highPriority=true)`, polls every 100 ms,
  **no timeout, no retry**. Per finished item it sends `status@` + `Translator.getText("UI_ServerStatus_Downloaded_Workshop_Items_Progress", n, total)`
  = "Downloaded n of total" (RU: "Загружено n из total", garbled to "?????????" by the child's stdout charset).
  Failures: `status@<install folder path>` then "Reinstalling n of total", 3 tries, then the process exits.
  Before the loop: `status@WorkshopItemsCount N` (RU "Количество элементов мастерской N"), then
  `UI_ServerStatus_Downloading_Workshop_Items`, `UI_ServerStatus_Requesting_Workshop_Item_Details`.
- Downloads go to `<game>\steamapps\workshop\content\108600\<id>` (game-server UGC), separate from the client's
  Steam folder, so the first host run downloads everything again. Even tiny items take 5–8 s each (Steam latency),
  12 MB took 24 s, 600 MB 17 s. 308 items ≈ 25+ min on first run.
- Hung launch (logs.zip coop-console/log_1.txt, 23:34): item 3818327441 went `CheckItemState -> DownloadPending`,
  3 polls, then the log stopped completely for 41 s until the user restarted. The restarted process re-checked
  134 installed items in ~1 s and downloaded the same item in 5.6 s.
- Vanilla bar is broken in RU (template word comparison fails) and odd in EN (workshop = 25% of the bar).
- Lua cannot read `coop-console.txt` (`getFileReader` is rooted at Zomboid/Lua and rejects `..`), so stall detection
  is counter-based: no change of "Downloaded X of Y" for `stallSeconds` (+ size/1.5 MB/s for the current item,
  ×2 per restart on the same counter value). The current item = `items[done+1]` from the server's `WorkshopItems`
  option (list order = processing order); titles/sizes via `querySteamWorkshopItemDetails` (client Steam).
- Lua API used (all verified in vanilla 42.21 Lua or decompiled jar): `CoopServer:abort/launch`, `Events.OnCoopServerMessage`,
  `getTimestampMs`, `getServerSettingsManager():getSettingsCount/getSettingsByIndex`, `settings:getServerOptions():getOptionByName("WorkshopItems"):getValue()`,
  `ArrayList.new`, `querySteamWorkshopItemDetails(list, fn, arg)` → `fn(arg, "Completed", ArrayList<SteamUGCDetails>)`,
  `SteamUGCDetails:getIDString/getTitle/getFileSize`, `ISButton.onclick`, `PZAPI.ModOptions`, `getFileWriter`.

## Gotchas

- Mod must be enabled in the **main-menu** mod list (it runs in the menu); server mod list irrelevant.
- `onStartButtonDown` sets `uiStatus="launching"` only on success; the wrapper keys off that.
- Buttons capture the click function by value at creation; when the screen already exists, patch `onclick`.
- Vanilla "process-status terminated" handler re-enables Start/Back; the mod disables them again while a relaunch is pending.
- The mod folder now uses the `42/` + `common/` layout (ZB resolves `javaJarFile` relative to the dir with mod.info);
  never leave the old flat `mod.info` next to it (duplicate mod id).
- After a game update rebuild with `tools/build.sh`: the hash gate turns the Java part off until the shadow class is
  re-checked against the new decompile; the Lua watchdog keeps working meanwhile.
- `HU.fmtInt` returns a string: never do arithmetic on it (Kahlua coercion not relied on).
