#!/usr/bin/env python3
"""Combines repeated runs: aggregate.py <out/real-run> <mc-version>. Prints a markdown table and writes bench-<ver>.json."""
import json, statistics, sys
from pathlib import Path

root, ver = Path(sys.argv[1]), sys.argv[2]
rows = {}
func = {}
for d in sorted(root.glob(f'{ver}-*-r*')):
    mode = d.name[len(ver) + 1:].rsplit('-r', 1)[0]
    s = json.loads((d / 'summary.json').read_text())
    rows.setdefault(('idle baseline', mode), []).append(s['baseline'])
    for l in s['locates']:
        rows.setdefault((l['structure'], mode), []).append(l)
    func.setdefault(mode, []).append({k: s[k] for k in ('burst_maps_with_target', 'burst_distinct_targets', 'pending_markers_in_burst_maps',
                                                          'lore_removal_patches', 'trade_map_ids', 'trade_locating_lore',
                                                          'dolphin_search_logged', 'stale_map_finished', 'mixin_errors', 'exceptions')})
order = []
for k in rows:
    if k[0] not in order:
        order.append(k[0])
print(f'### Minecraft {ver}: tick times over {len(next(iter(rows.values())))} runs per mode (median of runs, worst run in brackets)\n')
print('| Case | Vanilla max tick (ms) | Mod max tick (ms) | Vanilla longest stall (ms) | Mod longest stall (ms) | Vanilla ticks > 50 ms | Mod ticks > 50 ms |')
print('|---|---|---|---|---|---|---|')
out = {}
for case in order:
    cells = []
    for key in ('max_ms', 'longest_stall_ms', 'over_50ms'):
        for mode in ('vanilla', 'mod'):
            xs = [r.get(key, 0) or 0 for r in rows.get((case, mode), [])]
            cells.append(f'{statistics.median(xs):g} [{max(xs):g}]' if xs else '-')
            out.setdefault(case, {}).setdefault(mode, {})[key] = xs
    print(f'| {case} | ' + ' | '.join(cells[i] for i in (0, 1, 2, 3, 4, 5)) + ' |')
print()
for mode, fs in func.items():
    print(f'- {mode}: ' + '; '.join(json.dumps(f) for f in fs))
(root / f'bench-{ver}.json').write_text(json.dumps({'cases': out, 'functional': func}, indent=2))
