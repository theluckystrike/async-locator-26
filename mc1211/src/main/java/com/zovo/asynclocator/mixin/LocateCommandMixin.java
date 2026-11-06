package com.zovo.asynclocator.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zovo.asynclocator.AsyncLocatorMod;
import com.zovo.asynclocator.Locating;
import java.time.Duration;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** /locate structure: the search runs on a locating thread and the answer is sent when it is ready. */
@Mixin(LocateCommand.class)
public abstract class LocateCommandMixin {
	@Inject(method = "locateStructure", at = @At("HEAD"), cancellable = true)
	private static void asynclocator$locateStructure(
		CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> resourceOrTag,
		CallbackInfoReturnable<Integer> cir
	) throws CommandSyntaxException {
		if (!AsyncLocatorMod.config().locateCommand) return;
		Registry<Structure> registry = source.getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE);
		HolderSet<Structure> target = LocateCommandInvoker.asynclocator$getHolders(resourceOrTag, registry).orElse(null);
		if (target == null) return; // let vanilla throw its "invalid structure" error
		BlockPos from = BlockPos.containing(source.getPosition());
		ServerLevel level = source.getLevel();
		long t0 = System.nanoTime();
		source.sendSuccess(
			() -> Component.literal("Locating " + resourceOrTag.asPrintable() + " in the background...")
				.withStyle(ChatFormatting.GRAY),
			false
		);
		Locating.nearestFromGenerator(level, target, from, 100, false, found -> {
			if (found == null) {
				source.sendFailure(Component.translatableEscape("commands.locate.structure.not_found", resourceOrTag.asPrintable()));
			} else {
				LocateCommand.showLocateResult(
					source, resourceOrTag, from, found, "commands.locate.structure.success", false,
					Duration.ofNanos(System.nanoTime() - t0)
				);
			}
		}, () -> source.sendFailure(Component.literal("The search for " + resourceOrTag.asPrintable() + " failed. See the server log.")));
		cir.setReturnValue(0);
	}
}
