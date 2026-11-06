package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.AsyncLocatorMod;
import com.zovo.asynclocator.Locating;
import com.zovo.asynclocator.core.LocateExecutor;
import com.zovo.asynclocator.core.PendingMaps;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Starts the locating threads with the server and stops them when it stops. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "runServer", at = @At("HEAD"))
	private void asynclocator$start(CallbackInfo ci) {
		AsyncLocatorMod.reloadConfig();
		LocateExecutor.start(AsyncLocatorMod.config().threads, AsyncLocatorMod.config().logSearches);
	}

	@Inject(method = "stopServer", at = @At("HEAD"))
	private void asynclocator$stop(CallbackInfo ci) {
		LocateExecutor.stop();
		PendingMaps.clear();
		Locating.EYE_SEARCHES.clear();
	}
}
