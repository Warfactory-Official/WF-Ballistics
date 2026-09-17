package com.wf.wfballistics.orbital;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CargoStore extends SavedData {

    public static final String NAME = "wfballistics_orbital_cargo";
    private static final int MAX_IN_FLIGHT = 64;

    private final Map<UUID, List<ItemStack>> manifests = new LinkedHashMap<>();

    public static CargoStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CargoStore::new, (tag, registries) -> load(tag, registries)), NAME);
    }

    public void put(UUID shuttle, List<ItemStack> manifest) {
        manifests.put(shuttle, manifest);
        while (manifests.size() > MAX_IN_FLIGHT) {
            UUID oldest = manifests.keySet().iterator().next();
            manifests.remove(oldest);
        }
        setDirty();
    }

    public List<ItemStack> take(UUID shuttle) {
        List<ItemStack> manifest = manifests.remove(shuttle);
        if (manifest != null) {
            setDirty();
        }
        return manifest == null ? List.of() : manifest;
    }

    public int inFlight() {
        return manifests.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, List<ItemStack>> entry : manifests.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", entry.getKey());
            ListTag items = new ListTag();
            for (ItemStack stack : entry.getValue()) {
                if (!stack.isEmpty()) {
                    items.add(stack.save(registries));
                }
            }
            one.put("Items", items);
            list.add(one);
        }
        tag.put("Manifests", list);
        return tag;
    }

    private static CargoStore load(CompoundTag tag, HolderLookup.Provider registries) {
        CargoStore store = new CargoStore();
        ListTag list = tag.getList("Manifests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            List<ItemStack> items = new ArrayList<>();
            ListTag saved = one.getList("Items", Tag.TAG_COMPOUND);
            for (int j = 0; j < saved.size(); j++) {
                ItemStack.parse(registries, saved.getCompound(j)).ifPresent(items::add);
            }
            store.manifests.put(one.getUUID("Id"), items);
        }
        return store;
    }
}
