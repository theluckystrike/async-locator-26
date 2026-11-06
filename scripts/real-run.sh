#!/bin/bash
# Real-run test on a headless dedicated server started by the official Fabric server launcher.
# Usage: scripts/real-run.sh <mc-version> <vanilla|mod> [structures...]
#   vanilla: Fabric Loader with no mods (the baseline); mod: the same plus this mod's jar.
# Fresh world with a fixed seed each run. It sends /locate structure for each structure, reads every tick time
# over JMX (scripts/TickProbe.java), then checks a chest map, a cartographer trade map and a dolphin.
# At the end it stops the server and deletes the world. Evidence goes to out/real-run/<version>-<mode>/.
# Needs JDK 25. Setting EULA_OK=1 writes eula=true for the local test server (Mojang EULA, test use only).
set -euo pipefail
cd "$(dirname "$0")/.."
VER=${1:?mc version}; MODE=${2:?vanilla or mod}; shift 2
STRUCTS=("$@"); [ ${#STRUCTS[@]} -eq 0 ] && STRUCTS=(minecraft:mansion minecraft:ancient_city minecraft:jungle_pyramid)
JAVA=${JAVA:-/opt/homebrew/opt/openjdk@25/bin/java}
LOADER=${LOADER:-0.20.1}; INSTALLER=${INSTALLER:-1.1.2}
SEED=${SEED:-20261106}; JMX=${JMX:-19781}; PORT=${PORT:-25611}
case "$VER" in 26.*) MODJAR=$(ls mc26/build/libs/async-locator-26-fabric-*+26.x.jar);; *) MODJAR=$(ls mc1211/build/libs/async-locator-26-fabric-*+1.21.11.jar);; esac
SRV="$PWD/run/$VER"; EV="$PWD/out/real-run/$VER-$MODE"
mkdir -p "$SRV/mods" "$EV"; rm -rf "$EV"/*
LAUNCHER="$SRV/fabric-server-launch-$VER.jar"
[ -f "$LAUNCHER" ] || curl -fsSL -A "async-locator-26-test/1.0" -o "$LAUNCHER" \
  "https://meta.fabricmc.net/v2/versions/loader/$VER/$LOADER/$INSTALLER/server/jar"
rm -rf "$SRV"/world* "$SRV/logs" "$SRV/config/async-locator.json" "$SRV"/mods/*
[ "$MODE" = mod ] && cp "$MODJAR" "$SRV/mods/"
[ "${EULA_OK:-0}" = 1 ] && echo "eula=true" > "$SRV/eula.txt"
cat > "$SRV/server.properties" <<P
server-port=$PORT
online-mode=false
level-seed=$SEED
view-distance=4
simulation-distance=4
spawn-protection=0
enable-jmx-monitoring=true
max-tick-time=-1
pause-when-empty-seconds=-1
sync-chunk-writes=false
motd=async locator test
P
LOG="$EV/console.log"; EVENTS="$EV/events.csv"; echo "label,t_send_ms,t_done_ms,result" > "$EVENTS"
FIFO="$SRV/stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
( cd "$SRV" && exec "$JAVA" -Xms1G -Xmx2G \
    -Dcom.sun.management.jmxremote.port=$JMX -Dcom.sun.management.jmxremote.rmi.port=$JMX \
    -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.host=127.0.0.1 -Djava.rmi.server.hostname=127.0.0.1 \
    -jar "$LAUNCHER" nogui <"$FIFO" >"$LOG" 2>&1 ) &
SV=$!
exec 3>"$FIFO"
"$JAVA" scripts/TickProbe.java $JMX "$EV/ticks.csv" >"$EV/probe.log" 2>&1 &
PR=$!
cleanup() { exec 3>&- 2>/dev/null || true; kill $PR 2>/dev/null || true; sleep 1; kill $SV 2>/dev/null || true; pkill -f "fabric-server-launch-$VER.jar" 2>/dev/null || true; }
trap cleanup EXIT
now() { python3 -c 'import time; print(int(time.time()*1000))'; }
send() { echo "$*" >&3; echo "$(now) > $*" >> "$EV/commands.log"; }
# wait_for <regex> <timeout-s> [start-line]: waits until the console has a matching line after start-line
wait_for() {
  local re=$1 t=$2 from=${3:-0} i=0
  while [ $i -lt $((t * 10)) ]; do
    if tail -n +"$((from + 1))" "$LOG" | grep -Eq "$re"; then return 0; fi
    kill -0 $SV 2>/dev/null || { echo "server exited"; return 2; }
    sleep 0.1; i=$((i + 1))
  done
  return 1
}
lines() { wc -l < "$LOG" | tr -d ' '; }
wait_for 'Done \([0-9.]+s\)!' 600 || { echo "server did not start"; tail -40 "$LOG"; exit 1; }
# World setup first, so chunk loading for it does not overlap the measured windows
send "forceload add 0 0 31 15"
send "fill -1 199 -1 10 205 10 minecraft:glass hollow"; send "fill 0 200 0 9 204 9 minecraft:water"
send "fill 19 199 -1 25 203 5 minecraft:glass hollow"; send "setblock 22 200 2 minecraft:cartography_table"
send "fill 11 199 11 17 203 17 minecraft:glass hollow"
sleep 15
T_READY=$(now); echo "ready,$T_READY,$T_READY,ok" >> "$EVENTS"
sleep 20   # idle baseline
for S in "${STRUCTS[@]}"; do
  from=$(lines); t0=$(now)
  send "locate structure $S"
  sleep 1; send "time query gametime"
  if wait_for "Locating element $S took|Could not find a structure" 240 "$from"; then
    t1=$(now); r=$(tail -n +"$((from + 1))" "$LOG" | grep -Eo "The nearest $S is at \[[^]]*\] \([0-9]+ blocks away\)|Could not find[^\r]*" | head -1)
  else t1=$(now); r=timeout; fi
  echo "locate $S,$t0,$t1,\"$r\"" >> "$EVENTS"
  sleep 4
done
send "tick query"; sleep 2
# Burst: 16 buried treasure maps rolled in one go, as a datapack or a busy area of shipwreck chests would.
# Vanilla does all 16 searches inside one tick.
from=$(lines); t0=$(now)
BURST=""; for i in $(seq 1 16); do BURST+="loot spawn 14 200 14 loot minecraft:chests/shipwreck_map"$'\n'; done
printf "%s" "$BURST" >&3; echo "$t0 > 16x loot spawn shipwreck_map" >> "$EV/commands.log"
sleep 1; send "time query gametime"
for i in $(seq 1 2400); do
  n=$(tail -n +"$((from + 1))" "$LOG" | grep -c "Dropped [0-9]* items" || true)
  [ "$n" -ge 16 ] && break; sleep 0.1
done
# in mod mode the maps are filled after the command returns: wait for the 16th search to be logged
if [ "$MODE" = mod ]; then
  for i in $(seq 1 600); do
    n=$(tail -n +"$((from + 1))" "$LOG" | grep -c "Search for #minecraft:on_treasure_maps\|Search for minecraft:buried_treasure" || true)
    [ "$n" -ge 16 ] && break; sleep 0.1
  done
fi
t1=$(now); echo "burst 16 treasure maps,$t0,$t1,ok" >> "$EVENTS"
sleep 3
send "execute as @e[type=item,x=14,y=200,z=14,distance=..3] run data get entity @s Item.components"
# Functional checks: cartographer trade maps (a working villager at a cartography table generates its
# offers when it checks for restocking), chest map pickup guard, dolphin treasure search
send "time set 2000"
send "summon villager 21 200 2 {Tags:[\"carto\"],VillagerData:{profession:\"minecraft:cartographer\",level:3,type:\"minecraft:plains\"},Brain:{memories:{\"minecraft:job_site\":{value:{pos:[I;22,200,2],dimension:\"minecraft:overworld\"}}}}}"
send "summon dolphin 4 202 4 {Tags:[\"dolly\"],GotFish:1b}"
for i in $(seq 1 8); do sleep 5; send "data get entity @e[type=villager,tag=carto,limit=1] Offers"; done
send "data get entity @e[type=dolphin,tag=dolly,limit=1]"
# A map left pending by a search the server no longer knows (as after a restart): it must search again and finish.
send "summon item 14 200 14 {Tags:[\"stale\"],Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":900,\"minecraft:custom_data\":{asynclocator_pending:{id:\"stale-test\",dest:\"#minecraft:on_treasure_maps\",x:0,y:93,z:0,radius:50,skip:0b,zoom:1b,decoration:\"minecraft:red_x\"}}}}}"
sleep 6
send "data get entity @e[type=item,tag=stale,limit=1] Item"
sleep 3
send "stop"
for i in $(seq 1 120); do kill -0 $SV 2>/dev/null || break; sleep 0.5; done
T_END=$(now); echo "stopped,$T_END,$T_END,ok" >> "$EVENTS"
cleanup; trap - EXIT
cp "$SRV/logs/latest.log" "$EV/latest.log" 2>/dev/null || true
[ -f "$SRV/config/async-locator.json" ] && cp "$SRV/config/async-locator.json" "$EV/"
mkdir -p "$EV/maps"
# 1.21.x stores maps as data/map_<id>.dat, 26.x as data/.../maps/<id>.dat
find "$SRV"/world* -name 'map_*.dat' -exec cp {} "$EV/maps/" \; 2>/dev/null || true
find "$SRV"/world* -path '*/maps/*.dat' 2>/dev/null | while read -r f; do cp "$f" "$EV/maps/map_$(basename "$f")"; done
du -sk "$SRV"/world* 2>/dev/null | awk -v ev="$EV" '{print $1"KiB\t"$2}' > "$EV/deleted-world.txt" || true
rm -rf "$SRV"/world* "$FIFO"
python3 scripts/analyze-run.py "$EV" | tee "$EV/summary.md"
