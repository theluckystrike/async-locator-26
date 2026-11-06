package com.zovo.asynclocator.core;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Results of finished map searches, by pending id. The searched stack is filled at once; copies of it that were
 * made while the search ran (dropped items, trade result slots) are filled from here the next time they are touched.
 * Entries expire after {@code ttlMillis}; the cache never holds more than {@code maxEntries}.
 */
public final class ResultCache<T> {
	private record Entry<T>(T value, long at) {}

	private final Map<String, Entry<T>> map = new ConcurrentHashMap<>();
	private final long ttlMillis;
	private final int maxEntries;
	private final LongSupplier clock;

	public ResultCache(long ttlMillis, int maxEntries, LongSupplier clock) {
		this.ttlMillis = ttlMillis;
		this.maxEntries = maxEntries;
		this.clock = clock;
	}

	public ResultCache(long ttlMillis, int maxEntries) {
		this(ttlMillis, maxEntries, System::currentTimeMillis);
	}

	public void put(String id, T value) {
		if (id == null) return;
		long now = clock.getAsLong();
		prune(now);
		if (map.size() >= maxEntries) {
			// drop the oldest entry
			String oldest = null;
			long oldestAt = Long.MAX_VALUE;
			for (Map.Entry<String, Entry<T>> e : map.entrySet()) {
				if (e.getValue().at() < oldestAt) {
					oldestAt = e.getValue().at();
					oldest = e.getKey();
				}
			}
			if (oldest != null) map.remove(oldest);
		}
		map.put(id, new Entry<>(value, now));
	}

	/** True if a result (possibly a "nothing found" result) is known for this id. */
	public boolean has(String id) {
		Entry<T> e = id == null ? null : map.get(id);
		return e != null && clock.getAsLong() - e.at() <= ttlMillis;
	}

	public T get(String id) {
		Entry<T> e = id == null ? null : map.get(id);
		if (e == null) return null;
		if (clock.getAsLong() - e.at() > ttlMillis) {
			map.remove(id, e);
			return null;
		}
		return e.value();
	}

	public int size() {
		return map.size();
	}

	public void clear() {
		map.clear();
	}

	private void prune(long now) {
		for (Iterator<Entry<T>> it = map.values().iterator(); it.hasNext(); ) {
			if (now - it.next().at() > ttlMillis) it.remove();
		}
	}
}
