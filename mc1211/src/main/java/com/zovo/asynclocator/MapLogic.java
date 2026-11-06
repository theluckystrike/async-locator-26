package com.zovo.asynclocator;

import com.zovo.asynclocator.core.LocateExecutor;
import com.zovo.asynclocator.core.PendingMaps;
import com.zovo.asynclocator.core.ResultCache;
import com.zovo.asynclocator.mixin.MerchantMenuAccessor;
import com.zovo.asynclocator.mixin.MerchantOfferAccessor;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Exploration maps that are filled in later. The stack is a filled map with its map id from the start, a "Locating structure" lore line and a pending tag that holds the search itself.
 * When the search ends, the map data is stored under that map id and the stack is finished in place, as vanilla
 * would have done it. Copies made in the meantime are finished from {@link #DONE} when next touched. A pending map
 * whose search is unknown (after a restart, or long after) runs its search again from the stored parameters.
 */
public final class MapLogic {
	private static final Component LOCATING = Component.literal("Locating structure...")
		.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	/** What a finished search found ({@code pos} null = nothing), and the lore the stack had before. */
	record Done(BlockPos pos, Holder<MapDecorationType> decoration, ItemLore loreBefore) {}

	/** A finished search: the structure position and the map data drawn for it. */
	record Found(BlockPos pos, MapItemSavedData data) {}

	/** Searches that failed with an error, by pending id; a map stops retrying after MAX_TRIES. */
	static final java.util.Map<String, Integer> FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
	static final int MAX_TRIES = 3;

	/** Finished searches, kept 30 minutes for copies of a pending map. */
	static final ResultCache<Done> DONE = new ResultCache<>(30 * 60 * 1000L, 4096);

	private MapLogic() {}

	static CompoundTag pendingTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		return data.copyTag().getCompound(PendingMaps.TAG).orElse(null);
	}

	public static String pendingId(ItemStack stack) {
		CompoundTag tag = pendingTag(stack);
		return tag == null ? null : tag.getString("id").orElse(null);
	}

	/** Marks {@code stack} as pending and starts the search. Returns the same stack. */
	public static ItemStack startAsync(
		ServerLevel level, ItemStack stack, TagKey<Structure> destination, BlockPos from, int radius,
		boolean skipKnown, byte zoom, Holder<MapDecorationType> decoration, Entity merchantOrNull
	) {
		String id = PendingMaps.newId();
		MapId mapId = level.getFreeMapId();
		ItemLore loreBefore = stack.get(DataComponents.LORE);
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		tag.putString("dest", "#" + destination.location());
		tag.putInt("x", from.getX());
		tag.putInt("y", from.getY());
		tag.putInt("z", from.getZ());
		tag.putInt("radius", radius);
		tag.putBoolean("skip", skipKnown);
		tag.putByte("zoom", zoom);
		decoration.unwrapKey().ifPresent(k -> tag.putString("decoration", k.identifier().toString()));
		tag.putString("dimension", level.dimension().identifier().toString());
		stack.set(DataComponents.MAP_ID, mapId);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.put(PendingMaps.TAG, tag));
		stack.set(DataComponents.LORE, new ItemLore(List.of(LOCATING)));
		AbstractVillager merchant = merchantOrNull instanceof AbstractVillager v ? v : null;
		launch(level, stack, id, mapId, destination, from, radius, skipKnown, zoom, decoration, loreBefore, merchant);
		return stack;
	}

	private static void launch(
		ServerLevel level, ItemStack stack, String id, MapId mapId, TagKey<Structure> destination, BlockPos from,
		int radius, boolean skipKnown, byte zoom, Holder<MapDecorationType> decoration, ItemLore loreBefore,
		AbstractVillager merchant
	) {
		// Search, map data and the biome preview are all made on the locating thread; the server thread only stores the map.
		LocateExecutor.submit(
			"#" + destination.location() + " map near " + from.toShortString(),
			() -> {
				BlockPos pos = level.findNearestMapStructure(destination, from, radius, skipKnown);
				if (pos == null) return null;
				MapItemSavedData data = MapItemSavedData.createFresh(pos.getX(), pos.getZ(), zoom, true, true, level.dimension());
				MapPreview.paint(level, data);
				return new Found(pos, data);
			},
			Locating.mainThread(level),
			found -> finish(level, stack, id, mapId, found, decoration, loreBefore, merchant),
			// A failed search is not "nothing found": the map stays pending and the next touch searches again,
			// up to MAX_TRIES times in total.
			() -> {
				FAILURES.merge(id, 1, Integer::sum);
				PendingMaps.finish(id);
			}
		);
	}

	static void finish(
		ServerLevel level, ItemStack stack, String id, MapId mapId, Found found,
		Holder<MapDecorationType> decoration, ItemLore loreBefore, AbstractVillager merchant
	) {
		BlockPos pos = found == null ? null : found.pos();
		if (found != null) {
			// Map data lives under the map id, so every copy of this stack shows the same map.
			level.setMapData(mapId, found.data());
		}
		DONE.put(id, new Done(pos, decoration, loreBefore));
		PendingMaps.finish(id);
		if (merchant != null && pos == null) {
			// The trade is disabled; its result stack stays a valid item so the offer list can still be sent.
			disableOffer(merchant, stack);
			clearPending(stack, loreBefore);
		} else {
			resolve(stack, level, null);
		}
		if (merchant != null) refreshTraders(level, merchant);
	}

	/**
	 * Brings a stack up to date. Returns true while its search is still running. A finished stack gets its target
	 * marker (or is removed if nothing was found, as vanilla discards such maps). A stack whose search is unknown
	 * starts it again when a level is given.
	 */
	public static boolean resolve(ItemStack stack, ServerLevel level, AbstractVillager merchant) {
		if (stack.isEmpty() || !stack.has(DataComponents.MAP_ID) || !stack.has(DataComponents.CUSTOM_DATA)) return false;
		CompoundTag tag = pendingTag(stack);
		if (tag == null) return false;
		String id = tag.getString("id").orElse(null);
		if (PendingMaps.isActive(id)) return true;
		Done done = DONE.get(id);
		if (done != null) {
			clearPending(stack, done.loreBefore());
			if (done.pos() != null) {
				MapItemSavedData.addTargetDecoration(stack, done.pos(), "+", done.decoration());
			} else if (merchant != null) {
				// A trade result must stay a real item (an empty one cannot be sent or saved): disable the trade.
				disableOffer(merchant, stack);
			} else {
				nothingFound(stack);
			}
			return false;
		}
		if (level != null && restart(level, stack, tag, id, merchant)) return true;
		clearPending(stack, null);
		return false;
	}

	private static boolean restart(ServerLevel touchedIn, ItemStack stack, CompoundTag tag, String id, AbstractVillager merchant) {
		if (FAILURES.getOrDefault(id, 0) >= MAX_TRIES) {
			LocateExecutor.LOG.warn("Giving up on a pending map after {} failed searches", MAX_TRIES);
			return false;
		}
		// Search in the dimension the map was made for, not the one it was carried to.
		ServerLevel level = tag.getString("dimension").map(Identifier::tryParse)
			.map(d -> touchedIn.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, d)))
			.orElse(touchedIn);
		if (level == null) return false;
		TagKey<Structure> destination = decodeDestination(tag.getString("dest").orElse("")).orElse(null);
		Holder<MapDecorationType> decoration = tag.getString("decoration").map(Identifier::tryParse)
			.flatMap(BuiltInRegistries.MAP_DECORATION_TYPE::get).map(h -> (Holder<MapDecorationType>) h).orElse(null);
		MapId mapId = stack.get(DataComponents.MAP_ID);
		if (destination == null || decoration == null || mapId == null || id == null) return false;
		BlockPos from = new BlockPos(tag.getInt("x").orElse(0), tag.getInt("y").orElse(0), tag.getInt("z").orElse(0));
		PendingMaps.activate(id);
		LocateExecutor.LOG.info("Restarting the search for a pending map near {}", from.toShortString());
		launch(level, stack, id, mapId, destination, from, tag.getInt("radius").orElse(50), tag.getBoolean("skip").orElse(true),
			tag.getByte("zoom").orElse((byte) 2), decoration, null, merchant);
		return true;
	}

	static Optional<TagKey<Structure>> decodeDestination(String text) {
		if (!text.startsWith("#")) return Optional.empty();
		Identifier id = Identifier.tryParse(text.substring(1));
		return id == null ? Optional.empty() : Optional.of(TagKey.create(Registries.STRUCTURE, id));
	}

	/** Vanilla would have left an empty map; a filled map cannot turn back into one, so the stack is removed. */
	static void nothingFound(ItemStack stack) {
		stack.setCount(0);
	}

	static void clearPending(ItemStack stack, ItemLore loreBefore) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(PendingMaps.TAG));
		ItemLore lore = stack.get(DataComponents.LORE);
		boolean ours = lore != null && lore.lines().size() == 1 && lore.lines().get(0).getString().equals(LOCATING.getString());
		if (!ours) return;
		// Setting back the value the stack had keeps it identical to a vanilla stack (no "!lore" patch).
		stack.set(DataComponents.LORE, loreBefore != null ? loreBefore : stack.getPrototype().getOrDefault(DataComponents.LORE, ItemLore.EMPTY));
	}

	static boolean disableOffer(AbstractVillager merchant, ItemStack stack) {
		for (MerchantOffer offer : merchant.getOffers()) {
			if (offer.getResult() == stack) {
				if (AsyncLocatorMod.config().removeTradeWhenNothingFound) {
					merchant.getOffers().remove(offer);
					LocateExecutor.LOG.info("No structure found for a traded map: trade removed");
				} else {
					((MerchantOfferAccessor) offer).asynclocator$setMaxUses(0);
					offer.setToOutOfStock();
					LocateExecutor.LOG.info("No structure found for a traded map: trade marked sold out");
				}
				return true;
			}
		}
		return false;
	}

	static void refreshTraders(ServerLevel level, AbstractVillager merchant) {
		for (ServerPlayer player : level.players()) {
			if (player.containerMenu instanceof MerchantMenu menu
				&& ((MerchantMenuAccessor) menu).asynclocator$getTrader() == merchant) {
				int villagerLevel = merchant instanceof Villager v ? v.getVillagerData().level() : 1;
				player.sendMerchantOffers(
					menu.containerId, merchant.getOffers(), villagerLevel, merchant.getVillagerXp(),
					merchant.showProgressBar(), merchant.canRestock()
				);
				((MerchantMenuAccessor) menu).asynclocator$getTradeContainer().updateSellItem();
				menu.broadcastChanges();
			}
		}
	}
}
