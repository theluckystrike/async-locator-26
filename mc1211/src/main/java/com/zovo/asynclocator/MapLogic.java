package com.zovo.asynclocator;

import com.zovo.asynclocator.core.LocateExecutor;
import com.zovo.asynclocator.core.PendingMaps;
import com.zovo.asynclocator.core.ResultCache;
import com.zovo.asynclocator.mixin.MerchantMenuAccessor;
import com.zovo.asynclocator.mixin.MerchantOfferAccessor;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
 * Exploration maps that are filled in later. The stack is a filled map with its map id from the start, a "Locating structure" lore line and a pending id. When the search ends, the map
 * data is stored under that map id and the stack is finished in place, as vanilla would have done it. Copies
 * made in the meantime (dropped items, a trade result slot) are finished from {@link #DONE} when next touched.
 */
public final class MapLogic {
	private static final Component LOCATING = Component.literal("Locating structure...")
		.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	/** What a finished search found ({@code pos} null = nothing), and the lore the stack had before. */
	record Done(BlockPos pos, Holder<MapDecorationType> decoration, ItemLore loreBefore) {}

	/** Finished searches, kept 30 minutes for copies of a pending map. */
	static final ResultCache<Done> DONE = new ResultCache<>(30 * 60 * 1000L, 4096);

	private MapLogic() {}

	public static String pendingId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		return data.copyTag().getString(PendingMaps.TAG).orElse(null);
	}

	/** Marks {@code stack} as pending and starts the search. Returns the same stack. */
	public static ItemStack startAsync(
		ServerLevel level, ItemStack stack, TagKey<Structure> destination, BlockPos from, int radius,
		boolean skipKnown, byte zoom, Holder<MapDecorationType> decoration, Entity merchantOrNull
	) {
		String id = PendingMaps.newId();
		MapId mapId = level.getFreeMapId();
		ItemLore loreBefore = stack.get(DataComponents.LORE);
		stack.set(DataComponents.MAP_ID, mapId);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(PendingMaps.TAG, id));
		stack.set(DataComponents.LORE, new ItemLore(List.of(LOCATING)));
		AbstractVillager merchant = merchantOrNull instanceof AbstractVillager v ? v : null;
		// Search, map data and the biome preview are all made on the locating thread; the server thread only stores the map.
		LocateExecutor.submit(
			destination.location().toString() + " map near " + from.toShortString(),
			() -> {
				BlockPos pos = level.findNearestMapStructure(destination, from, radius, skipKnown);
				if (pos == null) return null;
				MapItemSavedData data = MapItemSavedData.createFresh(pos.getX(), pos.getZ(), zoom, true, true, level.dimension());
				MapPreview.paint(level, data);
				return new Found(pos, data);
			},
			level.getServer(),
			found -> finish(level, stack, id, mapId, found, decoration, loreBefore, merchant)
		);
		return stack;
	}

	/** A finished search: the structure position and the map data drawn for it. */
	record Found(BlockPos pos, MapItemSavedData data) {}

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
			resolve(stack, level);
		}
		if (merchant != null) refreshTraders(level, merchant);
	}

	/**
	 * Brings a stack up to date. Returns true while its search is still running. A finished stack gets its target
	 * marker (or is reverted if nothing was found); a stack whose search is unknown (from before a restart) only
	 * loses the pending mark.
	 */
	public static boolean resolve(ItemStack stack, ServerLevel levelOrNull) {
		if (stack.isEmpty() || !stack.has(DataComponents.MAP_ID)) return false;
		String id = pendingId(stack);
		if (id == null) return false;
		if (PendingMaps.isActive(id)) return true;
		Done done = DONE.get(id);
		clearPending(stack, done == null ? null : done.loreBefore());
		if (done != null && done.pos() != null) {
			MapItemSavedData.addTargetDecoration(stack, done.pos(), "+", done.decoration());
		} else if (done != null) {
			nothingFound(stack);
		}
		return false;
	}

	/**
	 * Vanilla would have left an empty map; a filled map cannot turn back into one, so the stack is dropped.
	 * (Traded maps never get here with a count: their trade is disabled first and the result is not sold.)
	 */
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

	/** True if the stack must not be taken yet. */
	public static boolean blocksPickup(ItemStack stack, ServerLevel levelOrNull) {
		return resolve(stack, levelOrNull);
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
