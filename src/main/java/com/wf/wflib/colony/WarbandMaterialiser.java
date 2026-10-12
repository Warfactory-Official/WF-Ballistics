package com.wf.wflib.colony;

import com.mojang.logging.LogUtils;
import com.wf.wflib.ModEntities;
import com.wf.wflib.config.WFConfig;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.entity.glyphid.GlyphidTasks;
import com.wf.wflib.entity.glyphid.flight.GlyphidFlight;
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

/** Warband record -> real glyphids once somebody is there to be attacked; T2 -> T0 step. */
public final class WarbandMaterialiser {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Exhausting these leaves a glyphid owed, retried next tick. */
    private static final int PLACEMENT_ATTEMPTS = 12;

    private static final int SCATTER = 8;

    private WarbandMaterialiser() {
    }

    public static void tick(ServerLevel level) {
        ColonyRegistry registry = ColonyRegistry.get(level);
        if (registry.warbands().isEmpty()) {
            return;
        }
        int budget = ColonyConfig.materialisePerTick();
        if (budget <= 0) {
            return;
        }

        // Copy: materialise() can remove a warband mid-iteration.
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

    private static boolean shouldMaterialise(ServerLevel level, Warband warband) {
        int chunkX = (int) Math.floor(warband.x) >> 4;
        int chunkZ = (int) Math.floor(warband.z) >> 4;
        // Chunk must already be loaded; loading one here defeats lazy materialisation.
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

    /** @return glyphids actually placed; also exactly what the record is debited. */
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
            LOGGER.debug("[wflib] materialised {} of {} at ({}, {}), {} still owed",
                    spawned, warband.id, (int) warband.x, (int) warband.z, warband.count);
        }
        return spawned;
    }

    private static boolean place(ServerLevel level, Warband warband, @Nullable Colony origin) {
        RandomSource random = level.random;
        int centerX = (int) Math.floor(warband.x);
        int centerZ = (int) Math.floor(warband.z);

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

            int y = warband.flying
                    ? Math.min(level.getMaxBuildHeight() - 2, surface + GlyphidFlight.CLEARANCE)
                    : surface;

            if (!warband.flying) {
                BlockPos ground = new BlockPos(x, y - 1, z);
                // Surface height alone doesn't rule out water/lava; check the fluid directly.
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

    private static void orient(EntityGlyphid glyphid, Warband warband, @Nullable Colony origin, int y) {
        glyphid.setCanFly(warband.flying);
        if (warband.flying) {
            glyphid.setBombs(WFConfig.GLYPHID_BOMB_LOAD.get());
        }
        glyphid.hasHome = true;
        glyphid.homeX = origin != null ? origin.x : (int) warband.x;
        glyphid.homeY = origin != null && origin.hasResolvedY() ? origin.y : y;
        glyphid.homeZ = origin != null ? origin.z : (int) warband.z;

        glyphid.taskX = warband.targetX;
        glyphid.taskY = y;
        glyphid.taskZ = warband.targetZ;
        glyphid.setCurrentTask(GlyphidTasks.TASK_FOLLOW, null);

        // setAirborne before first tick; placed mid-air, it falls otherwise.
        if (warband.flying) {
            glyphid.setAirborne(true);
            glyphid.setFlightTarget(
                    new net.minecraft.world.phys.Vec3(warband.targetX + 0.5, y, warband.targetZ + 0.5), false);
        }
    }
}
