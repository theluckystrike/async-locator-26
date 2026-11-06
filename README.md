# Async Locator 26

A Fabric mod that runs structure searches on a background thread, so the server keeps ticking while Minecraft looks for a structure. It covers `/locate structure`, treasure and explorer maps in chest loot, maps sold by cartographers and wandering traders, dolphins that lead you to treasure, and eyes of ender.

Builds for Minecraft 26.x and 1.21.11. Server side only. Players do not need it, and it works in single player too.

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
2. Drop the jar for your Minecraft version into `mods/`. Use `async-locator-26-fabric-1.0.0+26.x.jar` for 26.1 to 26.3 and `async-locator-26-fabric-1.0.0+1.21.11.jar` for 1.21.11.
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

## How it works

Each feature is a small mixin at the point where vanilla calls `findNearestMapStructure`. The mod hands the same call to a background thread and applies the result on the server thread when the call returns.

Two details matter for real lag, and the real-run test found both.

- A search needs chunk data. When another thread asks for a chunk, vanilla runs the request on the server thread and holds that thread until the chunk is ready. Moving only the search (as the original mod did) still leaves those waits on the server thread. Here, a request from a locating thread is only scheduled on the server thread, with a chunk ticket that lives for 10 seconds, and the locating thread does the waiting.
- Most of the cost of a treasure map is not the search but the water outline drawn on the map, which asks for 16,384 biomes. The mod draws that outline on the locating thread from the biome noise (the same source chunks are generated from), with vanilla's drawing rules. The server thread only stores the finished map.

A pending map gets its map id at once and a pending mark in its custom data. When the search ends, the map data is stored under that id and the item gets its target marker, as vanilla would set it. Copies made while the search ran are finished from a short-lived result cache the next time they tick or are clicked.

## Limits

Behaviour that differs from vanilla.

- `/locate` returns 0 as its command result, because the distance is not known yet when the command ends. Command blocks that read the result with `execute store` see 0. Turn `locateCommand` off if you rely on it.
- If the server stops while a map search runs, that map stays empty. This window is usually under a second.
- On 1.21.11, a chest map whose search finds nothing is removed from the chest, where vanilla would leave an empty map. In 26.x the item goes back to how it was.
- Like the original mod, the search adds structure references from the background thread when `skip_existing_chunks` is on. Vanilla does the same steps, just on another thread.
- The map preview reads biomes from the biome noise. If a command such as `/fillbiome` changed biomes in loaded chunks, the outline shows the generated biomes instead.

Not covered yet.

- Eyes of ender are checked by code review only, because the test server has no real player. The dolphin search is checked on a real server through its log line, not by watching a dolphin swim.
- Fabric only for now. A NeoForge build is planned.

## Build and test

Needs JDK 25.

```sh
./gradlew :common:test           # unit tests for config, executor, pending maps, result cache
./gradlew :mc26:build :mc1211:build
EULA_OK=1 scripts/real-run.sh 26.3 mod        # one headless server run, evidence in out/real-run/
EULA_OK=1 scripts/bench.sh 26.3 3             # vanilla vs mod, 3 runs each
```

`scripts/real-run.sh` downloads the official Fabric server launcher, starts a headless server with a fixed seed, sends `/locate` for three structures, rolls 16 treasure maps at once, has a cartographer generate trades, summons a dolphin with a fish, and reads every tick time over JMX. Then it stops the server and deletes the world. `EULA_OK=1` writes `eula=true` for that local test server. Only set it if you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA).

## Support

The mod is free and MIT licensed. If it saves your server some lag, you can support the work on [GitHub Sponsors](https://github.com/sponsors/theluckystrike).

More from the same author. [BucketBackup](https://modrinth.com/plugin/bucketbackup) backs up Paper worlds to S3, R2 or B2.

## License

MIT. See `LICENSE`. The original Async Locator is MIT licensed too, and its copyright line is kept in `LICENSE`.
