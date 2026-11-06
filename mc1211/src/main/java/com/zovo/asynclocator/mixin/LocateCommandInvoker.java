package com.zovo.asynclocator.mixin;

import java.util.Optional;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LocateCommand.class)
public interface LocateCommandInvoker {
	@Invoker("getHolders")
	static Optional<? extends HolderSet.ListBacked<Structure>> asynclocator$getHolders(
		ResourceOrTagKeyArgument.Result<Structure> resourceOrTag, Registry<Structure> registry
	) {
		throw new AssertionError();
	}
}
