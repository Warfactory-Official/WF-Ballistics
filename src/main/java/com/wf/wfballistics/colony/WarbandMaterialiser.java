package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.flight.GlyphidFlight;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;

/**
 * Turns a warband record into actual glyphids when somebody is there to be attacked by them.
 *
 * <p>This is the T2 -> T0 step, and the one place in the colony simulation where numbers can go wrong.
 * {@code DroneCarrier}'s "one brain, two bodies" does not help here: that swaps <em>one</em> drone between
 * representations and keeps its identity. Turning one record holding forty into forty entities is a different
 * operation, so the three ways it usually breaks are each closed deliberately:
 *
 * <ul>
 *   <li><b>Counts not conserved.</b> {@link Warband#count} is the only ledger, and {@link #materialise} holds
 *       the only line that debits it — by exactly the number of bodies that reached the world. A glyphid that
 *       could not be placed is still owed, so it is still in the record. Conservation is not a check made
 *       afterwards; it is that nothing else can spend a warband.</li>
 *   <li><b>Bodies inside walls.</b> Nothing is placed without a loaded chunk, a real surface height and a
 *       clear bounding box. Failing all of that is normal and costs nothing: the bug waits a tick.</li>
 *   <li><b>Double-spend across two loaded regions.</b> Structurally impossible rather than guarded against —
 *       there is one record, it is debited on the server thread, and no copy of it exists anywhere.</li>
 * </ul>
 *
 * <p>A large warband streams in over several ticks rather than spawning at once, so the arrival of three
 * hundred glyphids is not a single-tick spike.
 */
public final class WarbandMaterialiser {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Placement attempts per glyphid. Failing them all leaves it owed to the record and tried again next
     * tick, which is the right answer for a warband standing on a lake or a cliff edge.
     */
    private static final int PLACEMENT_ATTEMPTS = 12;

    /**
     * How far bodies are scattered around the record's position, so a warband arrives as a swarm rather than
     * a column standing in one block.
     */
    private static final int SCATTER = 8;

    private WarbandMaterialiser() {
    }

    /**
     * Materialise whatever is owed in this dimension. Server thread only: it touches the world.
     *
     * <p>Deliberately not part of {@code tickWarbands}, which is pure simulation and is run many times over by
     * {@code fastForward}. Fast-forwarding the simulation must not spawn entities.
     */
    public static void tick(ServerLevel level) {
        ColonyRegistry registry = ColonyRegistry.get(level);
        if (registry.warbands().isEmpty()) {
            return;
        }
        int budget = ColonyConfig.materialisePerTick();
        if (budget <= 0) {
            return;
        }

        // Copied because a warband spent down to nothing removes itself from the list.
        for (Warband warband : new ArrayList<>(registry.warbands())) {
            if (budget <= 0) {
                break;
            }
            if (!shouldMaterialise(level, warband)) {
                continue;
            }
            budget -= materialise(level, registry, warband, budget);
        }
    }

