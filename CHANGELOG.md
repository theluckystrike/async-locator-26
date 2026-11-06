# Changelog

## 1.0.0

First release, for Fabric on Minecraft 26.3 and 1.21.11.

- `/locate structure` searches on a background thread and answers when the search ends.
- Treasure and explorer maps from chest loot are handed out at once and filled when the search ends.
- Maps sold by cartographers and wandering traders work the same way. If nothing is found, the trade is marked sold out (or removed, if you set that in the config).
- Dolphins fed with fish look for treasure in the background, and eyes of ender look for the stronghold in the background and fly when the answer is in.

Safety and settings.

- A map whose search is still running cannot be taken from a chest or a trade slot. Copies of it (dropped items, the trade result slot) are finished as soon as they are touched.
- A map left pending by a server stop runs its search again when it is next touched.
- Config file `config/async-locator.json` with a thread count and a switch for each feature.
