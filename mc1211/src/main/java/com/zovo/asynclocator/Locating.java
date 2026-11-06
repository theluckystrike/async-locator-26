package com.zovo.asynclocator;

import com.mojang.datafixers.util.Pair;
import com.zovo.asynclocator.core.LocateExecutor;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Thin wrappers that run the vanilla structure search on a locating thread. */
public final class Locating {
	/** Players whose eye of ender search is running; one search per player at a time. */
	public static final java.util.Set<java.util.UUID> EYE_SEARCHES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private Locating() {}

	public static LocateExecutor.Task<BlockPos> nearestMapStructure(
		ServerLevel level, TagKey<Structure> tag, BlockPos from, int radius, boolean skipKnown, Consumer<BlockPos> onMain
	) {
		return LocateExecutor.submit(
			tag.location() + " near " + from.toShortString(),
			() -> level.findNearestMapStructure(tag, from, radius, skipKnown),
			level.getServer(),
			onMain
		);
	}

	public static LocateExecutor.Task<Pair<BlockPos, Holder<Structure>>> nearestFromGenerator(
		ServerLevel level, HolderSet<Structure> set, BlockPos from, int radius, boolean skipKnown,
		Consumer<Pair<BlockPos, Holder<Structure>>> onMain
	) {
		return LocateExecutor.submit(
			describe(set) + " near " + from.toShortString(),
			() -> level.getChunkSource().getGenerator().findNearestMapStructure(level, set, from, radius, skipKnown),
			level.getServer(),
			onMain
		);
	}

	static String describe(HolderSet<Structure> set) {
		return set.unwrapKey().map(k -> "#" + k.location()).orElseGet(() -> {
			StringBuilder sb = new StringBuilder();
			for (Holder<Structure> h : set) {
				if (sb.length() > 0) sb.append(',');
				sb.append(h.getRegisteredName());
				if (sb.length() > 120) break;
			}
			return sb.toString();
		});
	}
}
