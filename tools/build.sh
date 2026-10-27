#!/usr/bin/env bash
# Builds HostUnstuck.jar, assembles dist/HostUnstuck (mod folder, 42/ layout) and runs the offline tests:
#   - SimTest: the server download loop against a fake Steam (no game classes)
#   - SelfTest: the game's own JVM started like the Host screen starts the server, checks that our class shadows the game's
#   - test/sim_test.py: the Lua watchdog in lupa
# Usage: tools/build.sh [--no-tests]      MODVER=2.0.0 by default
set -euo pipefail
cd "$(dirname "$0")/.."

JDK="C:/Users/druid/.openclaw/workspace/_ext/jdk/jdk-25.0.4.1+1/bin"
GAME="G:/Steam/steamapps/common/ProjectZomboid"
CP="$GAME/projectzomboid.jar;$GAME/ZombieBuddy.jar"
MODVER="${MODVER:-2.0.0}"

rm -rf build/classes build/sim
mkdir -p build/classes build/sim

# SHA-256 of the game's class we replace; the client refuses to inject when the installed game has another one
unzip -p "$GAME/projectzomboid.jar" zombie/network/GameServerWorkshopItems.class | sha256sum | cut -d' ' -f1 > build/vanilla.sha256
echo "game GameServerWorkshopItems.class sha256: $(cat build/vanilla.sha256)"

"$JDK/javac" --release 25 -encoding UTF-8 -Xlint:all,-serial,-processing -cp "$CP" -d build/classes $(find src -name '*.java')
cp build/vanilla.sha256 build/classes/com/druid/hostunstuck/vanilla.sha256
printf 'Implementation-Title: HostUnstuck\nImplementation-Version: %s\n' "$MODVER" > build/manifest.txt
"$JDK/jar" --create --file build/HostUnstuck.jar --manifest build/manifest.txt -C build/classes .

rm -rf dist/HostUnstuck
mkdir -p dist/HostUnstuck/common dist/HostUnstuck/42/media/java dist/HostUnstuck/42/src
cp build/HostUnstuck.jar dist/HostUnstuck/42/media/java/HostUnstuck.jar
cp -r media/lua dist/HostUnstuck/42/media/lua
cp mod.info dist/HostUnstuck/42/mod.info
sed -i "s/^modversion=.*/modversion=$MODVER/" dist/HostUnstuck/42/mod.info
[ -f poster.png ] && cp poster.png dist/HostUnstuck/42/poster.png
cp -r src/com src/zombie dist/HostUnstuck/42/src/
sha256sum build/HostUnstuck.jar

if [ "${1:-}" = "--no-tests" ]; then exit 0; fi

echo "== SimTest (server loop, fake Steam)"
"$JDK/javac" --release 25 -encoding UTF-8 -d build/sim \
  src/com/druid/hostunstuck/server/Downloader.java src/com/druid/hostunstuck/server/Settings.java \
  test/com/druid/hostunstuck/SimTest.java
"$JDK/java" -cp build/sim com.druid.hostunstuck.SimTest

echo "== SelfTest (game JVM, server-style class path)"
JAR_WIN="$(cygpath -w "$PWD/dist/HostUnstuck/42/media/java/HostUnstuck.jar")"
GAME_WIN="$(cygpath -w "$GAME")"
"$GAME/jre64/bin/java.exe" "-Djava.class.path=$JAR_WIN;$GAME_WIN\\projectzomboid.jar" com.druid.hostunstuck.SelfTest

echo "== Lua simulation"
python -I -X utf8 test/sim_test.py
