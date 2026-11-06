package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.AsyncLocatorMod;
import com.zovo.asynclocator.Locating;
import com.zovo.asynclocator.core.LocateExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dolphins fed with fish search for treasure on a locating thread and wait in place until it answers. */
@Mixin(targets = "net.minecraft.world.entity.animal.dolphin.Dolphin$DolphinSwimToTreasureGoal")
public abstract class DolphinSwimToTreasureGoalMixin {
	@Shadow @Final private Dolphin dolphin;
	@Shadow private boolean stuck;

	@Unique private LocateExecutor.Task<BlockPos> asynclocator$task;

	@Inject(
		method = "start",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"
		),
		cancellable = true
	)
	private void asynclocator$startAsync(CallbackInfo ci) {
		if (!AsyncLocatorMod.config().dolphinTreasure || !(dolphin.level() instanceof ServerLevel level) || !Locating.structuresEnabled(level)) return;
		if (asynclocator$task != null) asynclocator$task.cancel();
		asynclocator$task = Locating.nearestMapStructure(level, StructureTags.DOLPHIN_LOCATED, dolphin.blockPosition(), 50, false, pos -> {
			asynclocator$task = null;
			if (pos != null) {
				((DolphinAccessor) dolphin).asynclocator$setTreasurePos(pos);
				level.broadcastEntityEvent(dolphin, (byte) 38);
			} else {
				stuck = true;
			}
		}, () -> {
			asynclocator$task = null;
			stuck = true;
		});
		ci.cancel();
	}

	@Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
	private void asynclocator$keepWhileSearching(CallbackInfoReturnable<Boolean> cir) {
		if (asynclocator$task != null) cir.setReturnValue(true);
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void asynclocator$waitWhileSearching(CallbackInfo ci) {
		if (asynclocator$task != null) ci.cancel();
	}

	@Inject(method = "stop", at = @At("HEAD"))
	private void asynclocator$cancelOnStop(CallbackInfo ci) {
		if (asynclocator$task != null) {
			asynclocator$task.cancel();
			asynclocator$task = null;
		}
	}
}
