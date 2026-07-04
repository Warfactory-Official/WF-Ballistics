package com.wf.wfballistics.sim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-dimension persistent store of {@link SimMissile}s. Data only — advancement/spawn logic lives
 * in {@link SimMissileManager}.
 */
public final class SimMissileRegistry extends SavedData {
    public static final String NAME = "wfballistics_sim_missiles";

    private final List<SimMissile> missiles = new ArrayList<>();

    public static SimMissileRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SimMissileRegistry::load, SimMissileRegistry::new, NAME);
    }

    public static SimMissileRegistry load(CompoundTag tag) {
        SimMissileRegistry r = new SimMissileRegistry();
        ListTag list = tag.getList("Missiles", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            r.missiles.add(SimMissile.load(list.getCompound(i)));
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (SimMissile sm : missiles) {
            list.add(sm.save());
        }
        tag.put("Missiles", list);
        return tag;
    }

    public List<SimMissile> view() {
        return missiles;
    }

    /**
     * @return the simulated missile with this id, or null if none (e.g. it's a real entity or already gone).
     */
    public SimMissile getById(UUID id) {
        for (SimMissile sm : missiles) {
            if (id.equals(sm.id)) {
                return sm;
            }
        }
        return null;
    }

    public void add(SimMissile sm) {
        missiles.add(sm);
        setDirty();
    }

    public void remove(SimMissile sm) {
        missiles.remove(sm);
        setDirty();
    }
}
