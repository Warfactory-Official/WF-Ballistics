package com.wf.wfballistics.colony;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-level store of colonies and the warbands they have sent out. Data only; the simulation lives in
 * {@link ColonyManager}, mirroring how {@code SimDroneRegistry} splits from {@code SimDroneManager}.
 */
public final class ColonyRegistry extends SavedData {

    public static final String NAME = "wfballistics_colonies";

    private final List<Colony> colonies = new ArrayList<>();
    private final List<Warband> warbands = new ArrayList<>();

    /**
     * The level's evolution scalar. Lives here rather than in its own {@link SavedData} because it is
     * colony state that happens to be scalar, and a second file for one float is a second thing to keep in
     * step. See {@link Evolution}.
     */
    private float evolution;

    public static ColonyRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ColonyRegistry::new, (tag, lookup) -> load(tag)), NAME);
    }

    public List<Colony> colonies() {
        return colonies;
    }

    public List<Warband> warbands() {
        return warbands;
    }

    public float evolution() {
        return evolution;
    }

    public void setEvolution(float value) {
        float clamped = Math.max(0.0F, Math.min(1.0F, value));
        if (clamped != evolution) {
            evolution = clamped;
            setDirty();
        }
    }

    public void add(Colony colony) {
        colonies.add(colony);
        setDirty();
    }

    public void remove(Colony colony) {
        if (colonies.remove(colony)) {
            setDirty();
        }
    }

    public void add(Warband warband) {
        warbands.add(warband);
        setDirty();
    }

    public void remove(Warband warband) {
        if (warbands.remove(warband)) {
            setDirty();
        }
    }

    public @Nullable Colony byId(UUID id) {
        for (Colony colony : colonies) {
            if (colony.id.equals(id)) {
                return colony;
            }
        }
        return null;
    }

    /**
     * @return the colonies whose nest sits in this chunk. Used on chunk load to materialise them.
     */
    public List<Colony> inChunk(ChunkPos pos) {
        List<Colony> out = new ArrayList<>(1);
        for (Colony colony : colonies) {
            if (colony.chunk().equals(pos)) {
                out.add(colony);
            }
        }
        return out;
    }

    /**
     * @return the nearest colony to a position, or null if there are none.
     */
    public @Nullable Colony nearest(double x, double z) {
        Colony best = null;
        double bestSq = Double.MAX_VALUE;
        for (Colony colony : colonies) {
            double dx = colony.x - x;
            double dz = colony.z - z;
            double distSq = dx * dx + dz * dz;
            if (distSq < bestSq) {
                bestSq = distSq;
                best = colony;
            }
        }
        return best;
    }

    /**
     * @return true if any colony sits within {@code radius} of this position. Guards expansion against
     * founding a new nest on top of an existing one.
     */
    public boolean anyWithin(double x, double z, double radius) {
        double limit = radius * radius;
        for (Colony colony : colonies) {
            double dx = colony.x - x;
            double dz = colony.z - z;
            if (dx * dx + dz * dz <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return how many colonies sit within {@code radius} of a position.
     */
    public int countWithin(double x, double z, double radius) {
        double limit = radius * radius;
        int count = 0;
        for (Colony colony : colonies) {
            double dx = colony.x - x;
            double dz = colony.z - z;
            if (dx * dx + dz * dz <= limit) {
                count++;
            }
        }
        return count;
    }

    public static ColonyRegistry load(CompoundTag tag) {
        ColonyRegistry registry = new ColonyRegistry();
        ListTag colonyList = tag.getList("colonies", Tag.TAG_COMPOUND);
        for (int i = 0; i < colonyList.size(); i++) {
            registry.colonies.add(Colony.load(colonyList.getCompound(i)));
        }
        ListTag warbandList = tag.getList("warbands", Tag.TAG_COMPOUND);
        for (int i = 0; i < warbandList.size(); i++) {
            registry.warbands.add(Warband.load(warbandList.getCompound(i)));
        }
        // Absent in worlds saved before evolution existed, which reads back as 0 -- a world that has not
        // evolved yet, which is the right answer for one that never could.
        registry.evolution = tag.getFloat("evolution");
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag colonyList = new ListTag();
        for (Colony colony : colonies) {
            colonyList.add(colony.save());
        }
        tag.put("colonies", colonyList);

        ListTag warbandList = new ListTag();
        for (Warband warband : warbands) {
            warbandList.add(warband.save());
        }
        tag.put("warbands", warbandList);
        tag.putFloat("evolution", evolution);
        return tag;
    }
}
