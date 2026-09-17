package com.wf.wfballistics.exchange;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The code-to-station directory, and the only thing that knows what a station code means. */
public final class StationRegistry extends SavedData {

    public static final String NAME = "wfballistics_stations";
    /** Attempts to draw an unused code before giving up. */
    private static final int CODE_ATTEMPTS = 8;

    private final Map<String, StationRecord> byCode = new HashMap<>();

    /**
     * @return the server-wide station directory, held on the overworld.
     */
    public static StationRegistry get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StationRegistry::new, (tag, reg) -> StationRegistry.load(tag)), NAME);
    }

    public static StationRegistry get(ServerLevel level) {
        return get(level.getServer());
    }

    public static StationRegistry load(CompoundTag tag) {
        StationRegistry registry = new StationRegistry();
        ListTag list = tag.getList("Stations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            StationRecord record = StationRecord.load(list.getCompound(i));
            registry.byCode.put(record.code(), record);
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (StationRecord record : byCode.values()) {
            list.add(record.save());
        }
        tag.put("Stations", list);
        return tag;
    }

    /** Give a pad a code, or return the one it already has. */
    public String register(ServerLevel level, BlockPos pos) {
        StationRecord existing = at(level.dimension(), pos);
        if (existing != null) {
            return existing.code();
        }
        String code = null;
        for (int attempt = 0; attempt < CODE_ATTEMPTS && code == null; attempt++) {
            String candidate = StationCode.generate();
            if (!byCode.containsKey(candidate)) {
                code = candidate;
            }
        }
        if (code == null) {
            return null;
        }
        byCode.put(code, new StationRecord(code, level.dimension(), pos.immutable(),
                level.getGameTime()));
        setDirty();
        return code;
    }

    @Nullable
    public StationRecord byCode(String code) {
        return code == null ? null : byCode.get(code);
    }

    /**
     * @return the station at this position, or null if none is registered there.
     */
    @Nullable
    public StationRecord at(ResourceKey<Level> dimension, BlockPos pos) {
        for (StationRecord record : byCode.values()) {
            if (record.pos().equals(pos) && record.dimension().equals(dimension)) {
                return record;
            }
        }
        return null;
    }

    public void forget(ResourceKey<Level> dimension, BlockPos pos) {
        StationRecord record = at(dimension, pos);
        if (record != null) {
            byCode.remove(record.code());
            setDirty();
        }
    }

    /**
     * @return true if {@code recipientCode} has authorised {@code senderCode} to send it cargo.
     */
    public boolean allows(String recipientCode, String senderCode) {
        StationRecord recipient = byCode(recipientCode);
        return recipient != null && recipient.has(StationRole.DEPOT) && recipient.allows(senderCode);
    }

    // --- roles ---

    /**
     * Set a station's roles wholesale, from a {@link StationKind} preset or otherwise.
     *
     * @return true if anything changed
     */
    public boolean setRoles(String code, java.util.Set<StationRole> roles) {
        StationRecord record = byCode(code);
        if (record == null || !record.setRoles(roles)) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * Turn one role on or off, leaving the rest alone.
     *
     * @return true if anything changed
     */
    public boolean setRole(String code, StationRole role, boolean on) {
        StationRecord record = byCode(code);
        if (record == null || !record.setRole(role, on)) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * @return every station in this dimension willing to do {@code role}, nearest first.
     */
    public List<StationRecord> withRole(ResourceKey<Level> dimension, BlockPos near, StationRole role) {
        List<StationRecord> found = new ArrayList<>();
        for (StationRecord record : byCode.values()) {
            if (record.dimension().equals(dimension) && record.has(role)) {
                found.add(record);
            }
        }
        found.sort(java.util.Comparator.comparingDouble(record -> record.pos().distSqr(near)));
        return found;
    }

    public boolean allow(String recipientCode, String senderCode) {
        StationRecord recipient = byCode(recipientCode);
        if (recipient == null || !StationCode.valid(senderCode) || !recipient.allow(senderCode)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean revoke(String recipientCode, String senderCode) {
        StationRecord recipient = byCode(recipientCode);
        if (recipient == null || !recipient.revoke(senderCode)) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * @return every registered station. For operator diagnostics only.
     */
    public List<StationRecord> all() {
        return new ArrayList<>(byCode.values());
    }

    public int size() {
        return byCode.size();
    }
}
