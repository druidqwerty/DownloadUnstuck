#!/usr/bin/env bash
# Builds HostUnstuck.jar, assembles dist/HostUnstuck (mod folder, 42/ layout) and runs the offline tests:
#   - SimTest: the server download loop against a fake Steam (no game classes)
#   - SelfTest: the game's own JVM started with -javaagent:HostUnstuck.jar, verifies signed package loading + shadows + hash gates
#   - ZombieBuddyTest: the game's JVM started with ZombieBuddy's agent, verifies CoopMaster @Patch + -javaagent launch command
#   - test/sim_test.py: the Lua watchdog in lupa
# Usage: tools/build.sh [--no-tests]      MODVER=2.1.2 by default
set -euo pipefail
cd "$(dirname "$0")/.."

JDK="C:/Users/druid/.openclaw/workspace/_ext/jdk/jdk-25.0.4.1+1/bin"
GAME="G:/Steam/steamapps/common/ProjectZomboid"
CP="$GAME/projectzomboid.jar;$GAME/ZombieBuddy.jar"
MODVER="${MODVER:-2.1.2}"

rm -rf build/classes build/sim build/zbtest
mkdir -p build/classes build/sim build/zbtest

# SHA-256 of every game class we transform or patch; the client and agent refuse to activate when the game differs
unzip -p "$GAME/projectzomboid.jar" zombie/network/GameServerWorkshopItems.class | sha256sum | cut -d' ' -f1 > build/vanilla.sha256
unzip -p "$GAME/projectzomboid.jar" zombie/savefile/PlayerDB.class | sha256sum | cut -d' ' -f1 > build/playerdb.sha256
unzip -p "$GAME/projectzomboid.jar" 'zombie/savefile/PlayerDB$PlayerData.class' | sha256sum | cut -d' ' -f1 > build/playerdb_data.sha256
unzip -p "$GAME/projectzomboid.jar" zombie/savefile/ServerPlayerDB.class | sha256sum | cut -d' ' -f1 > build/serverplayerdb.sha256
unzip -p "$GAME/projectzomboid.jar" 'zombie/savefile/ServerPlayerDB$NetworkCharacterData.class' | sha256sum | cut -d' ' -f1 > build/serverplayerdb_netdata.sha256
unzip -p "$GAME/projectzomboid.jar" zombie/network/CoopMaster.class | sha256sum | cut -d' ' -f1 > build/coopmaster.sha256
echo "game GameServerWorkshopItems.class sha256: $(cat build/vanilla.sha256)"
echo "game PlayerDB.class sha256: $(cat build/playerdb.sha256)"
echo "game PlayerDB\$PlayerData.class sha256: $(cat build/playerdb_data.sha256)"
echo "game ServerPlayerDB.class sha256: $(cat build/serverplayerdb.sha256)"
echo "game ServerPlayerDB\$NetworkCharacterData.class sha256: $(cat build/serverplayerdb_netdata.sha256)"
echo "game CoopMaster.class sha256: $(cat build/coopmaster.sha256)"

"$JDK/javac" --release 25 -encoding UTF-8 -Xlint:all,-serial,-processing -cp "$CP" -d build/classes $(find src -name '*.java')

# Verify only the 8 expected vanilla classes were compiled under zombie/ and move them into shadows/*.class.bin
EXPECTED_SHADOWS=(
  "zombie/network/GameServerWorkshopItems.class"
  "zombie/network/GameServerWorkshopItems\$ItemQuery.class"
  "zombie/savefile/PlayerDB.class"
  "zombie/savefile/PlayerDB\$IPlayerStore.class"
  "zombie/savefile/PlayerDB\$PlayerData.class"
  "zombie/savefile/PlayerDB\$SQLPlayerStore.class"
  "zombie/savefile/ServerPlayerDB.class"
  "zombie/savefile/ServerPlayerDB\$NetworkCharacterData.class"
)
ACTUAL_SHADOWS=($(cd build/classes && find zombie -name '*.class' | LC_ALL=C sort))
if [ "${#ACTUAL_SHADOWS[@]}" -ne "${#EXPECTED_SHADOWS[@]}" ]; then
  echo "ERROR: expected ${#EXPECTED_SHADOWS[@]} shadow classes, got ${#ACTUAL_SHADOWS[@]}: ${ACTUAL_SHADOWS[*]}" >&2
  exit 1
fi
for f in "${ACTUAL_SHADOWS[@]}"; do
  mkdir -p "build/classes/com/druid/hostunstuck/shadows/$(dirname "$f")"
  mv "build/classes/$f" "build/classes/com/druid/hostunstuck/shadows/${f}.bin"
done
rm -rf build/classes/zombie

cp build/*.sha256 build/classes/com/druid/hostunstuck/
printf 'Implementation-Title: HostUnstuck\nImplementation-Version: %s\nPremain-Class: com.druid.hostunstuck.ServerAgent\n' "$MODVER" > build/manifest.txt
"$JDK/jar" --create --file build/HostUnstuck.jar --manifest build/manifest.txt -C build/classes .

rm -rf dist/HostUnstuck
mkdir -p dist/HostUnstuck/common dist/HostUnstuck/42/media/java dist/HostUnstuck/42/src
cp build/HostUnstuck.jar dist/HostUnstuck/42/media/java/HostUnstuck.jar
cp -r media/lua dist/HostUnstuck/42/media/lua
cp mod.info dist/HostUnstuck/42/mod.info
sed -i "s/^modversion=.*/modversion=$MODVER/" dist/HostUnstuck/42/mod.info
[ -f poster.png ] && cp poster.png dist/HostUnstuck/42/poster.png
[ -f icon.png ] && cp icon.png dist/HostUnstuck/42/icon.png
cp -r src/com src/zombie dist/HostUnstuck/42/src/
sha256sum build/HostUnstuck.jar

if [ "${1:-}" = "--no-tests" ]; then exit 0; fi

echo "== SimTest (server loop, fake Steam)"
"$JDK/javac" --release 25 -encoding UTF-8 -d build/sim \
  src/com/druid/hostunstuck/server/Downloader.java src/com/druid/hostunstuck/server/Settings.java \
  test/com/druid/hostunstuck/SimTest.java
"$JDK/java" -cp build/sim com.druid.hostunstuck.SimTest

echo "== SelfTest (game JVM, -javaagent signed-JAR test)"
JAR_WIN="$(cygpath -w "$PWD/dist/HostUnstuck/42/media/java/HostUnstuck.jar")"
GAME_WIN="$(cygpath -w "$GAME")"
"$GAME/jre64/bin/java.exe" "-javaagent:$JAR_WIN" "-Djava.class.path=$GAME_WIN\\projectzomboid.jar" com.druid.hostunstuck.SelfTest

echo "== ZombieBuddyTest (game JVM, ZombieBuddy agent + CoopMaster @Patch)"
"$JDK/javac" --release 25 -encoding UTF-8 -cp "$CP;build/HostUnstuck.jar" -d build/zbtest test/com/druid/hostunstuck/ZombieBuddyTest.java
"$GAME/jre64/bin/java.exe" \
  "-javaagent:$GAME_WIN\\ZombieBuddy.jar=patches_jar=dist/HostUnstuck/42/media/java/HostUnstuck.jar:com.druid.hostunstuck,verbosity=1" \
  "-Djava.class.path=build\\zbtest;$GAME_WIN\\projectzomboid.jar;$GAME_WIN\\ZombieBuddy.jar" \
  com.druid.hostunstuck.ZombieBuddyTest

echo "== Lua simulation"
python -I -X utf8 test/sim_test.py
