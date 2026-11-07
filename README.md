# Async Locator 26

A Fabric mod that runs structure searches on a background thread, so the server keeps ticking while Minecraft looks for a structure. It covers `/locate structure`, treasure and explorer maps in chest loot, maps sold by cartographers and wandering traders, dolphins that lead you to treasure, and eyes of ender.

Builds for Minecraft 26.3 and 1.21.11. Server side only. Players do not need it, and it works in single player too.

## Why

In vanilla, every one of those searches runs on the server thread. While it runs, nothing else happens. Mobs stop, blocks stop, and players rubber-band. One search is short on a fresh world, but searches add up. On a test server, 16 shipwreck treasure maps rolled at once froze vanilla 26.3 for one tick of about 2 seconds. With this mod no tick went over 50 ms. Modded worlds with many structures and old worlds with explored land make each search longer.

The idea comes from [Async Locator](https://modrinth.com/mod/async-locator) by bright_spark (MIT), which stopped at 1.20.2. It is a new implementation written for the 26.x code base and back-ported to 1.21.11. It is not a fork. Thanks to bright_spark for the original design.

## What happens in game

| Feature | Vanilla | With the mod |
|---|---|---|
| `/locate structure` | The server waits for the search, then prints the result | Prints "Locating ... in the background", then the normal result line when the search ends |
| Chest treasure and explorer maps | The chest fills after the search | The map is in the chest at once with the lore line "Locating structure...". It cannot be taken until it is ready, usually within a second |
| Cartographer and trader maps | The villager's trades appear after the search | The trade appears at once. The map is filled in the trade screen when ready. If nothing is found, the trade is marked sold out |
| Dolphins | The dolphin picks a target after the search | The dolphin waits in place until the answer arrives, then swims |
| Eyes of ender | The eye flies after the search | The eye flies when the answer arrives, from where you are standing at that moment |

The search code itself is vanilla. The mod only moves where it runs, so results are the same as vanilla. The real-run test below checks this. With the same seed, the mod and vanilla found the same structures and drew the same map markers.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/server/) 0.17 or newer on your server.
2. Drop the jar for your Minecraft version into `mods/`. Use `async-locator-26-fabric-1.0.0+26.x.jar` for 26.3 and `async-locator-26-fabric-1.0.0+1.21.11.jar` for 1.21.11.
3. Start the server. The mod writes `config/async-locator.json`.

Fabric API is not needed. Remove the old Async Locator if you have it, since both change the same code.

## Config

`config/async-locator.json` is read when the server starts.

```json
{
  "threads": 1,
  "locateCommand": true,
  "eyeOfEnder": true,
  "dolphinTreasure": true,
  "chestExplorationMaps": true,
  "merchantExplorationMaps": true,
  "removeTradeWhenNothingFound": false,
  "logSearches": true
}
```

- `threads` is the number of background search threads, from 1 to 8. One is enough for most servers.
- Each feature can be turned off. A feature that is off runs the vanilla way.
- `removeTradeWhenNothingFound` removes a map trade whose search found nothing. When false (the default), the trade is marked sold out.
- `logSearches` writes one line per search with its duration.

## Measured on a real server

Longest single server tick while each case ran, on a headless Fabric server with a fixed seed (median of 3 runs per mode on 26.3, 2 runs on 1.21.11). A tick should take 50 ms or less; anything above that is lag players can feel. With the mod no case went above 50 ms.

| Case | 26.3 vanilla | 26.3 with mod | 1.21.11 vanilla | 1.21.11 with mod |
|---|---|---|---|---|
| Idle server | 4.0 ms | 4.7 ms | 6.8 ms | 10.1 ms |
| `/locate structure minecraft:mansion` | 70.9 ms | 8.8 ms | 1,872 ms | 10.5 ms |
| `/locate structure minecraft:ancient_city` | 54.6 ms | 0.7 ms | 75.9 ms | 1.1 ms |
| `/locate structure minecraft:jungle_pyramid` | 5.9 ms | 0.6 ms | 219 ms | 3.5 ms |
| 16 treasure maps at once | 1,368 ms | 15.8 ms | 3,492 ms | 17.1 ms |

Vanilla times vary a lot with how much of the world is already generated, so treat them as an example, not a promise.

