package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
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
 * Turns a warband record into actual glyphids when somebody is there to be attacked by them: the T2 -> T0
 * step, and the one place in the colony simulation where numbers can go wrong.
 *
 * <p>{@link Warband#count} is the only ledger and {@link #materialise} holds the only line that debits it, by
 * exactly the number of bodies that reached the world — a glyphid that could not be placed is still owed.
 * Nothing is placed without a loaded chunk, a real surface height and a clear box.
 *
 * <p>A large warband streams in over several ticks rather than spawning as a single-tick spike.
 */
public final class WarbandMaterialiser {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Placement attempts per glyphid. Failing them all leaves it owed and retried, which is fine. */
    private static final int PLACEMENT_ATTEMPTS = 12;

    /** How far bodies scatter around the record, so a warband arrives as a swarm and not as a column. */
    private static final int SCATTER = 8;

    private WarbandMaterialiser() {
    }

    /**
     * Materialise whatever is owed in this dimension. Server thread only: it touches the world. Kept out of
     * {@code tickWarbands}, which {@code fastForward} runs many times over and which must not spawn anything.
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

        // Rolled per bug rather than per warband, so a mixed column reads as an army rather than as a boss
        // fight. See GlyphidCaste for what evolution has unlocked by now.
        GlyphidCaste caste = GlyphidCaste.roll(random, Evolution.of(level));
        EntityType<? extends EntityGlyphid> type = caste.type();
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

            // A flight needs clearance rather than footing, which is why it crosses a coastline intact where
            // a walking warband leaves most of itself owed.
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

    /** Give a placed glyphid what makes it part of an attack: somewhere to fall back to, somewhere to go. */
    private static void orient(EntityGlyphid glyphid, Warband warband, @Nullable Colony origin, int y) {
        glyphid.setCanFly(warband.flying);
        if (warband.flying) {
            glyphid.setBombs(WFConfig.GLYPHID_BOMB_LOAD.get());
        }
        glyphid.hasHome = true;
        glyphid.homeX = origin != null ? origin.x : (int) warband.x;
        glyphid.homeY = origin != null && origin.hasResolvedY() ? origin.y : y;
        glyphid.homeZ = origin != null ? origin.z : (int) warband.z;

        // A placeholder height: the target column is usually unloaded from here, and the move goal
        // re-resolves it against real terrain as the swarm closes in.
        glyphid.taskX = warband.targetX;
        glyphid.taskY = y;
        glyphid.taskZ = warband.targetZ;
        glyphid.setCurrentTask(GlyphidTasks.TASK_FOLLOW, null);

        // Placed in the air, so it must be flying before its first tick or it falls out of the sky.
        if (warband.flying) {
            glyphid.setAirborne(true);
            glyphid.setFlightTarget(
                    new net.minecraft.world.phys.Vec3(warband.targetX + 0.5, y, warband.targetZ + 0.5), false);
        }
    }
}
