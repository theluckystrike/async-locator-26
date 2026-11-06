package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.MapLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A copy of a pending map that ended up in an inventory is finished on its next inventory tick. */
@Mixin(MapItem.class)
public abstract class MapItemMixin {
	@Inject(method = "inventoryTick", at = @At("HEAD"))
	private void asynclocator$finishCarriedMap(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot, CallbackInfo ci) {
		MapLogic.resolve(stack, level, null);
	}
}
