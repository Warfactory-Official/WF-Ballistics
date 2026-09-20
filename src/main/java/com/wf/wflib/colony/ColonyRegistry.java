package com.wf.wflib.colony;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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

/** Per-level store of colonies and the warbands they have sent out. */
public final class ColonyRegistry extends SavedData {

    public static final String NAME = "wflib_colonies";

    private final List<Colony> colonies = new ArrayList<>();
    private final List<Warband> warbands = new ArrayList<>();

    /** Grid cells {@link ColonySeeder} has already dealt with, whether or not it put anything in them. */
    private final LongOpenHashSet seededCells = new LongOpenHashSet();

    /** The level's evolution scalar; see {@link Evolution}. Here rather than in a second file for one float. */
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

    /**
     * Claim a seeding cell.
     *
     * @return true if this cell had not been examined before, and so is this caller's to place in
     */
    public boolean consumeCell(long cell) {
        if (!seededCells.add(cell)) {
            return false;
        }
        setDirty();
        return true;
    }

    public int seededCells() {
        return seededCells.size();
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
     * The colony whose blocks these are: the nearest one whose cluster actually <em>reaches</em> this position.
     *
     * @param margin extra reach beyond the cluster, for blocks on the flank of a mound
     * @return the owning colony, or null if this position belongs to none
     */
    public @Nullable Colony nearestOwning(double x, double z, double margin) {
        Colony best = null;
        double bestSq = Double.MAX_VALUE;
        for (Colony colony : colonies) {
            double dx = colony.x - x;
            double dz = colony.z - z;
            double distSq = dx * dx + dz * dz;
            if (distSq >= bestSq) {
                continue;
            }
            double reach = colony.footprintRadius() + margin;
            if (distSq <= reach * reach) {
                bestSq = distSq;
                best = colony;
            }
        }
        return best;
    }

    /**
     * @return true if any colony sits within {@code radius} of this position. Guards expansion against
     *      founding a new nest on top of an existing one.
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
        registry.evolution = tag.getFloat("evolution");
        registry.seededCells.addAll(LongArrayList.wrap(tag.getLongArray("seeded")));
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
        tag.putLongArray("seeded", seededCells.toLongArray());
        return tag;
    }
}
