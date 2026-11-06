package com.zovo.asynclocator.core;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ids of maps whose search is still running. A map item carries its id in custom data, so copies of the item
 * (for example the result slot of a trade screen) can be recognised. Ids are kept in memory only: after a restart
 * every old id is unknown, and such a map is treated as stale.
 */
public final class PendingMaps {
	public static final String TAG = "asynclocator_pending";
	private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();

	private PendingMaps() {}

	public static String newId() {
		String id = UUID.randomUUID().toString();
		ACTIVE.add(id);
		return id;
	}

	public static boolean isActive(String id) {
		return id != null && ACTIVE.contains(id);
	}

	public static void finish(String id) {
		if (id != null) ACTIVE.remove(id);
	}

	public static int count() {
		return ACTIVE.size();
	}

	public static void clear() {
		ACTIVE.clear();
	}
}
