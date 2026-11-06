package com.zovo.asynclocator;

import com.mojang.datafixers.util.Pair;
import com.zovo.asynclocator.core.LocateExecutor;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Thin wrappers that run the vanilla structure search on a locating thread. */
public final class Locating {
	/** Players whose eye of ender search is running; one search per player at a time. */
	public static final Set<UUID> EYE_SEARCHES = ConcurrentHashMap.newKeySet();

	private Locating() {}

	/** Vanilla 26.x searches even when structure generation is off, so this build does too. */
	public static boolean structuresEnabled(ServerLevel level) {
		return true;
	}

	/**
	 * Runs a result on the server thread, and only while the server is running. A server that is stopping would
	 * run a queued task at once on the calling thread; this drops it instead.
	 */
	public static Executor mainThread(ServerLevel level) {
		MinecraftServer server = level.getServer();
		return r -> {
			if (!server.isRunning()) return;
			server.execute(() -> {
				if (server.isSameThread() && server.isRunning()) r.run();
			});
		};
	}

	public static LocateExecutor.Task<BlockPos> nearestMapStructure(
		ServerLevel level, TagKey<Structure> tag, BlockPos from, int radius, boolean skipKnown,
		Consumer<BlockPos> onMain, Runnable onFail
	) {
		return LocateExecutor.submit(
			tag.location() + " near " + from.toShortString(),
			() -> level.findNearestMapStructure(tag, from, radius, skipKnown),
			mainThread(level), onMain, onFail
		);
	}

	public static LocateExecutor.Task<Pair<BlockPos, Holder<Structure>>> nearestFromGenerator(
		ServerLevel level, HolderSet<Structure> set, BlockPos from, int radius, boolean skipKnown,
		Consumer<Pair<BlockPos, Holder<Structure>>> onMain, Runnable onFail
	) {
		return LocateExecutor.submit(
			describe(set) + " near " + from.toShortString(),
			() -> level.getChunkSource().getGenerator().findNearestMapStructure(level, set, from, radius, skipKnown),
			mainThread(level), onMain, onFail
		);
	}

	public static String describe(HolderSet<Structure> set) {
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
