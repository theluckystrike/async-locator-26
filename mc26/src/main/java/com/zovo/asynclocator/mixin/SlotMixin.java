package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.MapLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A map whose search is still running cannot be taken out of a chest or a trade slot. */
@Mixin(Slot.class)
public abstract class SlotMixin {
	@Shadow public abstract ItemStack getItem();

	@Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
	private void asynclocator$holdPendingMaps(Player player, CallbackInfoReturnable<Boolean> cir) {
		if (MapLogic.blocksPickup(getItem(), player.level() instanceof ServerLevel sl ? sl : null)) cir.setReturnValue(false);
	}
}
