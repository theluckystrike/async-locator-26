package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.AsyncLocatorMod;
import com.zovo.asynclocator.MapLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The exploration_map loot function (treasure and explorer maps in chest loot). The map is handed out at once
 * and filled when the search ends. Traded maps are handled in TreasureMapForEmeraldsMixin.
 */
@Mixin(ExplorationMapFunction.class)
public abstract class ExplorationMapFunctionMixin {
	@Shadow @Final private TagKey<Structure> destination;
	@Shadow @Final private Holder<MapDecorationType> mapDecoration;
	@Shadow @Final private byte zoom;
	@Shadow @Final private int searchRadius;
	@Shadow @Final private boolean skipKnownStructures;

	@Inject(
		method = "run",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"
		),
		cancellable = true
	)
	private void asynclocator$runAsync(ItemStack itemStack, LootContext context, CallbackInfoReturnable<ItemStack> cir) {
		if (!AsyncLocatorMod.config().chestExplorationMaps) return;
		Vec3 origin = context.getOptionalParameter(LootContextParams.ORIGIN);
		if (origin == null) return;
		ServerLevel level = context.getLevel();
		ItemStack map = new ItemStack(Items.FILLED_MAP);
		cir.setReturnValue(MapLogic.startAsync(
			level, map, destination, BlockPos.containing(origin), searchRadius, skipKnownStructures, zoom,
			mapDecoration, null
		));
	}
}
