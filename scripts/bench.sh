#!/bin/bash
# Repeats the real run: scripts/bench.sh <mc-version> <repeats>   (alternates vanilla and mod; evidence in out/real-run/)
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$1; N=${2:-3}
for i in $(seq 1 "$N"); do
  for MODE in vanilla mod; do
    scripts/real-run.sh "$VER" "$MODE" > /dev/null
    rm -rf "out/real-run/$VER-$MODE-r$i"; mv "out/real-run/$VER-$MODE" "out/real-run/$VER-$MODE-r$i"
    echo "done $VER $MODE run $i"
  done
done
python3 scripts/aggregate.py out/real-run "$VER"
