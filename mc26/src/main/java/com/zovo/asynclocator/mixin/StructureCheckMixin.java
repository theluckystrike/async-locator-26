package com.zovo.asynclocator.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Vanilla's structure presence cache uses plain hash maps and is only ever used from the server thread. Searches
 * on a locating thread read and update it too, so all access takes one lock. The server thread never waits for it:
 * cache updates (onStructureLoad, incrementReference) that find the lock busy are queued and applied by whoever
 * takes the lock next. Delaying them is safe because they only fill a cache.
 */
@Mixin(StructureCheck.class)
public abstract class StructureCheckMixin {
	@Unique private final ReentrantLock asynclocator$lock = new ReentrantLock();
	@Unique private final Queue<Runnable> asynclocator$deferred = new ConcurrentLinkedQueue<>();

	@Unique
	private void asynclocator$drain() {
		if (asynclocator$lock.getHoldCount() > 1) return; // already draining further up the stack
		Runnable r;
		while ((r = asynclocator$deferred.poll()) != null) r.run();
	}

	@WrapMethod(method = "checkStart")
	private StructureCheckResult asynclocator$lockCheck(ChunkPos pos, Structure structure, StructurePlacement placement, boolean skipKnown, Operation<StructureCheckResult> original) {
		asynclocator$lock.lock();
		try {
			asynclocator$drain();
			return original.call(pos, structure, placement, skipKnown);
		} finally {
			asynclocator$lock.unlock();
		}
	}

	@WrapMethod(method = "onStructureLoad")
	private void asynclocator$lockLoad(ChunkPos pos, Map<Structure, StructureStart> starts, Operation<Void> original) {
		if (asynclocator$lock.tryLock()) {
			try {
				asynclocator$drain();
				original.call(pos, starts);
			} finally {
				asynclocator$lock.unlock();
			}
		} else {
			asynclocator$deferred.add(() -> original.call(pos, starts));
		}
	}

	@WrapMethod(method = "incrementReference")
	private void asynclocator$lockReference(ChunkPos pos, Structure structure, Operation<Void> original) {
		if (asynclocator$lock.tryLock()) {
			try {
				asynclocator$drain();
				original.call(pos, structure);
			} finally {
				asynclocator$lock.unlock();
			}
		} else {
			asynclocator$deferred.add(() -> original.call(pos, structure));
		}
	}
}
