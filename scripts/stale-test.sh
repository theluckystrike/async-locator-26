#!/bin/bash
# Stale pending map test: a map item carrying a pending tag from a search the server does not know (as after a
# restart) must run its search again and finish. Usage: EULA_OK=1 scripts/stale-test.sh <mc-version>
set -euo pipefail
cd "$(dirname "$0")/.."
VER=${1:?mc version}; JAVA=${JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}
case "$VER" in 26.*) MODJAR=$(ls mc26/build/libs/async-locator-26-fabric-*+26.x.jar);; *) MODJAR=$(ls mc1211/build/libs/async-locator-26-fabric-*+1.21.11.jar);; esac
SRV="$PWD/run/$VER"; EV="$PWD/out/real-run/$VER-stale"; mkdir -p "$EV" "$SRV/mods"; rm -rf "$EV"/* "$SRV"/world* "$SRV"/mods/*
cp "$MODJAR" "$SRV/mods/"; printf "online-mode=false\nserver-port=25611\npause-when-empty-seconds=-1\nlevel-seed=20261106\n" > "$SRV/server.properties"; [ "${EULA_OK:-0}" = 1 ] && echo "eula=true" > "$SRV/eula.txt"
LOG="$EV/console.log"; FIFO="$SRV/stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
( cd "$SRV" && exec "$JAVA" -Xms1G -Xmx2G -jar "fabric-server-launch-$VER.jar" nogui <"$FIFO" >"$LOG" 2>&1 ) &
SV=$!; exec 3>"$FIFO"
trap 'exec 3>&- 2>/dev/null; kill $SV 2>/dev/null; rm -rf "$SRV"/world* "$FIFO"' EXIT
for i in $(seq 1 3000); do grep -q 'Done (' "$LOG" && break; sleep 0.2; done
printf "%s\n" "forceload add 0 0" >&3; echo "fill 11 199 11 17 203 17 minecraft:glass hollow" >&3; sleep 5
echo 'summon item 14 200 14 {Tags:["stale"],Item:{id:"minecraft:filled_map",count:1,components:{"minecraft:map_id":900,"minecraft:custom_data":{asynclocator_pending:{id:"stale-test",dest:"#minecraft:on_treasure_maps",x:0,y:93,z:0,radius:50,skip:0b,zoom:1b,decoration:"minecraft:red_x"}}}}}' >&3
sleep 8
echo "data get entity @e[type=item,tag=stale,limit=1] Item" >&3
sleep 2; echo stop >&3
for i in $(seq 1 120); do kill -0 $SV 2>/dev/null || break; sleep 0.5; done
du -sk "$SRV"/world* 2>/dev/null > "$EV/deleted-world.txt" || true
rm -rf "$SRV"/world* "$FIFO"
grep -E "DEBUG|Restarting|stale|Map #900|Map has|map near|Exception" "$LOG" | cut -c1-400
