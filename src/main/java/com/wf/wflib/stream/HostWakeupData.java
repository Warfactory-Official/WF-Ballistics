package com.wf.wflib.stream;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Last known position of every {@link com.wf.wflib.api.DetachedBodyHost}, for waking one from unloaded terrain. */
public final class HostWakeupData extends SavedData {

    public static final String FILE_ID = "wflib_host_wakeup";

    private final Map<UUID, Entry> entries = new HashMap<>();

    public record Entry(UUID hostId, ResourceKey<Level> dimension, Vec3 position, long gameTime) {

        public ChunkPos chunk() {
            return new ChunkPos((int) Math.floor(position.x) >> 4, (int) Math.floor(position.z) >> 4);
        }

    }

    private static SavedData.Factory<HostWakeupData> factory() {
        return new SavedData.Factory<>(HostWakeupData::new, HostWakeupData::load, null);
    }

    public static HostWakeupData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    private static HostWakeupData load(CompoundTag tag, HolderLookup.Provider registries) {
        HostWakeupData data = new HostWakeupData();
        ListTag list = tag.getList("Hosts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (dimensionId == null || !entry.hasUUID("Id")) {
                continue;
            }
            UUID id = entry.getUUID("Id");
            data.entries.put(id, new Entry(id,
                    ResourceKey.create(Registries.DIMENSION, dimensionId),
                    new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z")),
                    entry.getLong("GameTime")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : this.entries.values()) {
            CompoundTag element = new CompoundTag();
            element.putUUID("Id", entry.hostId());
            element.putString("Dimension", entry.dimension().location().toString());
            element.putDouble("X", entry.position().x);
            element.putDouble("Y", entry.position().y);
            element.putDouble("Z", entry.position().z);
            element.putLong("GameTime", entry.gameTime());
            list.add(element);
        }
        tag.put("Hosts", list);
        return tag;
    }

    public void put(UUID hostId, ResourceKey<Level> dimension, Vec3 position, long gameTime) {
        Entry previous = this.entries.get(hostId);
        if (previous != null
                && previous.dimension() == dimension
                && previous.position().distanceToSqr(position) < 1.0E-4) {
            return;
        }
        this.entries.put(hostId, new Entry(hostId, dimension, position, gameTime));
        setDirty();
    }

    @Nullable
    public Entry get(UUID hostId) {
        return this.entries.get(hostId);
    }

    public void remove(UUID hostId) {
        if (this.entries.remove(hostId) != null) {
            setDirty();
        }
    }

    public Collection<Entry> all() {
        return this.entries.values();
    }

}
