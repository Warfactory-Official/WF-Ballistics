package com.wf.wflib.orbital.survey;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SurveyRaster extends SavedData {

    public static final String NAME = "wflib_orbital_survey";
    private static final int MAX_TILES_PER_NET = 64;

    private final Map<Long, Map<Long, SurveyTile>> byNet = new LinkedHashMap<>();

    public static SurveyRaster get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SurveyRaster::new, (tag, registries) -> load(tag)), NAME);
    }

    public SurveyTile tile(long netId, int tileX, int tileZ) {
        Map<Long, SurveyTile> tiles = byNet.computeIfAbsent(netId, k -> new LinkedHashMap<>());
        SurveyTile tile = tiles.get(SurveyTile.key(tileX, tileZ));
        if (tile == null) {
            tile = new SurveyTile(tileX, tileZ);
            tiles.put(SurveyTile.key(tileX, tileZ), tile);
            while (tiles.size() > MAX_TILES_PER_NET) {
                tiles.remove(tiles.keySet().iterator().next());
            }
            setDirty();
        }
        return tile;
    }

    @Nullable
    public SurveyTile existing(long netId, int tileX, int tileZ) {
        Map<Long, SurveyTile> tiles = byNet.get(netId);
        return tiles == null ? null : tiles.get(SurveyTile.key(tileX, tileZ));
    }

    public List<SurveyTile> tiles(long netId) {
        Map<Long, SurveyTile> tiles = byNet.get(netId);
        return tiles == null ? List.of() : new ArrayList<>(tiles.values());
    }

    public int tileCount(long netId) {
        Map<Long, SurveyTile> tiles = byNet.get(netId);
        return tiles == null ? 0 : tiles.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag nets = new ListTag();
        for (Map.Entry<Long, Map<Long, SurveyTile>> entry : byNet.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag net = new CompoundTag();
            net.putLong("Net", entry.getKey());
            ListTag tiles = new ListTag();
            for (SurveyTile tile : entry.getValue().values()) {
                tiles.add(tile.save());
            }
            net.put("Tiles", tiles);
            nets.add(net);
        }
        tag.put("Nets", nets);
        return tag;
    }

    private static SurveyRaster load(CompoundTag tag) {
        SurveyRaster raster = new SurveyRaster();
        ListTag nets = tag.getList("Nets", Tag.TAG_COMPOUND);
        for (int i = 0; i < nets.size(); i++) {
            CompoundTag net = nets.getCompound(i);
            Map<Long, SurveyTile> tiles = new LinkedHashMap<>();
            ListTag list = net.getList("Tiles", Tag.TAG_COMPOUND);
            for (int j = 0; j < list.size(); j++) {
                SurveyTile tile = SurveyTile.load(list.getCompound(j));
                tiles.put(SurveyTile.key(tile.tileX(), tile.tileZ()), tile);
            }
            raster.byNet.put(net.getLong("Net"), tiles);
        }
        return raster;
    }
}
