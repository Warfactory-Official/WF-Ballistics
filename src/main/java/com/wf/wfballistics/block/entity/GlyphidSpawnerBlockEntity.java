package com.wf.wfballistics.block.entity;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.colony.Colony;
import com.wf.wfballistics.colony.ColonyRegistry;
import com.wf.wfballistics.colony.Evolution;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidManager;
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

/**
 * An egg chamber: a colony record's hands in a loaded chunk. The record simulates and the block is its view
 * (§4), so everything this asks is asked of the colony rather than decided here.
 *
 * <p>A defender costs one {@link Colony#population} from the same pool a warband is mustered out of, and is
 * then <em>kept</em> — marked {@code garrison}, made persistent, and held against {@link Colony#garrison} for
 * as long as it lives. One bug is a body or a number, never both.
 *
 * <p>Nothing spawns where nobody is, and a chamber whose colony no longer exists is inert flesh.
 */
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
     * flank of a mound. Measured against {@link Colony#footprintRadius()}, not {@code nestRadius()} — a
     * cluster's outer chambers are mounds away from the record and would all read as orphaned.
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
        // A chunk whose entities are not restored yet reads as empty, and so does the recount -- hatching in
        // that window would stack a second garrison on the one already saved in the chunk.
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
        // Debited only once a body is standing: the population is the ledger, and a failed placement must
        // not spend from it. The garrison goes up in the same breath because the recount is a staggered tick
        // away, and until then every other chamber would read a stale count and hatch past the cap.
        colony.population -= 1.0;
        colony.garrison++;
        ColonyRegistry.get(level).setDirty();
    }

    /**
     * Put one defender on the surface of the mound. The height comes off the heightmap rather than from the
     * chamber, so a buried chamber surfaces its bug on top of the flesh instead of inside it.
     */
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
            // Home is the nest and not where it surfaced, so a garrison chased off comes back -- and it is
            // how the recount recognises this bug as this colony's.
            bug.hasHome = true;
            bug.homeX = colony.x;
            bug.homeY = colony.hasResolvedY() ? colony.y : y;
            bug.homeZ = colony.z;
            bug.setCurrentTask(GlyphidTasks.TASK_IDLE, null);
            // Kept rather than despawned: a defender is population already spent, so letting one evaporate
            // when a player walks off would silently bleed the nest every visit.
            bug.garrison = true;
            bug.setPersistenceRequired();

            if (level.addFreshEntity(bug)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find this chamber's colony, binding it the first time. Lazy rather than written in by
     * {@link com.wf.wfballistics.colony.GlyphidNest}, since half a nest's blocks can arrive later through
     * {@code PendingChunkEdits}, which knows a block id and nothing else.
     */
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
            // No colonies at all yet. Not orphaned — a nest may still be founded on top of this block.
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

    /**
     * A chamber has been dug out. Take it off the colony's count, and kill the colony with its last one —
     * otherwise the blocks come away while the record carries on growing and mustering.
     */
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
        LOGGER.debug("[wfballistics] {} lost its last chamber and is gone", colony);
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