Raw numbers, logs and map files are written to `out/real-run/` by `scripts/bench.sh`.

## How it works

Each feature is a small mixin at the point where vanilla calls `findNearestMapStructure`. The mod hands the same call to a background thread and applies the result on the server thread when the call returns.

Two details matter for real lag, and the real-run test found both.

- A search needs chunk data. When another thread asks for a chunk, vanilla runs the request on the server thread and holds that thread until the chunk is ready. Moving only the search (as the original mod did) still leaves those waits on the server thread. Here, a request from a locating thread is only scheduled on the server thread, with a chunk ticket that lives for 10 seconds, and the locating thread does the waiting.
- Most of the cost of a treasure map is not the search but the water outline drawn on the map, which asks for 16,384 biomes. The mod draws that outline on the locating thread from the biome noise (the same source chunks are generated from), with vanilla's drawing rules. The server thread only stores the finished map.

A pending map gets its map id at once and a pending tag in its custom data that holds the search itself. When the search ends, the map data is stored under that id and the item gets its target marker, as vanilla would set it. Copies made while the search ran are finished from a short-lived result cache the next time they tick or are clicked. If the server stops during a search, nothing is applied; the map keeps its pending tag and runs its search again the next time it ticks, is clicked or is shown in a trade screen. A search that fails with an error is treated the same way, never as "nothing found".

## Limits

Behaviour that differs from vanilla.

- `/locate` returns 0 as its command result, because the distance is not known yet when the command ends. Command blocks that read the result with `execute store` see 0. Turn `locateCommand` off if you rely on it.
- A chest map whose search finds nothing is removed. Vanilla 26.x discards such a map too; vanilla 1.21.11 would leave an empty map.
- A map trade whose search finds nothing is marked sold out (or removed, see the config). Vanilla would have rolled a different trade.
- A map whose search had to run again after a restart may point to the next structure of its kind, because the first search already claimed the nearest one.
- The map preview reads biomes from the biome noise. If a command such as `/fillbiome` changed biomes in loaded chunks, the outline shows the generated biomes instead.

Not covered yet.

- Like the original mod, the search adds structure references from the background thread when `skip_existing_chunks` is on. Vanilla does the same steps, just on another thread. The mod puts a lock around vanilla's structure cache for this.
- Eyes of ender are checked by code review only, because the test server has no real player. The dolphin search is checked on a real server through its log line, not by watching a dolphin swim.
- Fabric only for now. A NeoForge build is planned.
- Only 26.3 among the 26.x versions. 26.1 and 26.2 differ in code the mod hooks into, so the jar declares 26.3 and Fabric will not load it on older 26.x versions.

## Build and test

Needs JDK 25.

```sh
./gradlew :common:test           # unit tests for config, executor, pending maps, result cache
./gradlew :mc26:build :mc1211:build
EULA_OK=1 scripts/real-run.sh 26.3 mod        # one headless server run, evidence in out/real-run/
EULA_OK=1 scripts/bench.sh 26.3 3             # vanilla vs mod, 3 runs each
```

`scripts/real-run.sh` downloads the official Fabric server launcher, starts a headless server with a fixed seed, sends `/locate` for three structures, rolls 16 treasure maps at once, has a cartographer generate trades, summons a dolphin with a fish, and reads every tick time over JMX. Then it stops the server and deletes the world. `EULA_OK=1` writes `eula=true` for that local test server. Only set it if you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA).

[zovo-support-start]: #

## Support Zovo

Zovo builds and maintains Async Locator 26, and every feature stays free. If it saves you time, you can support the work on [GitHub Sponsors](https://github.com/sponsors/theluckystrike). Sponsorship is optional, and the tool works the same without it.

Bug reports and ideas go to the issue tracker of this repository.

Zovo is made by [theluckystrike](https://github.com/theluckystrike) on GitHub.

## More tools by Zovo

- [BucketBackup](https://github.com/theluckystrike/bucketbackup). Backs up Paper worlds to S3, Cloudflare R2, Backblaze B2, Wasabi or MinIO on a schedule.

[zovo-support-end]: #

## License

MIT. See `LICENSE`. The original Async Locator is MIT licensed too, and its copyright line is kept in `LICENSE`.
