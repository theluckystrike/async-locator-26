package com.zovo.asynclocator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * The water outline that vanilla draws on explorer and treasure maps (MapItem.renderBiomePreviewMap), drawn on a
 * locating thread. Vanilla asks the level for 16,384 biomes on the server thread, which is most of the cost of a
 * treasure map. Here the biomes come straight from the biome noise, the same source chunks are generated from, so
 * no chunk is touched. The drawing rules are vanilla's. Only call this on map data nobody else can see yet.
 */
public final class MapPreview {
	private MapPreview() {}

	public static void paint(ServerLevel level, MapItemSavedData data) {
		if (level.dimension() != data.dimension) return;
		BiomeManager biomes = level.getBiomeManager().withDifferentSource(level::getUncachedNoiseBiome);
		int scale = 1 << data.scale;
		boolean[] watery = new boolean[16384];
		int startX = data.centerX / scale - 64;
		int startZ = data.centerZ / scale - 64;
		int sampleY = level.getSeaLevel();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int row = 0; row < 128; row++) {
			if (Thread.currentThread().isInterrupted()) return;
			for (int column = 0; column < 128; column++) {
				Holder<Biome> biome = biomes.getBiome(pos.set((startX + column) * scale, sampleY, (startZ + row) * scale));
				watery[row * 128 + column] = biome.is(BiomeTags.WATER_ON_MAP_OUTLINES);
			}
		}
		for (int mx = 1; mx < 127; mx++) {
			for (int mz = 1; mz < 127; mz++) {
				int waterCount = 0;
				for (int dx = -1; dx < 2; dx++) {
					for (int dz = -1; dz < 2; dz++) {
						if ((dx != 0 || dz != 0) && watery[(mz + dz) * 128 + mx + dx]) waterCount++;
					}
				}
				MapColor.Brightness brightness = MapColor.Brightness.LOWEST;
				MapColor color = MapColor.NONE;
				if (watery[mz * 128 + mx]) {
					color = MapColor.COLOR_ORANGE;
					if (waterCount > 7 && mz % 2 == 0) {
						switch ((mx + (int) (Mth.sin(mz + 0.0F) * 7.0F)) / 8 % 5) {
							case 0, 4 -> brightness = MapColor.Brightness.LOW;
							case 1, 3 -> brightness = MapColor.Brightness.NORMAL;
							case 2 -> brightness = MapColor.Brightness.HIGH;
							default -> { }
						}
					} else if (waterCount > 7) {
						color = MapColor.NONE;
					} else if (waterCount > 5) {
						brightness = MapColor.Brightness.NORMAL;
					} else if (waterCount > 1) {
						brightness = MapColor.Brightness.LOW;
					}
				} else if (waterCount > 0) {
					color = MapColor.COLOR_BROWN;
					brightness = waterCount > 3 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOWEST;
				}
				if (color != MapColor.NONE) data.setColor(mx, mz, color.getPackedId(brightness));
			}
		}
	}
}
