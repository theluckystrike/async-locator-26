package com.zovo.asynclocator;

import com.zovo.asynclocator.core.Config;
import com.zovo.asynclocator.core.LocateExecutor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class AsyncLocatorMod implements ModInitializer {
	public static volatile Config CONFIG = Config.defaults();

	@Override
	public void onInitialize() {
		SearchTicket.init();
		reloadConfig();
	}

	public static void reloadConfig() {
		Config cfg = Config.load(FabricLoader.getInstance().getConfigDir().resolve("async-locator.json"));
		for (String w : cfg.warnings) LocateExecutor.LOG.warn("async-locator.json: {}", w);
		CONFIG = cfg;
		LocateExecutor.LOG.info(
			"Async Locator ready: threads={}, locate={}, eyeOfEnder={}, dolphins={}, chestMaps={}, tradeMaps={}",
			cfg.threads, cfg.locateCommand, cfg.eyeOfEnder, cfg.dolphinTreasure, cfg.chestExplorationMaps,
			cfg.merchantExplorationMaps
		);
	}

	public static Config config() {
		return CONFIG;
	}
}
