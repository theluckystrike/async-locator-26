package com.zovo.asynclocator.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ResultCacheTest {
	private final AtomicLong now = new AtomicLong(1_000);
	private final ResultCache<String> cache = new ResultCache<>(60_000, 3, now::get);

	@Test
	void storesAndReturnsResults() {
		cache.put("a", "pos-a");
		assertTrue(cache.has("a"));
		assertEquals("pos-a", cache.get("a"));
		assertFalse(cache.has("b"));
		assertNull(cache.get("b"));
		assertNull(cache.get(null));
	}

	@Test
	void nullValueMeansNothingFoundButIsStillKnown() {
		cache.put("miss", null);
		assertTrue(cache.has("miss"));
		assertNull(cache.get("miss"));
	}

	@Test
	void entriesExpire() {
		cache.put("a", "x");
		now.addAndGet(60_001);
		assertFalse(cache.has("a"));
		assertNull(cache.get("a"));
	}

	@Test
	void sizeIsBoundedByDroppingOldest() {
		cache.put("1", "a");
		now.incrementAndGet();
		cache.put("2", "b");
		now.incrementAndGet();
		cache.put("3", "c");
		now.incrementAndGet();
		cache.put("4", "d");
		assertEquals(3, cache.size());
		assertFalse(cache.has("1"));
		assertTrue(cache.has("4"));
	}

	@Test
	void putPrunesExpired() {
		cache.put("old", "x");
		now.addAndGet(120_000);
		cache.put("new", "y");
		assertEquals(1, cache.size());
	}
}
