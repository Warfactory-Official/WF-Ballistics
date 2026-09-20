package com.wf.wflib.recon.example.decoy;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Every live decoy in a level. */
public final class DecoyRegistry {

    private static final Map<ResourceKey<Level>, DecoyRegistry> BY_LEVEL = new HashMap<>();

    private final List<Decoy> decoys = new ArrayList<>();
    private int nextId = 1;

    private DecoyRegistry() {
    }

    public static DecoyRegistry get(ServerLevel level) {
        WorldThread.assertOn("decoy registry access");
        return BY_LEVEL.computeIfAbsent(level.dimension(), k -> new DecoyRegistry());
    }

    /** Drop everything, for a server stopping or a level unloading. */
    public static void forget(ServerLevel level) {
        BY_LEVEL.remove(level.dimension());
    }

    public static void shutdown() {
        BY_LEVEL.clear();
    }

    public Decoy release(double x, double y, double z, double vx, double vy, double vz,
                         float rcs, long gameTime, int lifetimeTicks) {
        Decoy decoy = new Decoy(nextId++, x, y, z, vx, vy, vz, rcs, gameTime, gameTime + lifetimeTicks);
        decoys.add(decoy);
        return decoy;
    }

    /**
     * @return the decoys still alive at this tick, pruning the ones that are not.
     */
    public List<Decoy> view(long gameTime) {
        for (Iterator<Decoy> it = decoys.iterator(); it.hasNext(); ) {
            if (!it.next().alive(gameTime)) {
                it.remove();
            }
        }
        return decoys;
    }

    public int count() {
        return decoys.size();
    }

    public void clear() {
        decoys.clear();
    }
}
