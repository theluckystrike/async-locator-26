package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.MapLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a trade screen opens, map trades whose search is unknown (the villager was saved and loaded while it ran)
 * start it again, and finished ones get their marker.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "sendMerchantOffers", at = @At("HEAD"))
	private void asynclocator$resolveTradeMaps(int containerId, MerchantOffers offers, int level, int xp, boolean showProgress, boolean canRestock, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (!(self.level() instanceof ServerLevel serverLevel) || !(self.containerMenu instanceof MerchantMenu menu)) return;
		AbstractVillager merchant = ((MerchantMenuAccessor) menu).asynclocator$getTrader() instanceof AbstractVillager v ? v : null;
		for (MerchantOffer offer : offers) {
			MapLogic.resolve(offer.getResult(), serverLevel, merchant);
		}
	}
}
