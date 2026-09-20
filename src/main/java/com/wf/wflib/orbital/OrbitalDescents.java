package com.wf.wflib.orbital;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrbitalDescents extends SavedData {

    public static final String NAME = "wflib_orbital_descents";
    private static final int MAX_TRACKED = 64;

    private final Map<UUID, Descent> descents = new LinkedHashMap<>();

    public static OrbitalDescents get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OrbitalDescents::new, (tag, registries) -> load(tag)), NAME);
    }

    public void add(UUID id, Descent descent) {
        descents.put(id, descent);
        while (descents.size() > MAX_TRACKED) {
            descents.remove(descents.keySet().iterator().next());
        }
        setDirty();
    }

    public boolean isEmpty() {
        return descents.isEmpty();
    }

    public int size() {
        return descents.size();
    }

    public List<Map.Entry<UUID, Descent>> entries() {
        return new ArrayList<>(descents.entrySet());
    }

    public void remove(UUID id) {
        if (descents.remove(id) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Descent> entry : descents.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", entry.getKey());
            one.putDouble("X", entry.getValue().aimX());
            one.putDouble("Z", entry.getValue().aimZ());
            one.putString("Effect", entry.getValue().effect().toString());
            if (entry.getValue().faction() != null) {
                one.putUUID("Faction", entry.getValue().faction());
            }
            list.add(one);
        }
        tag.put("Descents", list);
        return tag;
    }

    private static OrbitalDescents load(CompoundTag tag) {
        OrbitalDescents store = new OrbitalDescents();
        ListTag list = tag.getList("Descents", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            ResourceLocation effect = ResourceLocation.tryParse(one.getString("Effect"));
            if (effect == null) {
                continue;
            }
            store.descents.put(one.getUUID("Id"), new Descent(one.getDouble("X"), one.getDouble("Z"),
                    effect, one.hasUUID("Faction") ? one.getUUID("Faction") : null));
        }
        return store;
    }

    public record Descent(double aimX, double aimZ, ResourceLocation effect, @Nullable UUID faction) {
    }
}
