package com.zovo.asynclocator.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings read from config/async-locator.json. Unknown keys are ignored, missing keys get defaults,
 * bad values are clamped and reported. The file is rewritten with every known key so users can see them.
 */
public final class Config {
	public static final int MAX_THREADS = 8;

	/** Number of background threads that search for structures. */
	public int threads = 1;
	/** /locate structure runs in the background. */
	public boolean locateCommand = true;
	/** Eyes of ender look for the stronghold in the background. */
	public boolean eyeOfEnder = true;
	/** Dolphins look for treasure in the background. */
	public boolean dolphinTreasure = true;
	/** Treasure and explorer maps in chest loot are filled in the background. */
	public boolean chestExplorationMaps = true;
	/** Explorer maps sold by cartographers (and other traders) are filled in the background. */
	public boolean merchantExplorationMaps = true;
	/** If a sold map finds nothing, remove the trade. When false, the trade is marked sold out instead. */
	public boolean removeTradeWhenNothingFound = false;
	/** Log one line per search with its duration. */
	public boolean logSearches = true;

	public transient List<String> warnings = new ArrayList<>();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static Config defaults() {
		return new Config();
	}

	/** Reads the file (creating it with defaults when missing) and writes back a normalised copy. */
	public static Config load(Path file) {
		Config cfg = new Config();
		if (Files.isRegularFile(file)) {
			try {
				String text = Files.readString(file, StandardCharsets.UTF_8);
				cfg = parse(text);
			} catch (IOException e) {
				cfg.warnings.add("could not read " + file + ": " + e.getMessage() + " (using defaults)");
			}
		}
		try {
			save(cfg, file);
		} catch (IOException e) {
			cfg.warnings.add("could not write " + file + ": " + e.getMessage());
		}
		return cfg;
	}

	public static Config parse(String json) {
		Config cfg = new Config();
		JsonObject obj;
		try {
			JsonElement el = JsonParser.parseString(json);
			if (!el.isJsonObject()) {
				cfg.warnings.add("config is not a JSON object (using defaults)");
				return cfg;
			}
			obj = el.getAsJsonObject();
		} catch (RuntimeException e) {
			cfg.warnings.add("config is not valid JSON: " + e.getMessage() + " (using defaults)");
			return cfg;
		}
		cfg.threads = readInt(obj, "threads", cfg.threads, cfg.warnings);
		cfg.locateCommand = readBool(obj, "locateCommand", cfg.locateCommand, cfg.warnings);
		cfg.eyeOfEnder = readBool(obj, "eyeOfEnder", cfg.eyeOfEnder, cfg.warnings);
		cfg.dolphinTreasure = readBool(obj, "dolphinTreasure", cfg.dolphinTreasure, cfg.warnings);
		cfg.chestExplorationMaps = readBool(obj, "chestExplorationMaps", cfg.chestExplorationMaps, cfg.warnings);
		cfg.merchantExplorationMaps = readBool(obj, "merchantExplorationMaps", cfg.merchantExplorationMaps, cfg.warnings);
		cfg.removeTradeWhenNothingFound = readBool(obj, "removeTradeWhenNothingFound", cfg.removeTradeWhenNothingFound, cfg.warnings);
		cfg.logSearches = readBool(obj, "logSearches", cfg.logSearches, cfg.warnings);
		if (cfg.threads < 1 || cfg.threads > MAX_THREADS) {
			int clamped = Math.max(1, Math.min(MAX_THREADS, cfg.threads));
			cfg.warnings.add("threads=" + cfg.threads + " is out of range 1.." + MAX_THREADS + ", using " + clamped);
			cfg.threads = clamped;
		}
		return cfg;
	}

	public String toJson() {
		return GSON.toJson(this) + "\n";
	}

	static void save(Config cfg, Path file) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) Files.createDirectories(dir);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, cfg.toJson(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static int readInt(JsonObject obj, String key, int def, List<String> warnings) {
		if (!obj.has(key)) return def;
		JsonElement el = obj.get(key);
		try {
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				double d = el.getAsDouble();
				if (d == Math.rint(d)) return (int) d;
			}
		} catch (RuntimeException ignored) {
			// fall through
		}
		warnings.add(key + " must be a whole number, using " + def);
		return def;
	}

	private static boolean readBool(JsonObject obj, String key, boolean def, List<String> warnings) {
		if (!obj.has(key)) return def;
		JsonElement el = obj.get(key);
		if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
		warnings.add(key + " must be true or false, using " + def);
		return def;
	}
}
