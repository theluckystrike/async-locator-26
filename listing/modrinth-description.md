# Async Locator 26

Structure searches without the server freeze, for Fabric on Minecraft 26.x and 1.21.11.

In vanilla, `/locate`, treasure maps, cartographer maps, dolphins and eyes of ender all search for structures on the server thread. While the search runs, the whole server waits. This mod moves those searches to a background thread. The server keeps ticking, and the answer arrives a moment later.

The idea comes from [Async Locator](https://modrinth.com/mod/async-locator) by bright_spark, which stopped at 1.20.2. Async Locator 26 is a new implementation for the 26.x code base, back-ported to 1.21.11. Thanks to bright_spark for the original design.

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

TICK_TABLE

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
- If the server stops during a map search, that one map stays empty.

Free and open source under the MIT license. If it helps your server, you can support the work on [GitHub Sponsors](https://github.com/sponsors/theluckystrike).
