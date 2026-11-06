package com.zovo.asynclocator.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PendingMapsTest {
	@AfterEach
	void clean() {
		PendingMaps.clear();
	}

	@Test
	void idsAreActiveUntilFinished() {
		String a = PendingMaps.newId();
		String b = PendingMaps.newId();
		assertNotEquals(a, b);
		assertTrue(PendingMaps.isActive(a));
		PendingMaps.finish(a);
		assertFalse(PendingMaps.isActive(a));
		assertTrue(PendingMaps.isActive(b));
		assertEquals(1, PendingMaps.count());
	}

	@Test
	void unknownAndNullIdsAreStale() {
		assertFalse(PendingMaps.isActive(null));
		assertFalse(PendingMaps.isActive("00000000-0000-0000-0000-000000000000"));
		PendingMaps.finish(null); // no exception
	}

	@Test
	void clearDropsEverything() {
		PendingMaps.newId();
		PendingMaps.newId();
		PendingMaps.clear();
		assertEquals(0, PendingMaps.count());
	}
}
