package com.zovo.asynclocator.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
	@Test
	void defaultsAreAllOnWithOneThread() {
		Config c = Config.defaults();
		assertEquals(1, c.threads);
		assertTrue(c.locateCommand && c.eyeOfEnder && c.dolphinTreasure && c.chestExplorationMaps && c.merchantExplorationMaps);
		assertFalse(c.removeTradeWhenNothingFound);
	}

	@Test
	void parsesKnownKeysAndIgnoresUnknown() {
		Config c = Config.parse("{\"threads\": 3, \"eyeOfEnder\": false, \"somethingElse\": 5}");
		assertEquals(3, c.threads);
		assertFalse(c.eyeOfEnder);
		assertTrue(c.locateCommand);
		assertTrue(c.warnings.isEmpty(), c.warnings.toString());
	}

	@Test
	void clampsThreads() {
		assertEquals(Config.MAX_THREADS, Config.parse("{\"threads\": 64}").threads);
		Config low = Config.parse("{\"threads\": 0}");
		assertEquals(1, low.threads);
		assertEquals(1, low.warnings.size());
	}

	@Test
	void wrongTypesKeepDefaultsWithWarning() {
		Config c = Config.parse("{\"threads\": \"four\", \"dolphinTreasure\": \"no\", \"logSearches\": 1.5}");
		assertEquals(1, c.threads);
		assertTrue(c.dolphinTreasure);
		assertTrue(c.logSearches);
		assertEquals(3, c.warnings.size(), c.warnings.toString());
	}

	@Test
	void fractionalThreadsRejected() {
		Config c = Config.parse("{\"threads\": 2.5}");
		assertEquals(1, c.threads);
		assertEquals(1, c.warnings.size());
	}

	@Test
	void brokenJsonFallsBackToDefaults() {
		Config c = Config.parse("{ threads: ");
		assertEquals(1, c.threads);
		assertEquals(1, c.warnings.size());
		Config arr = Config.parse("[1,2]");
		assertEquals(1, arr.warnings.size());
	}

	@Test
	void loadCreatesFileAndRoundTrips(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("sub/async-locator.json");
		Config first = Config.load(f);
		assertTrue(Files.exists(f));
		assertTrue(first.warnings.isEmpty());
		String text = Files.readString(f);
		assertTrue(text.contains("\"threads\": 1"));
		assertFalse(text.contains("warnings"), "transient field must not be written");
		Files.writeString(f, text.replace("\"threads\": 1", "\"threads\": 2").replace("\"locateCommand\": true", "\"locateCommand\": false"));
		Config second = Config.load(f);
		assertEquals(2, second.threads);
		assertFalse(second.locateCommand);
		assertEquals(second.toJson(), Files.readString(f));
	}

	@Test
	void loadNormalisesBadFile(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("async-locator.json");
		Files.writeString(f, "{\"threads\": 99}");
		Config c = Config.load(f);
		assertEquals(Config.MAX_THREADS, c.threads);
		assertTrue(Files.readString(f).contains("\"threads\": " + Config.MAX_THREADS));
		assertTrue(Files.readString(f).contains("\"merchantExplorationMaps\": true"));
	}
}
