#!/usr/bin/env python3
"""Summarises one real run: tick times while idle and around each /locate, plus the functional checks.
Usage: analyze-run.py <evidence-dir>   (reads events.csv, ticks.csv, console.log; writes summary.json)"""
import csv, json, re, statistics, sys
from pathlib import Path

ev = Path(sys.argv[1])
events = list(csv.DictReader(open(ev / 'events.csv')))
ticks = [(int(r['epoch_ms']), float(r['tick_ms'])) for r in csv.DictReader(open(ev / 'ticks.csv'))]
console = (ev / 'console.log').read_text(errors='replace')


def window(a, b):
    return [ms for t, ms in ticks if a <= t <= b]


def stall(a, b):
    """Longest wall-clock gap with no finished tick, from the poll times that saw new ticks (polled every 100 ms).
    Catches work the main thread does between ticks too, which the tick timer alone does not count."""
    ts = sorted({t for t, _ in ticks if a - 1000 <= t <= b})
    gaps = [y - x for x, y in zip(ts, ts[1:]) if y > a]
    return max(gaps) if gaps else None


def stats(xs):
    if not xs:
        return {'ticks': 0}
    xs2 = sorted(xs)
    return {
        'ticks': len(xs),
        'mean_ms': round(statistics.fmean(xs), 2),
        'p50_ms': round(xs2[len(xs2) // 2], 2),
        'max_ms': round(xs2[-1], 1),
        'over_50ms': sum(1 for x in xs if x > 50),
        'blocked_ms': round(sum(x - 50 for x in xs if x > 50), 1),
    }


ready = next(e for e in events if e['label'] == 'ready')
locs = [e for e in events if e['label'].startswith('locate ') or e['label'].startswith('burst ')]
ra, rb = int(ready['t_send_ms']), int(locs[0]['t_send_ms']) - 200
out = {'dir': ev.name, 'baseline': stats(window(ra, rb)), 'locates': []}
out['baseline']['longest_stall_ms'] = stall(ra + 1000, rb)
for e in locs:
    a, b = int(e['t_send_ms']), int(e['t_done_ms'])
    s = stats(window(a, b + 2000))
    s['longest_stall_ms'] = stall(a, b + 2000)
    s['ticks_expected'] = round((b + 2000 - a) / 50)
    s.update({'structure': e['label'].split(' ', 1)[1] if e['label'].startswith('locate ') else e['label'], 'wall_ms': b - a, 'result': e['result']})
    m = re.search(r'Locating element %s took (\d+) ms' % re.escape(s['structure']), console)
    s['search_ms_logged'] = int(m.group(1)) if m else None
    out['locates'].append(s)
# was the server answering other commands while a search ran? ("time query" is sent 1 s after each /locate)
out['time_query_answers'] = re.findall(r'(\d\d:\d\d:\d\d)\] \[Server thread/INFO\]: (?:System chat: )?The game time is (\d+)', console)
m = re.search(r'Percentiles: P50: ([0-9.]+)ms P95: ([0-9.]+)ms P99: ([0-9.]+)ms', console)
out['tick_query_avg_ms'] = m.group(0) if m else None
burst = re.findall(r'Item entity data: (\{[^\n]*\})|Treasure Map has the following entity data: (\{[^\n]*\})', console)
burst = [a or b for a, b in burst]
out['burst_maps_dumped'] = len(burst)
out['burst_maps_with_target'] = sum(1 for i in burst if 'map_id' in i and 'red_x' in i)
out['burst_distinct_targets'] = len(set(re.findall(r'x: (-?[0-9.]+)d, z: (-?[0-9.]+)d', ' '.join(burst))))
out['pending_markers_in_burst_maps'] = sum(i.count('asynclocator_pending') for i in burst)
offers = re.findall(r'Cartographer has the following entity data: (\{[^\n]*)', console)
out['trade_offer_dumps'] = len(offers)
out['trade_map_ids'] = [len(re.findall(r'map_id', o)) for o in offers]
out['trade_locating_lore'] = [o.count('Locating structure') for o in offers]
out['trade_offer_last'] = re.sub(r'\s+', ' ', offers[-1])[:1500] if offers else None
out['lore_removal_patches'] = console.count('!minecraft:lore')
out['dolphin_search_logged'] = bool(re.search(r'Search for minecraft:dolphin_located', console))
stale = re.findall(r'Map has the following entity data: (\{[^\n]*map_id": 900[^\n]*\})|Map #900[^\n]*has the following entity data: (\{[^\n]*\})', console)
stale = [x or y for x, y in stale]
out['stale_map_dump'] = stale[-1][:400] if stale else None
out['stale_map_finished'] = bool(stale) and 'red_x' in stale[-1] and 'asynclocator_pending' not in stale[-1]
out['stale_map_restart_logged'] = 'Restarting the search for a pending map' in console
out['mixin_errors'] = len(re.findall(r'Mixin apply .* failed|InvalidInjectionException|MixinTransformerError', console))
out['exceptions'] = len(re.findall(r'^\S*Exception|\tat ', console, re.M))
(ev / 'summary.json').write_text(json.dumps(out, indent=2))
print(f"## {ev.name}\n")
b = out['baseline']
print(f"Idle baseline: {b.get('ticks')} ticks, mean {b.get('mean_ms')} ms, max {b.get('max_ms')} ms\n")
print('| Structure | Wall time (s) | Search (ms, log) | Ticks / expected | Max tick (ms) | Longest stall (ms) | Ticks > 50 ms | Result |')
print('|---|---|---|---|---|---|---|---|')
for s in out['locates']:
    print(f"| {s['structure']} | {s['wall_ms']/1000:.1f} | {s['search_ms_logged']} | {s.get('ticks')} / {s['ticks_expected']} | {s.get('max_ms')} | {s['longest_stall_ms']} | {s.get('over_50ms')} | {s['result']} |")
print()
for k in ('time_query_answers', 'tick_query_avg_ms', 'burst_maps_dumped', 'burst_maps_with_target', 'burst_distinct_targets', 'pending_markers_in_burst_maps', 'lore_removal_patches', 'trade_offer_dumps',
          'trade_map_ids', 'trade_locating_lore', 'dolphin_search_logged', 'stale_map_finished', 'stale_map_restart_logged', 'mixin_errors', 'exceptions'):
    print(f'- {k}: {out[k]}')
