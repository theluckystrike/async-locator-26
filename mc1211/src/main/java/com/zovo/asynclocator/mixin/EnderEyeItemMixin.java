package com.zovo.asynclocator.mixin;

import com.zovo.asynclocator.AsyncLocatorMod;
import com.zovo.asynclocator.Locating;
import java.util.UUID;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eye of ender: the stronghold search runs on a locating thread. The eye is thrown when the answer arrives, from
 * the player's position at that moment, and only if the player still holds an eye in the same hand.
 */
@Mixin(EnderEyeItem.class)
public abstract class EnderEyeItemMixin {
	@Inject(
		method = "use",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"
		),
		cancellable = true
	)
	private void asynclocator$useAsync(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (!AsyncLocatorMod.config().eyeOfEnder || !(level instanceof ServerLevel serverLevel) || !Locating.structuresEnabled(serverLevel)) return;
		UUID id = player.getUUID();
		if (!Locating.EYE_SEARCHES.add(id)) {
			cir.setReturnValue(InteractionResult.CONSUME);
			return;
		}
		Locating.nearestMapStructure(serverLevel, StructureTags.EYE_OF_ENDER_LOCATED, player.blockPosition(), 100, false, pos -> {
			Locating.EYE_SEARCHES.remove(id);
			if (pos == null || player.isRemoved() || player.level() != serverLevel) return;
			ItemStack stack = player.getItemInHand(hand);
			if (!stack.is(Items.ENDER_EYE)) return;
			EyeOfEnder eye = new EyeOfEnder(serverLevel, player.getX(), player.getY(0.5), player.getZ());
			eye.setItem(stack);
			eye.signalTo(Vec3.atLowerCornerOf(pos));
			serverLevel.gameEvent(GameEvent.PROJECTILE_SHOOT, eye.position(), GameEvent.Context.of(player));
			serverLevel.addFreshEntity(eye);
			if (player instanceof ServerPlayer sp) {
				CriteriaTriggers.USED_ENDER_EYE.trigger(sp, pos);
			}
			float pitch = Mth.lerp(serverLevel.getRandom().nextFloat(), 0.33F, 0.5F);
			serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL, 1.0F, pitch);
			stack.consume(1, player);
			player.awardStat(Stats.ITEM_USED.get(Items.ENDER_EYE));
		}, () -> Locating.EYE_SEARCHES.remove(id));
		cir.setReturnValue(InteractionResult.SUCCESS_SERVER);
	}
}
