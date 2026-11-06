package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.SearchTicket;
import com.zovo.asynclocator.core.LocateExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A structure search asks for chunks. Called from another thread, vanilla getChunk runs on the server thread and
 * holds it until the chunk is ready. For our locating threads the request is only scheduled on the server thread
 * (with a ticket that outlives the request) and the locating thread does the waiting. Other threads are untouched.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
	@Shadow @Final public ServerLevel level;

	@Shadow
	public abstract void addTicket(Ticket ticket, ChunkPos pos);

	@Shadow
	private CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(int x, int z, ChunkStatus status, boolean create) {
		throw new AssertionError();
	}

	@Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
	private void asynclocator$waitOffThread(int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
		if (!LocateExecutor.onLocatorThread()) return;
		ChunkResult<ChunkAccess> result = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			CompletableFuture<ChunkResult<ChunkAccess>> future = CompletableFuture.supplyAsync(() -> {
				if (create) addTicket(new Ticket(SearchTicket.TYPE, ChunkLevel.byStatus(status)), new ChunkPos(x, z));
				return getChunkFutureMainThread(x, z, status, create);
			}, level.getServer()).thenCompose(Function.identity());
			try {
				result = future.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("structure search cancelled", e);
			} catch (ExecutionException e) {
				throw new IllegalStateException("chunk " + x + ", " + z + " failed to load", e.getCause());
			}
			if (result.isSuccess() || !create) break;
		}
		ChunkAccess chunk = result.orElse(null);
		if (chunk == null && create) {
			throw new IllegalStateException("Chunk not there when requested: " + result.getError());
		}
		cir.setReturnValue(chunk);
	}
}
