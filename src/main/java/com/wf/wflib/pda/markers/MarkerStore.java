package com.wf.wflib.pda.markers;

import com.wf.wflib.recon.ReconOwners;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Map markers per side ({@link #side}); overworld data, every dimension's markers. */
public final class MarkerStore extends SavedData {
    private static final String NAME = "wflib_pda_markers";
    public static final int CAP = 64;

    private final Map<UUID, List<Marker>> bySide = new HashMap<>();
    private long nextId = 1;

    public static MarkerStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MarkerStore::new, (tag, registries) -> load(tag)), NAME);
    }

    /** Faction, else the player alone. */
    public static UUID side(ServerPlayer player) {
        UUID side = ReconOwners.owningEntity(player);
        return side == null ? player.getUUID() : side;
    }

    public List<Marker> of(UUID side) {
        return bySide.getOrDefault(side, List.of());
    }

    /** @return false at {@link #CAP}. */
    public boolean add(UUID side, ResourceLocation dimension, BlockPos pos, String label) {
        List<Marker> list = bySide.computeIfAbsent(side, k -> new ArrayList<>());
        if (list.size() >= CAP) {
            return false;
        }
        list.add(new Marker(nextId++, dimension, pos.immutable(), label));
        setDirty();
        return true;
    }

    public boolean remove(UUID side, long id) {
        List<Marker> list = bySide.get(side);
        if (list == null || !list.removeIf(m -> m.id() == id)) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("next", nextId);
        ListTag sides = new ListTag();
        bySide.forEach((side, markers) -> {
            CompoundTag s = new CompoundTag();
            s.putUUID("side", side);
            ListTag list = new ListTag();
            for (Marker m : markers) {
                CompoundTag t = new CompoundTag();
                t.putLong("id", m.id());
                t.putString("dim", m.dimension().toString());
                t.putLong("pos", m.pos().asLong());
                t.putString("label", m.label());
                list.add(t);
            }
            s.put("markers", list);
            sides.add(s);
        });
        tag.put("sides", sides);
        return tag;
    }

    private static MarkerStore load(CompoundTag tag) {
        MarkerStore store = new MarkerStore();
        store.nextId = tag.getLong("next");
        for (Tag raw : tag.getList("sides", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) raw;
            List<Marker> list = new ArrayList<>();
            for (Tag m : s.getList("markers", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) m;
                list.add(new Marker(t.getLong("id"), ResourceLocation.parse(t.getString("dim")),
                        BlockPos.of(t.getLong("pos")), t.getString("label")));
            }
            store.bySide.put(s.getUUID("side"), list);
        }
        return store;
    }
}
