package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.MapLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dropped copy of a pending map is finished as soon as its search is done. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Shadow public abstract ItemStack getItem();

	@Inject(method = "tick", at = @At("HEAD"))
	private void asynclocator$finishDroppedMap(CallbackInfo ci) {
		ItemStack stack = getItem();
		if (!stack.isEmpty() && ((ItemEntity) (Object) this).level() instanceof ServerLevel level) MapLogic.resolve(stack, level);
	}
}
