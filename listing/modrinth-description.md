# Async Locator 26

Structure searches without the server freeze, for Fabric on Minecraft 26.3 and 1.21.11.

In vanilla, `/locate`, treasure maps, cartographer maps, dolphins and eyes of ender all search for structures on the server thread. While the search runs, the whole server waits. This mod moves those searches to a background thread. The server keeps ticking, and the answer arrives a moment later.

The idea comes from [Async Locator](https://modrinth.com/mod/async-locator) by bright_spark, which stopped at 1.20.2. Async Locator 26 is a new implementation (not a fork) written for the 26.x code base and back-ported to 1.21.11. Thanks to bright_spark for the original design.

## What it covers

| Feature | With the mod |
|---|---|
| `/locate structure` | Prints "Locating ... in the background", then the normal result line |
| Chest treasure and explorer maps | The map is in the chest at once and is filled when ready, usually within a second |
| Cartographer and wandering trader maps | The trade appears at once and the map is filled in the trade screen. If nothing is found, the trade is marked sold out |
| Dolphins | The dolphin waits until the answer arrives, then swims to the treasure |
| Eyes of ender | The eye flies when the answer arrives |

The search code is vanilla, so the results match vanilla. In our test with a fixed seed, the mod and vanilla found the same structures and drew the same map markers.

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

## Install

1. Install Fabric Loader 0.17 or newer.
2. Put the jar for your version in `mods/`. Fabric API is not needed.
3. Start the server. Settings are in `config/async-locator.json`.

Server side only. Players do not need the mod, and it works in single player as well. Remove the old Async Locator first, since both change the same code.

## Config

`threads` sets the number of search threads (1 to 8, default 1). Each feature has its own switch (`locateCommand`, `eyeOfEnder`, `dolphinTreasure`, `chestExplorationMaps`, `merchantExplorationMaps`). A feature that is off runs the vanilla way. `removeTradeWhenNothingFound` removes a map trade that found nothing instead of marking it sold out.

## Good to know

- `/locate` returns 0 as its command result, because the distance is not known when the command ends. Turn `locateCommand` off if a command block reads it.
- A map whose search is still running cannot be taken out of a chest or a trade slot until it is ready.
- If the server stops during a map search, the map runs its search again the next time it is touched. It may then point to the next structure of its kind.
- A map whose search finds nothing is removed, and a trade map that finds nothing is marked sold out.
- Among the 26.x versions only 26.3 is supported, because 26.1 and 26.2 differ in the code the mod hooks into.

Free and open source under the MIT license. If it helps your server, you can support the work on [GitHub Sponsors](https://github.com/sponsors/theluckystrike).
