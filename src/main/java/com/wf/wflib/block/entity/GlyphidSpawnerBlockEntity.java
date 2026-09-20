package com.wf.wflib.block.entity;

import com.mojang.logging.LogUtils;
import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.colony.Colony;
import com.wf.wflib.colony.ColonyRegistry;
import com.wf.wflib.colony.Evolution;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.entity.glyphid.GlyphidTasks;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/** An egg chamber: a colony record's hands in a loaded chunk. */
public class GlyphidSpawnerBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks between spawn attempts. A tier-4 nest's five chambers trickle a bug a second, not a burst. */
    public static final int INTERVAL = 100;
    /** How close a player has to be for a chamber to bother. */
    private static final double SPAWN_RANGE = 48.0;
    /** How far a defender may surface and how many spots it tries. Failing costs nothing and no population. */
    private static final int SCATTER = 4;
    private static final int PLACEMENT_ATTEMPTS = 8;
    /**
     * How far outside a colony's footprint a chamber may still bind to it, covering a hand-placed block on the
     * flank of a mound.
     */
    private static final int BIND_MARGIN = 4;

    /** The colony this chamber belongs to, resolved on its first tick and then persisted. */
    private @Nullable UUID colonyId;
    /** Set once a chamber belongs to no colony, so an orphan never adopts whichever nest is founded next. */
    private boolean orphaned;

    public GlyphidSpawnerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GLYPHID_SPAWNER.get(), pos, state);
    }

    /** Staggered by position, so the chambers of one nest do not all spawn on the same tick. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, GlyphidSpawnerBlockEntity be) {
        if (!(level instanceof ServerLevel server)
                || Math.floorMod(server.getGameTime() + pos.asLong(), INTERVAL) != 0) {
            return;
        }
        if (server.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        be.hatch(server, pos);
    }

    private void hatch(ServerLevel level, BlockPos pos) {
        // Cheapest question first: the colony lookup scans every colony in the level.
        if (!watched(level, pos)) {
            return;
        }
        if (!level.isPositionEntityTicking(pos)) {
            return;
        }
        Colony colony = colony(level);
        if (colony == null || colony.population < 1.0 || colony.garrison >= colony.garrisonCap()) {
            return;
        }

        GlyphidCaste caste = GlyphidCaste.roll(level.random, Evolution.of(level));
        if (!place(level, pos, colony, caste)) {
            return;
        }
        colony.population -= 1.0;
        colony.garrison++;
        ColonyRegistry.get(level).setDirty();
    }

    /** Put one defender on the surface of the mound. */
    private boolean place(ServerLevel level, BlockPos pos, Colony colony, GlyphidCaste caste) {
        EntityType<? extends EntityGlyphid> type = caste.type();
        EntityDimensions size = type.getDimensions();

        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            int x = pos.getX() + level.random.nextInt(SCATTER * 2 + 1) - SCATTER;
            int z = pos.getZ() + level.random.nextInt(SCATTER * 2 + 1) - SCATTER;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                continue;
            }

            double px = x + 0.5;
            double pz = z + 0.5;
            if (!level.noCollision(size.makeBoundingBox(px, y, pz))) {
                continue;
            }

            EntityGlyphid bug = type.create(level);
            if (bug == null) {
                return false;
            }
            bug.moveTo(px, y, pz, level.random.nextFloat() * 360F, 0F);
            bug.hasHome = true;
            bug.homeX = colony.x;
            bug.homeY = colony.hasResolvedY() ? colony.y : y;
            bug.homeZ = colony.z;
            bug.setCurrentTask(GlyphidTasks.TASK_IDLE, null);
            bug.garrison = true;
            bug.setPersistenceRequired();

            if (level.addFreshEntity(bug)) {
                return true;
            }
        }
        return false;
    }

    /** Find this chamber's colony, binding it the first time. */
    private @Nullable Colony colony(ServerLevel level) {
        if (orphaned) {
            return null;
        }
        ColonyRegistry registry = ColonyRegistry.get(level);
        if (colonyId != null) {
            Colony known = registry.byId(colonyId);
            if (known == null) {
                orphaned = true;
                setChanged();
            }
            return known;
        }

        if (registry.colonies().isEmpty()) {
            // No colonies at all yet. Not orphaned: a nest may still be founded on top of this block.
            return null;
        }
        BlockPos pos = getBlockPos();
        Colony owner = registry.nearestOwning(pos.getX(), pos.getZ(), BIND_MARGIN);
        if (owner == null) {
            orphaned = true;
            setChanged();
            return null;
        }
        colonyId = owner.id;
        setChanged();
        return owner;
    }

    /** A chamber has been dug out. */
    public void onBroken(ServerLevel level) {
        Colony colony = colony(level);
        if (colony == null || colony.spawners <= 0) {
            return;
        }
        ColonyRegistry registry = ColonyRegistry.get(level);
        colony.spawners--;
        if (colony.spawners > 0) {
            registry.setDirty();
            return;
        }
        registry.remove(colony);
        LOGGER.debug("[wflib] {} lost its last chamber and is gone", colony);
    }

    private static boolean watched(ServerLevel level, BlockPos pos) {
        // Shared with the sim tier so the bench's stand-in player counts for a nest as it does for a swarm.
        return SimGlyphidManager.watched(level, pos.getX() + 0.5, pos.getZ() + 0.5, SPAWN_RANGE);
    }

    // --- persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (colonyId != null) {
            tag.putUUID("colony", colonyId);
        }
        tag.putBoolean("orphaned", orphaned);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        colonyId = tag.hasUUID("colony") ? tag.getUUID("colony") : null;
        orphaned = tag.getBoolean("orphaned");
    }
}
