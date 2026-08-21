package com.wf.wfballistics.drone.sim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-dimension persistent store of off-world drones, mirroring {@code SimMissileRegistry}. Data only:
 * offload/onload logic lives in {@link SimDroneManager}.
 */
public final class SimDroneRegistry extends SavedData {

    public static final String NAME = "wfballistics_sim_drones";

    private final List<SimDrone> drones = new ArrayList<>();

    public static SimDroneRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SimDroneRegistry::new, (tag, reg) -> SimDroneRegistry.load(tag)),
                NAME);
    }

    public static SimDroneRegistry load(CompoundTag tag) {
        SimDroneRegistry r = new SimDroneRegistry();
        ListTag list = tag.getList("Drones", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            r.drones.add(SimDrone.load(list.getCompound(i)));
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (SimDrone sd : drones) {
            list.add(sd.save());
        }
        tag.put("Drones", list);
        return tag;
    }

    public List<SimDrone> view() {
        return drones;
    }

    public SimDrone getById(UUID id) {
        for (SimDrone sd : drones) {
            if (id.equals(sd.id)) {
                return sd;
            }
        }
        return null;
    }

    public void add(SimDrone sd) {
        drones.add(sd);
        setDirty();
    }

    public void remove(SimDrone sd) {
        drones.remove(sd);
        setDirty();
    }
}
