package com.wf.wfballistics.kinetic;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.chunk.DetonationChunkGuard;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Drives every kinetic round that is currently out of the world, one dimension at a time. */
public final class KineticSimManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ResourceKey<Level>, List<KineticSim>> BY_LEVEL = new HashMap<>();

    private KineticSimManager() {
    }

    /** Take a climbing shell out of the world. The caller discards the entity. */
    public static void handOff(ServerLevel level, KineticShellEntity shell) {
        KineticSim sim = KineticSim.of(shell, level.getGameTime());
        BY_LEVEL.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(sim);
        LOGGER.debug("[wfballistics] kinetic shell {} left the world at {} climbing at {}",
                sim.id, sim.pos, sim.velocity.y);
    }

    /** How many rounds this dimension currently has in the air but out of the world. */
    public static int count(ServerLevel level) {
        List<KineticSim> sims = BY_LEVEL.get(level.dimension());
        return sims == null ? 0 : sims.size();
    }

    /** The round with this id, while it is out of the world. Null once it is back, or was never here. */
    @Nullable
    public static KineticSim find(ServerLevel level, UUID id) {
        List<KineticSim> sims = BY_LEVEL.get(level.dimension());
        if (sims == null) {
            return null;
        }
        for (KineticSim sim : sims) {
            if (sim.id.equals(id)) {
                return sim;
            }
        }
        return null;
    }

    /** Drop everything in flight here. For tests and for a level being unloaded. */
    public static void clear(ServerLevel level) {
        BY_LEVEL.remove(level.dimension());
    }

    public static void tick(ServerLevel level) {
        List<KineticSim> sims = BY_LEVEL.get(level.dimension());
        if (sims == null || sims.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (Iterator<KineticSim> it = sims.iterator(); it.hasNext(); ) {
            KineticSim sim = it.next();
            if (sim.handOffTime == now) {
                // Handed over earlier in this same tick, after the entity had already flown it.
                continue;
            }
            if (--sim.remainingLife <= 0) {
                it.remove();
                continue;
            }
            sim.advance();
            if (sim.descending()) {
                it.remove();
                materialise(level, sim);
            }
        }
        if (sims.isEmpty()) {
            BY_LEVEL.remove(level.dimension());
        }
    }

    /** Put the round back. */
    private static void materialise(ServerLevel level, KineticSim sim) {
        KineticShellEntity shell = sim.toEntity(level);
        ChunkPos chunk = shell.chunkPosition();
        DetonationChunkGuard.CONTROLLER.forceChunk(level, shell.getUUID(), chunk.x, chunk.z, true, true);
        level.getChunk(chunk.x, chunk.z);
        level.addFreshEntity(shell);
        LOGGER.debug("[wfballistics] kinetic shell {} came back at {} descending at {}",
                sim.id, sim.pos, sim.velocity.y);
    }
}
