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
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * An egg chamber, which is a colony record's hands in a loaded chunk.
 *
 * <p>Deliberately not upstream's spawner. {@code TileEntityGlyphidSpawner} is the whole colony — it decides
 * how big a swarm to make out of local pollution, keeps its own cooldown, and answers to nothing. §4 inverts
 * that: <b>the record simulates and the block is its view</b>, so everything this asks is asked of the
 * colony.
 *
 * <p>Three consequences, and each of them is the point rather than a simplification:
 * <ul>
 *   <li><b>Defenders are paid for.</b> One bug costs one {@link Colony#population}, from the same pool a
 *       warband is mustered out of. A nest that has been fighting cannot also be attacking, and one that has
 *       just sent an army defends itself badly — the two are the same number.</li>
 *   <li><b>Nothing spawns where nobody is.</b> Off-world defence is what the record is for; a chamber that
 *       spawned into an empty chunk would be paying population for bugs no one would ever see.</li>
 *   <li><b>An orphan chamber is inert.</b> Blocks left behind by a colony that no longer exists are dead
 *       flesh, not a spawner that outlived its owner.</li>
 * </ul>
 */
public class GlyphidSpawnerBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks between one chamber's spawn attempts. A nest under attack trickles rather than erupts: at a
     * tier-4 nest's five chambers this is a bug a second, which is a defence, and one burst of thirty would
     * be a single-tick spike (§11.8) that also emptied the colony in one go.
     */
    public static final int INTERVAL = 100;
    /**
     * How close a player has to be for a chamber to bother.
     */
    private static final double SPAWN_RANGE = 48.0;
    /**
     * Glyphids already standing around this chamber that make another one pointless. Counted with a box
     * query rather than off {@code GlyphidTracker}, because a level-wide list would make the cost of one
     * chamber's tick the size of the whole swarm attacking it.
     */
    private static final int LOCAL_CAP = 12;
    private static final int LOCAL_RADIUS = 8;
    /**
     * How far from the chamber a defender may surface, and how many spots it tries before giving up. Failing
     * costs nothing and no population: the chamber tries again next interval.
     */
    private static final int SCATTER = 4;
    private static final int PLACEMENT_ATTEMPTS = 8;
    /**
     * How far outside a colony's nest radius a chamber may still be bound to it. Covers a hand-placed block
     * on the flank of a mound; beyond it a spawner belongs to nobody.
     */
    private static final int BIND_MARGIN = 4;

    /**
     * The colony this chamber belongs to, resolved on its first tick and then persisted.
     */
    private @Nullable UUID colonyId;
    /**
     * Set once a chamber has been found to belong to no colony, so it never re-binds. Without it, a chamber
     * orphaned by a colony being wiped out would adopt whichever nest was founded nearest to it next.
     */
    private boolean orphaned;

    public GlyphidSpawnerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GLYPHID_SPAWNER.get(), pos, state);
    }

    /**
     * Staggered by position, so the chambers of one nest do not all spawn on the same tick.
     */
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
        // Cheapest question first: the colony lookup is a scan over every colony in the level, and there is
        // no point paying for it for a nest nobody is standing near.
        if (!watched(level, pos) || crowded(level, pos)) {
            return;
        }
        Colony colony = colony(level);
        if (colony == null || colony.population < 1.0) {
            return;
        }

        GlyphidCaste caste = GlyphidCaste.roll(level.random, Evolution.of(level));
        if (!place(level, pos, colony, caste)) {
            return;
        }
        // Debited only once a body is actually standing in the world, for the same reason
        // WarbandMaterialiser debits by what it placed rather than by what it wanted: the population is the
        // ledger, and a failed placement must not spend from it.
        colony.population -= 1.0;
        ColonyRegistry.get(level).setDirty();
    }

    /**
     * Put one defender on the surface of the mound.
     *
     * <p>The height comes off the heightmap rather than from the chamber, which is why a chamber buried
     * inside the nest still works: the bug surfaces on top of the flesh above it instead of inside it.
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
            // Home is the nest, not where it happens to have surfaced, so a garrison chased off comes back
            // here rather than settling wherever the chase ended.
            bug.hasHome = true;
            bug.homeX = colony.x;
            bug.homeY = colony.hasResolvedY() ? colony.y : y;
            bug.homeZ = colony.z;
            bug.setCurrentTask(GlyphidTasks.TASK_IDLE, null);

            if (level.addFreshEntity(bug)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find this chamber's colony, binding it the first time.
     *
     * <p>Bound lazily rather than written in by {@link com.wf.wfballistics.colony.GlyphidNest}, because half
     * a nest's blocks may be owed to a chunk that was not loaded when it was built and arrive later through
     * {@code PendingChunkEdits} — which knows a block id and nothing else. Doing it here covers both paths,
     * and a hand-placed chamber besides.
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

        BlockPos pos = getBlockPos();
        Colony nearest = registry.nearest(pos.getX(), pos.getZ());
        if (nearest == null) {
            // No colonies at all yet. Not orphaned — a nest may still be founded on top of this block.
            return null;
        }
        double dx = nearest.x - pos.getX();
        double dz = nearest.z - pos.getZ();
        double reach = nearest.nestRadius() + BIND_MARGIN;
        if (dx * dx + dz * dz > reach * reach) {
            orphaned = true;
            setChanged();
            return null;
        }
        colonyId = nearest.id;
        setChanged();
        return nearest;
    }

    /**
     * A chamber has been dug out. Take it off the colony's count, and kill the colony with its last one.
     *
     * <p>This is what makes clearing a nest mean something. The alternative — blocks that can be removed
     * while the record carries on growing and mustering — is the failure §4 warns about from the other
     * direction: the view and the truth diverging, permanently, with the player looking at the view.
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
        // Shared with the sim tier rather than written out again here, so the bench's stand-in player counts
        // for a nest exactly as it counts for a swarm -- see SimGlyphidManager.watched.
        return SimGlyphidManager.watched(level, pos.getX() + 0.5, pos.getZ() + 0.5, SPAWN_RANGE);
    }

    private static boolean crowded(ServerLevel level, BlockPos pos) {
        AABB box = new AABB(pos).inflate(LOCAL_RADIUS);
        return level.getEntitiesOfClass(EntityGlyphid.class, box).size() >= LOCAL_CAP;
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
