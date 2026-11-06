package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.AsyncLocatorMod;
import com.zovo.asynclocator.Locating;
import com.zovo.asynclocator.MapLogic;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Explorer maps sold by cartographers in 1.21.x: the trade appears at once and the map is filled later. */
@Mixin(targets = "net.minecraft.world.entity.npc.villager.VillagerTrades$TreasureMapForEmeralds")
public abstract class TreasureMapForEmeraldsMixin {
	@Shadow @Final private int emeraldCost;
	@Shadow @Final private TagKey<Structure> destination;
	@Shadow @Final private String displayName;
	@Shadow @Final private Holder<MapDecorationType> destinationType;
	@Shadow @Final private int maxUses;
	@Shadow @Final private int villagerXp;

	@Inject(
		method = "getOffer",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"
		),
		cancellable = true
	)
	private void asynclocator$offerAsync(ServerLevel level, Entity trader, RandomSource random, CallbackInfoReturnable<MerchantOffer> cir) {
		if (!AsyncLocatorMod.config().merchantExplorationMaps || !(trader instanceof AbstractVillager) || !Locating.structuresEnabled(level)) return;
		ItemStack map = new ItemStack(Items.FILLED_MAP);
		map.set(DataComponents.ITEM_NAME, Component.translatable(displayName));
		MapLogic.startAsync(level, map, destination, trader.blockPosition(), 100, true, (byte) 2, destinationType, trader);
		cir.setReturnValue(new MerchantOffer(
			new ItemCost(Items.EMERALD, emeraldCost), Optional.of(new ItemCost(Items.COMPASS)), map, maxUses, villagerXp, 0.2F
		));
	}
}