    /**
     * @return true if this warband is somewhere a glyphid could be seen, and somewhere one could be placed.
     */
    private static boolean shouldMaterialise(ServerLevel level, Warband warband) {
        int chunkX = (int) Math.floor(warband.x) >> 4;
        int chunkZ = (int) Math.floor(warband.z) >> 4;
        // No chunk, no ground to stand on, and loading one to find out would defeat the whole design.
        if (!level.hasChunk(chunkX, chunkZ)) {
            return false;
        }
        double range = ColonyConfig.materialiseRange();
        double limit = range * range;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - warband.x;
            double dz = player.getZ() - warband.z;
            if (dx * dx + dz * dz <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Place up to {@code budget} of this warband's glyphids in the world.
     *
     * @return how many actually reached it, which is exactly how many the record was debited
     */
    public static int materialise(ServerLevel level, ColonyRegistry registry, Warband warband, int budget) {
        int wanted = Math.min(budget, warband.count);
        if (wanted <= 0) {
            registry.remove(warband);
            return 0;
        }

        Colony origin = registry.byId(warband.origin);
        int spawned = 0;
        for (int i = 0; i < wanted; i++) {
            if (place(level, warband, origin)) {
                spawned++;
            }
        }

        warband.count -= spawned;
        if (warband.count <= 0) {
            registry.remove(warband);
        }
        if (spawned > 0) {
            registry.setDirty();
            LOGGER.debug("[wfballistics] materialised {} of {} at ({}, {}), {} still owed",
                    spawned, warband.id, (int) warband.x, (int) warband.z, warband.count);
        }
        return spawned;
    }

    /**
     * Put one glyphid on real ground near the warband.
     *
     * @return true if it made it into the world
     */
    private static boolean place(ServerLevel level, Warband warband, @Nullable Colony origin) {
        RandomSource random = level.random;
        int centerX = (int) Math.floor(warband.x);
        int centerZ = (int) Math.floor(warband.z);
        EntityType<EntityGlyphid> type = ModEntities.GLYPHID.get();
        EntityDimensions size = type.getDimensions();

        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            int x = centerX + random.nextInt(SCATTER * 2 + 1) - SCATTER;
            int z = centerZ + random.nextInt(SCATTER * 2 + 1) - SCATTER;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }

            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (surface <= level.getMinBuildHeight() || surface >= level.getMaxBuildHeight()) {
                continue;
            }

            // A flight arrives in the air, so it needs clearance rather than footing. That is why a flying
            // warband crosses a coastline intact where a walking one leaves most of itself owed to the record:
            // there is no such thing as unsuitable ground when you are not standing on it.
            int y = warband.flying
                    ? Math.min(level.getMaxBuildHeight() - 2, surface + GlyphidFlight.CLEARANCE)
                    : surface;

            if (!warband.flying) {
                BlockPos ground = new BlockPos(x, y - 1, z);
                // Nothing stands on water or in lava; the surface height alone rules out neither.
                if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty()) {
                    continue;
                }
            }

            double px = x + 0.5;
            double pz = z + 0.5;
            if (!level.noCollision(size.makeBoundingBox(px, y, pz))) {
                continue;
            }

            EntityGlyphid glyphid = type.create(level);
            if (glyphid == null) {
                return false;
            }
            glyphid.moveTo(px, y, pz, random.nextFloat() * 360F, 0F);
            orient(glyphid, warband, origin, y);

            if (level.addFreshEntity(glyphid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Give a freshly placed glyphid the two things it needs to behave like part of an attack rather than a
     * wild spawn: somewhere to fall back to, and somewhere to go.
     */
    private static void orient(EntityGlyphid glyphid, Warband warband, @Nullable Colony origin, int y) {
        glyphid.setCanFly(warband.flying);
        if (warband.flying) {
            glyphid.setBombs(WFConfig.GLYPHID_BOMB_LOAD.get());
        }
        glyphid.hasHome = true;
        glyphid.homeX = origin != null ? origin.x : (int) warband.x;
        glyphid.homeY = origin != null && origin.hasResolvedY() ? origin.y : y;
        glyphid.homeZ = origin != null ? origin.z : (int) warband.z;

        // The task height is a placeholder: the target column is usually still unloaded from here, so the
        // move goal re-resolves it against real terrain as the swarm closes in.
        glyphid.taskX = warband.targetX;
        glyphid.taskY = y;
        glyphid.taskZ = warband.targetZ;
        glyphid.setCurrentTask(GlyphidTasks.TASK_FOLLOW, null);

        // Placed in the air, so it has to be flying before its first tick or it simply falls out of the sky.
        if (warband.flying) {
            glyphid.setAirborne(true);
            glyphid.setFlightTarget(
                    new net.minecraft.world.phys.Vec3(warband.targetX + 0.5, y, warband.targetZ + 0.5), false);
        }
    }
}
