package com.wf.wflib.round;

import com.mojang.logging.LogUtils;
import com.norwood.ahf.config.AhfConfig;
import com.norwood.ahf.hit.HitRegistration;
import com.norwood.ahf.hit.HitboxApi;
import com.norwood.ahf.hit.RayHit;
import com.norwood.ahf.lagcomp.EntityHistory;
import com.norwood.ahf.lagcomp.Snapshot;
import com.norwood.ahf.part.HitboxPart;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.PreciseHitbox;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.armor.ArmorSystem;
import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.damage.DamageClass;
import com.wf.wflib.damage.WFDamage;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.effect.ImpactContext;
import com.wf.wflib.round.effect.ImpactEffect;
import com.wf.wflib.round.effect.ImpactEffects;
import com.wf.wflib.round.effect.ImpactTrigger;
import com.wf.wflib.round.pen.BlockPen;
import com.wf.wflib.round.pen.PenTable;
import com.wf.wflib.round.terrain.AsyncTerrainSource;
import com.wf.wflib.round.terrain.SectionSolidity;
import com.wf.wflib.sim.SimKind;
import com.wf.wflib.sim.SimTier;
import com.wf.wflib.sim.SimWorld;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Unguided rounds without entities (bullets, shells, bombs): one {@link RoundBatch} per level, stepped on the tick
 * thread after the level tick. Flight contract = {@code KineticPreset}: {@code pos += v; v = v * decay - g}, decay
 * in float. Transient: rounds in flight are not saved; {@link DeferredImpacts} are.
 */
public final class Rounds implements SimKind<RoundBatch> {

    public static final Rounds KIND = new Rounds();

    /** Fraction of kinetic energy landing as damage when the preset names no flat figure. */
    private static final float ENERGY_TO_DAMAGE = 0.1f;
    private static final ResourceLocation SHAPED_CHARGE = WarheadRegistry.rl("shaped_charge");
    private static final ResourceLocation INERT = WarheadRegistry.rl("inert");
    /** Spawn/end packets go to players this close; chunk-loading rounds go to the whole level. */
    private static final double AUDIENCE_RANGE = 1024.0;
    /** Head = eye height +- this, for {@link KineticPreset#headshot}. */
    private static final double HEAD_HALF_HEIGHT = 0.25;
    /** Pick slack on plain AABB targets, as vanilla's projectile sweep. */
    private static final double PICK_INFLATE = 0.3;
    /** Chunk ticket ids: {@code key} in the low half. */
    private static final long TICKET_HIGH = 0x524F554E44L;
    /** Steps per tick for an unwatched round over unloaded ground. */
    private static final int MAX_SUBSTEPS = 16;
    /** Terrain prefetch beyond the segment, ticks of straight travel. */
    private static final int LOOKAHEAD = 8;
    private static final Logger LOGGER = LogUtils.getLogger();
    /** {@link #launch} {@code shooterSeq}: none. */
    public static final int NO_SEQ = -1;
    private static final Comparator<EntityHit> NEAREST = Comparator.comparingDouble(h -> h.distanceSq);
    /**
     * Entity sweep reach beyond the segment: query inflate 1 + entity section lookup's 2 (vanilla
     * {@code EntitySectionStorage.getEntities}).
     */
    private static final double ENTITY_REACH = 3.0;
    /** {@link #walk} scratch; server thread. */
    private static final LongArrayList WALK = new LongArrayList();
    /** Rewound broadphase scratch; server thread, not re-entered. */
    private static final List<LivingEntity> CANDIDATES = new ArrayList<>();

    private static long nextKey = 1L;
    /** Config {@code rounds.rewindWholeFlight}; false => rewind spent by the first sweeping step. */
    public static boolean rewindWholeFlight = true;

    private Rounds() {
    }

    private static RoundBatch batch(ServerLevel level) {
        SimTier<RoundBatch> tier = SimWorld.get(level).tier(KIND);
        List<RoundBatch> view = tier.view();
        if (view.isEmpty()) {
            tier.add(new RoundBatch());
        }
        return view.get(0);
    }

    /**
     * A gun's shot: heading spread by {@code inaccuracy} + the preset's dispersion (degrees), at muzzle speed, plus
     * {@code carrier} velocity.
     * @return the round's key
     */
    public static long fire(ServerLevel level, KineticPreset preset, Vec3 from, Vec3 direction, float inaccuracy,
                            Vec3 carrier, @Nullable Entity shooter, @Nullable UUID faction) {
        Vec3 heading = direction.lengthSqr() < 1.0e-8 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        float spread = inaccuracy + preset.dispersion();
        if (spread > 0.0f) {
            RandomSource random = level.random;
            double s = 0.0172275 * Math.toRadians(spread) * 40.0;
            heading = heading.add(random.triangle(0.0, s), random.triangle(0.0, s), random.triangle(0.0, s)).normalize();
        }
        return launch(level, preset, from, heading.scale(preset.muzzleSpeed()).add(carrier), shooter, faction);
    }

    /** A round at {@code at} moving {@code velocity}. @return the round's key */
    public static long launch(ServerLevel level, KineticPreset preset, Vec3 at, Vec3 velocity,
                              @Nullable Entity shooter, @Nullable UUID faction) {
        return launch(level, preset, at, velocity, shooter, faction, 1.0f, 0, NO_SEQ);
    }

    /**
     * {@link #launch(ServerLevel, KineticPreset, Vec3, Vec3, Entity, UUID)}, lag-compensated.
     * @param damageScale direct-hit damage factor
     * @param rewindTicks each step sweeps living targets as broadcast {@code rewindTicks} before the step's
     *                    {@code EntityHistory.broadcastTick} (untracked entities and passengers live); 0 = live
     * @param shooterSeq  on the wire with the shooter id (client tracer adoption); {@link #NO_SEQ} none
     * @return the round's key
     */
    public static long launch(ServerLevel level, KineticPreset preset, Vec3 at, Vec3 velocity,
                              @Nullable Entity shooter, @Nullable UUID faction, float damageScale, int rewindTicks,
                              int shooterSeq) {
        if (rewindTicks < 0 || !(damageScale >= 0.0f)) {
            throw new IllegalArgumentException("rewind " + rewindTicks + ", damage scale " + damageScale);
        }
        long key = nextKey++;
        RoundBatch b = batch(level);
        int i = b.add(key, preset, at.x, at.y, at.z, velocity.x, velocity.y, velocity.z,
                shooter == null ? -1 : shooter.getId(), faction, damageScale, rewindTicks, shooterSeq);
        RoundNetwork.sync(level, b, i);
        return key;
    }

    public static int count(ServerLevel level) {
        return batch(level).size;
    }

    /** Position of a round in flight; null once it has ended. */
    @Nullable
    public static Vec3 position(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i < 0 ? null : new Vec3(b.x[i], b.y[i], b.z[i]);
    }

    @Nullable
    public static KineticPreset preset(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i < 0 ? null : b.preset[i];
    }

    /** Ticks of life left; -1 once ended. */
    public static int remainingLife(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i < 0 ? -1 : b.life[i];
    }

    /** Chunks (packed) whose tickets the round holds; empty for none or ended. */
    public static long[] heldChunks(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i < 0 ? RoundBatch.NO_CHUNKS : b.chunks[i].clone();
    }

    @Nullable
    public static Vec3 velocity(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i < 0 ? null : new Vec3(b.vx[i], b.vy[i], b.vz[i]);
    }

    /** Shooter of a round in flight, if still loaded; null once ended. */
    @Nullable
    public static Entity shooter(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i < 0 || b.shooter[i] < 0 ? null : level.getEntity(b.shooter[i]);
    }

    /** Rounds in {@code box}: keys. */
    public static long[] within(ServerLevel level, AABB box) {
        RoundBatch b = batch(level);
        long[] found = new long[b.size];
        int n = 0;
        for (int i = 0; i < b.size; i++) {
            if (box.contains(b.x[i], b.y[i], b.z[i])) {
                found[n++] = b.key[i];
            }
        }
        return java.util.Arrays.copyOf(found, n);
    }

    /** Advance one round one tick now (tests, deterministic stepping). @return false once it has ended */
    public static boolean step(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i >= 0 && step(level, b, i) && b.indexOf(key) >= 0;
    }

    /** Destroy a round in flight (active protection): no warhead. @return false if it had already ended */
    public static boolean destroy(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        if (i < 0) {
            return false;
        }
        end(level, b, i, RoundEnd.DESTROYED);
        return true;
    }

    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "round");
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public CompoundTag save(RoundBatch record) {
        throw new UnsupportedOperationException("transient");
    }

    @Override
    public RoundBatch load(CompoundTag tag) {
        throw new UnsupportedOperationException("transient");
    }

    @Override
    public void resolve(ServerLevel level, SimTier<RoundBatch> tier) {
        VirtualGroundEvents.drain(level);
        DeferredImpacts.get(level).apply(level);
        if (tier.view().isEmpty()) {
            return;
        }
        RoundBatch b = tier.view().get(0);
        long t0 = System.nanoTime();
        for (int i = 0; i < b.size; ) {
            if (!step(level, b, i)) {
                continue;
            }
            if (b.dark[i] && !watched(level, b, i)) {
                long key = b.key[i];
                for (int n = 1; n < MAX_SUBSTEPS && b.dark[i] && darkAhead(level, b, i); n++) {
                    if (!step(level, b, i)) {
                        break;
                    }
                }
                if (i >= b.size || b.key[i] != key) {
                    continue;
                }
            }
            i++;
        }
        b.resolveNanos = System.nanoTime() - t0;
        RoundNetwork.flush(level);
    }

    /** Wall time of the last tick's round steps in {@code level}, ns. */
    public static long lastResolveNanos(ServerLevel level) {
        return batch(level).resolveNanos;
    }

    /** Falling this far below the build range => expired (vanilla {@code Entity.checkBelowWorld} depth). */
    public static final int VOID_DEPTH = 64;

    /** @return false if round {@code i} ended (the next round now sits at {@code i}) */
    private static boolean step(ServerLevel level, RoundBatch b, int i) {
        KineticPreset preset = b.preset[i];
        b.dark[i] = false;
        if (--b.life[i] <= 0 || b.y[i] < level.getMinBuildHeight() - VOID_DEPTH && b.vy[i] <= 0.0) {
            end(level, b, i, RoundEnd.EXPIRED);
            return false;
        }
        if (preset.loadsChunks()) {
            if (ground(level, b, i) == Ground.WAIT) {
                return true;
            }
        } else if (b.rest[i] >= 0 && !loaded(level, b.x[i], b.z[i])) {
            Vec3 at = new Vec3(b.x[i], b.y[i], b.z[i]);
            defer(level, b, i, true, at, Vec3.ZERO, BlockPos.containing(at), Direction.UP);
            end(level, b, i, RoundEnd.DEFERRED);
            return false;
        }
        Entity shooter = b.shooter[i] < 0 ? null : level.getEntity(b.shooter[i]);
        if (b.rest[i] >= 0) {
            return rest(level, b, i, shooter);
        }
        long key = b.key[i];
        Vec3 from = new Vec3(b.x[i], b.y[i], b.z[i]);
        Vec3 to = from.add(b.vx[i], b.vy[i], b.vz[i]);
        int span = preset.loadsChunks() ? LOADED : span(level, from, to);
        // Undecoded column ahead: fly the decoded prefix now (targets short of it), wait at its edge.
        boolean partial = span == PENDING;
        if (partial) {
            double t = decodedPrefix(level, from, to);
            if (t <= 0.0) {
                return true;
            }
            to = from.lerp(to, t);
            span = span(level, from, to);
        }
        AsyncTerrainSource terrain = span == LOADED ? null : AsyncTerrainSource.of(level);
        int sweep = span;
        int wet = fluid(level, terrain, b.x[i], b.y[i], b.z[i]);
        if (wet < 0) {
            return true;
        }
        Entity shooterRoot = shooter == null ? null : shooter.getRootVehicle();
        Predicate<Entity> canHit = !preset.entityContact() ? e -> false
                : e -> e.isAlive() && !e.isSpectator() && e.canBeHitByProjectile()
                && (shooterRoot == null || e.getRootVehicle() != shooterRoot);
        long when = b.rewind[i] == 0 ? RoundDamageSource.LIVE : EntityHistory.broadcastTick(level) - b.rewind[i];
        Vec3 velocity = new Vec3(b.vx[i], b.vy[i], b.vz[i]);
        List<Entity> struck = null;
        long seed = b.seq[i] != NO_SEQ ? BlockPen.shot(b.shooter[i], b.seq[i]) : key;
        int pierced = 0;
        while (true) {
            BlockHitResult block = clip(level, terrain, from, to);
            if (block == PENDING_HIT) {
                if (pierced == 0) {
                    return true;
                }
                // TODO: deflected remainder over a column still decoding flies as air.
                break;
            }
            Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
            List<Entity> done = struck;
            List<EntityHit> hits = sweep == DARK ? List.of() : entityHits(level, from, end,
                    done == null ? canHit : e -> canHit.test(e) && !done.contains(e), when);
            if (sweep != DARK && !rewindWholeFlight) {
                // Spent by a step that swept entities: a retried (decoding) or dark step keeps it.
                b.rewind[i] = 0;
            }
            for (EntityHit eh : hits) {
                ProjectileStrikeEvent strike = strike(level, preset, null, key, eh, from, end, velocity,
                        shooter, b.faction[i], b.damageScale[i]);
                // Listeners may end this round (destroy => swap-remove); keys are unique.
                if (i >= b.size || b.key[i] != key) {
                    return false;
                }
                if (strike.outcome() == ProjectileStrikeEvent.Outcome.DUD) {
                    end(level, b, i, eh.at, RoundEnd.DUD);
                    return false;
                }
                if (strike.outcome() == ProjectileStrikeEvent.Outcome.PROCEED && preset.detonateOnEntity()) {
                    detonate(level, b, i, strike.detonation() != null ? strike.detonation() : eh.at, shooter,
                            RoundEnd.ENTITY);
                    return false;
                }
                if (struck == null) {
                    struck = new ArrayList<>(2);
                }
                struck.add(eh.entity);
            }
            if (block.getType() == HitResult.Type.MISS) {
                break;
            }
            BlockPos hitPos = block.getBlockPos();
            boolean unloaded = !loaded(level, hitPos.getX(), hitPos.getZ());
            if (!unloaded && drill(level, b, i, block)) {
                from = block.getLocation();
                continue;
            }
            BlockPen.Pass pass = preset.blockPen() > 0.0f && pierced < BlockPen.MAX_PER_STEP
                    ? pierce(level, terrain, preset, block, velocity, seed) : null;
            if (pass != null) {
                NeoForge.EVENT_BUS.post(new RoundPierceEvent(level, preset, key, block, pass, velocity));
                if (!unloaded && preset.hasEffects(ImpactTrigger.PIERCE_IN)) {
                    ImpactEffects.fire(new ImpactContext(level, ImpactTrigger.PIERCE_IN, preset, key, null, shooter,
                            b.faction[i], block.getLocation(), velocity, block, null, null, pass, null));
                }
                if (!unloaded && pass.exited() && preset.hasEffects(ImpactTrigger.PIERCE_OUT)) {
                    ImpactEffects.fire(new ImpactContext(level, ImpactTrigger.PIERCE_OUT, preset, key, null, shooter,
                            b.faction[i], pass.point(), pass.velocity(), block, null, null, pass, null));
                }
                if (i >= b.size || b.key[i] != key) {
                    return false;
                }
                RoundNetwork.pierced(level, b, i, block, pass);
            }
            if (pass != null && pass.exited()) {
                double left = Math.max(0.0, from.distanceTo(to) - from.distanceTo(pass.point())) / velocity.length();
                velocity = pass.velocity();
                from = BlockPen.resume(pass.point(), velocity);
                to = from.add(velocity.scale(left));
                pierced++;
                sweep = span(level, from, to);
                if (sweep != LOADED && terrain == null) {
                    terrain = AsyncTerrainSource.of(level);
                }
                continue;
            }
            Vec3 at = pass == null ? block.getLocation() : pass.point();
            if (unloaded) {
                defer(level, b, i, false, at, velocity, hitPos, block.getDirection());
                end(level, b, i, at, RoundEnd.BLOCK, true);
                return false;
            }
            NeoForge.EVENT_BUS.post(new RoundImpactEvent(level, preset, block, velocity));
            if (preset.hasEffects(ImpactTrigger.BLOCK)) {
                ImpactEffects.fire(new ImpactContext(level, ImpactTrigger.BLOCK, preset, key, null, shooter,
                        b.faction[i], at, velocity, block, null, null, pass, null));
            }
            if (i >= b.size || b.key[i] != key) {
                return false;
            }
            if (preset.fuseDelay() > 0 || preset.burrow() > 0.0f) {
                land(level, b, i, at);
                return true;
            }
            detonate(level, b, i, at, shooter, RoundEnd.BLOCK);
            return false;
        }
        if (fuseTripped(level, terrain, preset, new Vec3(b.x[i], b.y[i], b.z[i]),
                new Vec3(b.vx[i], b.vy[i], b.vz[i]), canHit)) {
            detonate(level, b, i, new Vec3(b.x[i], b.y[i], b.z[i]), shooter, RoundEnd.FUSE);
            return false;
        }
        if (pierced > 0 || partial) {
            b.x[i] = to.x;
            b.y[i] = to.y;
            b.z[i] = to.z;
            b.vx[i] = velocity.x;
            b.vy[i] = velocity.y;
            b.vz[i] = velocity.z;
            if (partial) {
                // Sub-tick advance: no decay/gravity; the client learns the delay from the resync.
                RoundNetwork.sync(level, b, i);
                return true;
            }
        } else {
            b.x[i] += b.vx[i];
            b.y[i] += b.vy[i];
            b.z[i] += b.vz[i];
        }
        double decay = preset.decay(Math.sqrt(b.vx[i] * b.vx[i] + b.vy[i] * b.vy[i] + b.vz[i] * b.vz[i]));
        float gravity = preset.gravity();
        if (wet != 0) {
            decay = (double) (1.0f - Math.max(preset.waterDrag(), 0.0f));
            gravity *= preset.waterGravityFactor();
        }
        b.vx[i] = b.vx[i] * decay;
        b.vy[i] = b.vy[i] * decay - gravity;
        b.vz[i] = b.vz[i] * decay;
        b.dark[i] = sweep == DARK;
        if (pierced > 0) {
            RoundNetwork.sync(level, b, i);
        }
        return true;
    }

    /** {@link KineticPreset#blockPen} through the struck voxel: loaded state, else its on-disk solidity. */
    private static BlockPen.Pass pierce(ServerLevel level, @Nullable AsyncTerrainSource terrain, KineticPreset preset,
                                        BlockHitResult hit, Vec3 velocity, long seed) {
        BlockPos pos = hit.getBlockPos();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        VoxelShape shape;
        float resistance;
        if (chunk != null) {
            BlockState state = chunk.getBlockState(pos);
            shape = state.getCollisionShape(level, pos);
            resistance = PenTable.resistance(state);
        } else {
            SectionSolidity section = terrain.column(pos.getX() >> 4, pos.getZ() >> 4)[(pos.getY() >> 4)
                    - terrain.minSection()];
            int index = SectionSolidity.index(pos.getX(), pos.getY(), pos.getZ());
            BlockState partial = section.partial(index);
            shape = partial == null ? Shapes.block() : partial.getCollisionShape(EmptyBlockGetter.INSTANCE, pos);
            resistance = partial == null ? section.resistance(index) : PenTable.resistance(partial);
        }
        return BlockPen.pass(shape, pos, hit.getLocation(), velocity, resistance,
                BlockPen.capacity(preset.blockPen(), preset.muzzleSpeed(), velocity.length()),
                BlockPen.seed(seed, pos));
    }

    private static void land(ServerLevel level, RoundBatch b, int i, Vec3 at) {
        b.x[i] = at.x;
        b.y[i] = at.y;
        b.z[i] = at.z;
        b.vx[i] = 0.0;
        b.vy[i] = 0.0;
        b.vz[i] = 0.0;
        b.rest[i] = b.preset[i].fuseDelay();
        b.burrow[i] = b.preset[i].burrow();
        RoundNetwork.sync(level, b, i);
    }

    /** Landed: burrow, then the fuse. @return false once ended */
    private static boolean rest(ServerLevel level, RoundBatch b, int i, @Nullable Entity shooter) {
        if (b.burrow[i] > 0.0f) {
            BlockPos below = BlockPos.containing(b.x[i], b.y[i], b.z[i]).below();
            BlockState state = level.getBlockState(below);
            float cost = Math.max(0.1f, state.getDestroySpeed(level, below));
            if (below.getY() <= level.getMinBuildHeight() || state.getDestroySpeed(level, below) < 0.0f
                    || !state.isAir() && cost > b.burrow[i]) {
                b.burrow[i] = 0.0f;
                return true;
            }
            if (!state.isAir()) {
                b.burrow[i] -= cost;
                level.destroyBlock(below, false, shooter);
            }
            b.y[i] = below.getY();
            RoundNetwork.sync(level, b, i);
            return true;
        }
        if (b.rest[i]-- > 0) {
            return true;
        }
        detonate(level, b, i, new Vec3(b.x[i], b.y[i], b.z[i]), shooter, RoundEnd.FUSE);
        return false;
    }

    private enum Ground { FLY, WAIT }

    /**
     * Chunk-loading round, this tick's segment: every column loaded or wholly above the build limit => fly; else
     * tickets on the segment's columns (loaded ones kept: a released one may unload => never all loaded), others
     * released, and wait in place (life refunded). Never a synchronous load.
     */
    private static Ground ground(ServerLevel level, RoundBatch b, int i) {
        double ex = b.x[i] + b.vx[i];
        double ey = b.y[i] + b.vy[i];
        double ez = b.z[i] + b.vz[i];
        if (ey >= level.getMaxBuildHeight() && b.y[i] >= level.getMaxBuildHeight()) {
            return Ground.FLY;
        }
        LongArrayList segment = walk(b.x[i], b.z[i], ex, ez);
        boolean all = true;
        for (int k = 0; k < segment.size() && all; k++) {
            long c = segment.getLong(k);
            all = level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) != null;
        }
        if (all) {
            return Ground.FLY;
        }
        UUID owner = ticket(b.key[i]);
        for (long c : b.chunks[i]) {
            if (!segment.contains(c)) {
                DetonationChunkGuard.CONTROLLER.forceChunk(level, owner, ChunkPos.getX(c), ChunkPos.getZ(c), false,
                        true);
            }
        }
        long[] held = segment.toLongArray();
        for (long c : held) {
            if (!contains(b.chunks[i], c)) {
                DetonationChunkGuard.CONTROLLER.forceChunk(level, owner, ChunkPos.getX(c), ChunkPos.getZ(c), true,
                        true);
            }
        }
        b.chunks[i] = held;
        b.life[i]++;
        return Ground.WAIT;
    }

    private static boolean contains(long[] xs, long x) {
        for (long v : xs) {
            if (v == x) {
                return true;
            }
        }
        return false;
    }

    /** {@link #span}: every column under the segment loaded. */
    private static final int LOADED = 0;
    /** Some loaded, some flown on disk terrain. */
    private static final int MIXED = 1;
    /** None loaded within {@link #ENTITY_REACH}: no entity can be struck. */
    private static final int DARK = 2;
    /** An unloaded column in the build range is still decoding: fly up to it, then wait (life spent). */
    private static final int PENDING = 3;
    /** {@link #columns} flags. */
    private static final int SOME_LOADED = 1;
    private static final int SOME_UNLOADED = 2;
    private static final int UNDECODED = 4;
    /** Clip sentinel: a voxel's column is still decoding. */
    private static final BlockHitResult PENDING_HIT = BlockHitResult.miss(Vec3.ZERO, Direction.DOWN, BlockPos.ZERO);

    /**
     * Non-chunk-loading round's segment over chunk columns; requests undecoded ones, plus {@link #LOOKAHEAD} ticks of
     * straight travel beyond once off loaded ground.
     */
    private static int span(ServerLevel level, Vec3 from, Vec3 to) {
        boolean ground = Math.min(from.y, to.y) < level.getMaxBuildHeight()
                && Math.max(from.y, to.y) >= level.getMinBuildHeight();
        int flags = columns(level, ground ? AsyncTerrainSource.of(level) : null, from.x, from.z, to.x, to.z);
        double ax = to.x + (to.x - from.x) * LOOKAHEAD;
        double az = to.z + (to.z - from.z) * LOOKAHEAD;
        if (flags == SOME_LOADED) {
            if (!loaded(level, ax, az)) {
                columns(level, AsyncTerrainSource.of(level), to.x, to.z, ax, az);
            }
            return LOADED;
        }
        columns(level, AsyncTerrainSource.of(level), to.x, to.z, ax, az);
        return (flags & UNDECODED) != 0 ? PENDING
                : (flags & SOME_LOADED) != 0 || loadedNear(level, from, to) ? MIXED : DARK;
    }

    /** Pullback off the first undecoded column's face, fraction of the segment: its voxels stay out of the clip. */
    private static final double PREFIX_EPSILON = 1.0e-6;

    /**
     * Fraction of {@code from -> to} before its first undecoded column (XZ entry), less {@link #PREFIX_EPSILON};
     * 0 => at or in it. Columns already requested by {@link #span}.
     */
    private static double decodedPrefix(ServerLevel level, Vec3 from, Vec3 to) {
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        LongArrayList crossed = walk(from.x, from.z, to.x, to.z);
        for (int k = 0, n = crossed.size(); k < n; k++) {
            long c = crossed.getLong(k);
            int cx = ChunkPos.getX(c);
            int cz = ChunkPos.getZ(c);
            if (level.getChunkSource().getChunkNow(cx, cz) == null && terrain.peek(cx, cz) == null) {
                double tx = entry(from.x, to.x, cx << 4);
                double tz = entry(from.z, to.z, cz << 4);
                double t = Math.max(tx, tz);
                // Already pulled back to the face (waiting): no hair-width advance + resync every tick.
                return tx < 0.0 || tz < 0.0 || t <= 2.0 * PREFIX_EPSILON ? 0.0 : t - PREFIX_EPSILON;
            }
        }
        return 1.0;
    }

    /** Fraction where {@code a0 -> a1} enters {@code [lo, lo + 16)}; 0 if it starts inside, -1 never. */
    private static double entry(double a0, double a1, int lo) {
        int hi = lo + 16;
        if (a0 >= lo && a0 < hi) {
            return 0.0;
        }
        double d = a1 - a0;
        double t = ((a0 < lo ? lo : hi) - a0) / d;
        return d == 0.0 || t < 0.0 || t > 1.0 ? -1.0 : t;
    }

    /** Next segment of round {@code i} crosses unloaded columns only. */
    private static boolean darkAhead(ServerLevel level, RoundBatch b, int i) {
        return columns(level, null, b.x[i], b.z[i], b.x[i] + b.vx[i], b.z[i] + b.vz[i]) == SOME_UNLOADED;
    }

    /**
     * Chunk columns crossed by {@code (x0,z0) -> (x1,z1)}. {@code terrain != null}: unloaded undecoded =>
     * {@link #UNDECODED}, requested.
     */
    private static int columns(ServerLevel level, @Nullable AsyncTerrainSource terrain, double x0, double z0,
                               double x1, double z1) {
        LongArrayList crossed = walk(x0, z0, x1, z1);
        int flags = 0;
        for (int k = 0, n = crossed.size(); k < n; k++) {
            long c = crossed.getLong(k);
            flags |= column(level, terrain, ChunkPos.getX(c), ChunkPos.getZ(c));
        }
        return flags;
    }

    /** Chunk columns (packed) crossed by {@code (x0,z0) -> (x1,z1)}, both at a corner tie, in order; scratch list. */
    private static LongArrayList walk(double x0, double z0, double x1, double z1) {
        LongArrayList out = WALK;
        out.clear();
        int cx = Mth.floor(x0) >> 4;
        int cz = Mth.floor(z0) >> 4;
        int ex = Mth.floor(x1) >> 4;
        int ez = Mth.floor(z1) >> 4;
        out.add(ChunkPos.asLong(cx, cz));
        double dx = x1 - x0;
        double dz = z1 - z0;
        int sx = dx > 0 ? 1 : -1;
        int sz = dz > 0 ? 1 : -1;
        double tMaxX = cx == ex ? Double.POSITIVE_INFINITY : (((sx > 0 ? cx + 1 : cx) << 4) - x0) / dx;
        double tMaxZ = cz == ez ? Double.POSITIVE_INFINITY : (((sz > 0 ? cz + 1 : cz) << 4) - z0) / dz;
        double tDx = Math.abs(16.0 / dx);
        double tDz = Math.abs(16.0 / dz);
        for (int left = Math.abs(ex - cx) + Math.abs(ez - cz); left > 0; left--) {
            if (tMaxX < tMaxZ) {
                cx += sx;
                tMaxX = cx == ex ? Double.POSITIVE_INFINITY : tMaxX + tDx;
            } else if (tMaxZ < tMaxX) {
                cz += sz;
                tMaxZ = cz == ez ? Double.POSITIVE_INFINITY : tMaxZ + tDz;
            } else {
                out.add(ChunkPos.asLong(cx + sx, cz));
                out.add(ChunkPos.asLong(cx, cz + sz));
                cx += sx;
                cz += sz;
                tMaxX = cx == ex ? Double.POSITIVE_INFINITY : tMaxX + tDx;
                tMaxZ = cz == ez ? Double.POSITIVE_INFINITY : tMaxZ + tDz;
                left--;
            }
            out.add(ChunkPos.asLong(cx, cz));
        }
        return out;
    }

    /** Some loaded column within {@link #ENTITY_REACH} of the segment's XZ box: an entity sweep can find something. */
    private static boolean loadedNear(ServerLevel level, Vec3 from, Vec3 to) {
        int x0 = Mth.floor(Math.min(from.x, to.x) - ENTITY_REACH) >> 4;
        int x1 = Mth.floor(Math.max(from.x, to.x) + ENTITY_REACH) >> 4;
        int z0 = Mth.floor(Math.min(from.z, to.z) - ENTITY_REACH) >> 4;
        int z1 = Mth.floor(Math.max(from.z, to.z) + ENTITY_REACH) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int column(ServerLevel level, @Nullable AsyncTerrainSource terrain, int cx, int cz) {
        if (level.getChunkSource().getChunkNow(cx, cz) != null) {
            return SOME_LOADED;
        }
        return terrain != null && terrain.column(cx, cz) == null ? SOME_UNLOADED | UNDECODED : SOME_UNLOADED;
    }

    private static boolean loaded(ServerLevel level, double x, double z) {
        return level.getChunkSource().getChunkNow(Mth.floor(x) >> 4, Mth.floor(z) >> 4) != null;
    }

    /**
     * Queues on-disk terrain decodes along {@code from -> to} (XZ).
     * @return every unloaded column there decoded
     */
    public static boolean prefetch(ServerLevel level, Vec3 from, Vec3 to) {
        return (columns(level, AsyncTerrainSource.of(level), from.x, from.z, to.x, to.z) & UNDECODED) == 0;
    }

    /** Fluid at the voxel: 1 wet, 0 dry (unloaded without {@code terrain}: dry), -1 column decoding. */
    private static int fluid(ServerLevel level, @Nullable AsyncTerrainSource terrain, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (level.isOutsideBuildHeight(pos)) {
            return 0;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            return chunk.getFluidState(pos).isEmpty() ? 0 : 1;
        }
        if (terrain == null) {
            return 0;
        }
        SectionSolidity[] column = terrain.column(pos.getX() >> 4, pos.getZ() >> 4);
        if (column == null) {
            return -1;
        }
        return column[(pos.getY() >> 4) - terrain.minSection()]
                .wet(SectionSolidity.index(pos.getX(), pos.getY(), pos.getZ())) ? 1 : 0;
    }

    /** Anyone within the audience range of the round, or holding a client copy of it. */
    private static boolean watched(ServerLevel level, RoundBatch b, int i) {
        for (ServerPlayer player : level.players()) {
            if (!(player instanceof FakePlayer) && near(player, b.x[i], b.z[i])) {
                return true;
            }
        }
        for (UUID id : b.viewers[i]) {
            if (level.getPlayerByUUID(id) instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(ServerPlayer player, double x, double z) {
        double dx = player.getX() - x;
        double dz = player.getZ() - z;
        return dx * dx + dz * dz <= AUDIENCE_RANGE * AUDIENCE_RANGE;
    }

    private static void defer(ServerLevel level, RoundBatch b, int i, boolean landed, Vec3 at, Vec3 velocity,
                              BlockPos block, Direction face) {
        Entity shooter = b.shooter[i] < 0 ? null : level.getEntity(b.shooter[i]);
        DeferredImpacts.get(level).add(ChunkPos.asLong(block.getX() >> 4, block.getZ() >> 4),
                new DeferredImpact(landed, b.preset[i].id(), at, velocity, block, face,
                        shooter == null ? null : shooter.getUUID(), b.faction[i], b.rest[i], b.burrow[i]));
    }

    /** Owed impact, its chunk now loaded: impact event then warhead or landing, as a loaded-ground hit. */
    static void applyDeferred(ServerLevel level, DeferredImpact d) {
        KineticPreset preset = KineticPresetRegistry.get(d.preset());
        if (preset == null) {
            LOGGER.warn("Deferred impact of unknown round {} at {} dropped", d.preset(), d.at());
            return;
        }
        Entity shooter = d.shooter() == null ? null : level.getEntity(d.shooter());
        int fuse = d.fuse();
        float burrow = d.burrow();
        if (!d.landed()) {
            BlockHitResult hit = new BlockHitResult(d.at(), d.face(), d.block(), false);
            NeoForge.EVENT_BUS.post(new RoundImpactEvent(level, preset, hit, d.velocity()));
            if (preset.hasEffects(ImpactTrigger.BLOCK)) {
                ImpactEffects.fire(new ImpactContext(level, ImpactTrigger.BLOCK, preset, -1L, null, shooter,
                        d.faction(), d.at(), d.velocity(), hit, null, null, null, null));
            }
            if (preset.fuseDelay() <= 0 && preset.burrow() <= 0.0f) {
                Vec3 v = d.velocity();
                Vec3 angle = v.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : v.normalize();
                if (preset.hasEffects(ImpactTrigger.END)) {
                    ImpactEffects.fire(new ImpactContext(level, ImpactTrigger.END, preset, -1L, null, shooter,
                            d.faction(), d.at(), v, null, null, null, null, RoundEnd.BLOCK));
                }
                WarheadRegistry.get(preset.warheadId()).detonate(
                        new RoundCarrier(level, preset, angle, shooter, d.faction()), d.at());
                return;
            }
            fuse = preset.fuseDelay();
            burrow = preset.burrow();
        }
        RoundBatch b = batch(level);
        int i = b.add(nextKey++, preset, d.at().x, d.at().y, d.at().z, 0.0, 0.0, 0.0,
                shooter == null ? -1 : shooter.getId(), d.faction(), 1.0f, 0, NO_SEQ);
        b.rest[i] = fuse;
        b.burrow[i] = burrow;
        RoundNetwork.sync(level, b, i);
    }

    private static void release(ServerLevel level, RoundBatch b, int i) {
        for (long c : b.chunks[i]) {
            DetonationChunkGuard.CONTROLLER.forceChunk(level, ticket(b.key[i]), ChunkPos.getX(c), ChunkPos.getZ(c),
                    false, true);
        }
        b.chunks[i] = RoundBatch.NO_CHUNKS;
    }

    private static UUID ticket(long key) {
        return new UUID(TICKET_HIGH, key);
    }

    private static void end(ServerLevel level, RoundBatch b, int i, RoundEnd reason) {
        end(level, b, i, new Vec3(b.x[i], b.y[i], b.z[i]), reason, reason == RoundEnd.DEFERRED);
    }

    private static void end(ServerLevel level, RoundBatch b, int i, Vec3 at, RoundEnd reason) {
        end(level, b, i, at, reason, false);
    }

    /**
     * Removes round {@code i}, then its END effects: none if {@code owed} (a {@link DeferredImpact} ends it) or
     * {@code at} unloaded (an effect there loads the chunk on the tick thread).
     */
    private static void end(ServerLevel level, RoundBatch b, int i, Vec3 at, RoundEnd reason, boolean owed) {
        release(level, b, i);
        RoundNetwork.ended(level, b, i, at, reason);
        KineticPreset preset = b.preset[i];
        if (owed || !preset.hasEffects(ImpactTrigger.END) || !loaded(level, at.x, at.z)) {
            b.remove(i);
            return;
        }
        ImpactContext ctx = new ImpactContext(level, ImpactTrigger.END, preset, b.key[i], null,
                b.shooter[i] < 0 ? null : level.getEntity(b.shooter[i]), b.faction[i], at,
                new Vec3(b.vx[i], b.vy[i], b.vz[i]), null, null, null, null, reason);
        b.remove(i);
        ImpactEffects.fire(ctx);
    }

    private static void detonate(ServerLevel level, RoundBatch b, int i, Vec3 at, @Nullable Entity shooter,
                                 RoundEnd reason) {
        KineticPreset preset = b.preset[i];
        Vec3 v = new Vec3(b.vx[i], b.vy[i], b.vz[i]);
        Vec3 angle = v.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : v.normalize();
        UUID faction = b.faction[i];
        end(level, b, i, at, reason);
        WarheadRegistry.get(preset.warheadId()).detonate(new RoundCarrier(level, preset, angle, shooter, faction), at);
    }

    /** Strike event, then the direct hit when it proceeds; the preset's pierce rides on the source. */
    static ProjectileStrikeEvent strike(ServerLevel level, KineticPreset preset, @Nullable Entity projectile,
                                        Object key, EntityHit eh, Vec3 from, Vec3 segmentEnd, Vec3 velocity,
                                        @Nullable Entity shooter, @Nullable UUID faction, float damageScale) {
        Threat threat = threat(preset, velocity);
        ImpactContext ctx = null;
        List<ImpactEffect> effects = List.of();
        if (preset.hasEffects(ImpactTrigger.HIT)) {
            ctx = new ImpactContext(level, ImpactTrigger.HIT, preset, key, projectile, shooter, faction, eh.at, velocity,
                    null, eh.entity, eh.parts, null, null);
            effects = ImpactEffects.roll(preset, ImpactTrigger.HIT, level.random);
            for (ImpactEffect e : effects) {
                threat = e.threat(ctx, threat);
            }
        }
        ProjectileStrikeEvent strike = NeoForge.EVENT_BUS.post(new ProjectileStrikeEvent(projectile, key, eh.entity,
                eh.at, velocity, threat, eh.parts));
        if (strike.outcome() != ProjectileStrikeEvent.Outcome.PROCEED) {
            return strike;
        }
        RoundDamageSource source = new RoundDamageSource(damageType(level), projectile, shooter, threat, from,
                velocity, key, segmentEnd, eh.when, eh.parts, preset.pierceDT(), preset.pierceDR());
        float damage = impactDamage(preset, velocity) * damageScale;
        // One hit per round: i-frames from anything else must not swallow it (MG streams).
        if (eh.entity instanceof LivingEntity living) {
            WFDamage.hurtIgnoringIFrames(living, source, damage * headshotFactor(preset, living, eh));
        } else {
            eh.entity.hurt(source, damage);
        }
        for (ImpactEffect e : effects) {
            e.apply(ctx);
        }
        return strike;
    }

    /** Claimed body => 1 (its owner scales by part); part => HEAD; no part => eye height +- 0.25 at the swept tick. */
    static float headshotFactor(KineticPreset preset, LivingEntity victim, EntityHit eh) {
        if (ArmorSystem.resolutionClaimed(victim)) {
            return 1.0f;
        }
        if (eh.parts != null) {
            return eh.parts.get(0) == HitboxPart.HEAD ? preset.headshot() : 1.0f;
        }
        double fromEye = eh.at.y - eh.feetY - victim.getEyeHeight();
        return Math.abs(fromEye) < HEAD_HALF_HEIGHT ? preset.headshot() : 1.0f;
    }

    /** Drill: each block with {@link PenTable} resistance <= the preset's costs one penetration and is destroyed. */
    private static boolean drill(ServerLevel level, RoundBatch b, int i, BlockHitResult hit) {
        if (b.pen[i] <= 0) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || PenTable.resistance(state) > b.preset[i].penetrationResistance()) {
            return false;
        }
        b.pen[i]--;
        level.destroyBlock(pos, false);
        return true;
    }

    static boolean fuseTripped(ServerLevel level, KineticPreset preset, Vec3 at, Vec3 v, Predicate<Entity> canHit) {
        return fuseTripped(level, null, preset, at, v, canHit);
    }

    /** Airburst over unloaded ground reads {@code terrain}; a column still decoding does not trip. */
    private static boolean fuseTripped(ServerLevel level, @Nullable AsyncTerrainSource terrain, KineticPreset preset,
                                       Vec3 at, Vec3 v, Predicate<Entity> canHit) {
        double proximity = preset.proximityRadius();
        if (proximity > 0.0) {
            for (Entity e : level.getEntities((Entity) null, new AABB(at, at).inflate(proximity), canHit)) {
                if (e.distanceToSqr(at) <= proximity * proximity) {
                    return true;
                }
            }
        }
        double airburst = preset.airburstHeight();
        if (airburst > 0.0 && v.y < 0.0) {
            Vec3 down = at.subtract(0.0, airburst + v.length(), 0.0);
            if (level.hasChunkAt(BlockPos.containing(at))) {
                BlockHitResult below = level.clip(new ClipContext(at, down, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, CollisionContext.empty()));
                return below.getType() == HitResult.Type.BLOCK;
            }
            if (terrain != null) {
                BlockHitResult below = clip(level, terrain, at, down);
                return below != PENDING_HIT && below.getType() == HitResult.Type.BLOCK;
            }
        }
        return false;
    }

    /**
     * Entities on {@code from -> to}, nearest first. Shaped entities by their {@link PreciseHitbox}; living by AHF
     * ({@link #hitLiving}); the rest AABB + 0.3. {@code when != LIVE}: living, riding nothing, with an AHF snapshot
     * at {@code when} => as broadcast then (history broadphase); everything else live, stamped {@code LIVE}.
     */
    static List<EntityHit> entityHits(ServerLevel level, Vec3 from, Vec3 to, Predicate<Entity> canHit, long when) {
        List<EntityHit> out = null;
        boolean rewound = when != RoundDamageSource.LIVE;
        if (rewound) {
            EntityHistory.candidates(level, when, from, to, PICK_INFLATE, CANDIDATES);
            try {
                for (int k = 0, n = CANDIDATES.size(); k < n; k++) {
                    LivingEntity e = CANDIDATES.get(k);
                    if (rewindable(e) && canHit.test(e)) {
                        out = add(out, hitLiving(e, from, to, EntityHistory.snapshot(e, when), when));
                    }
                }
            } finally {
                CANDIDATES.clear();
            }
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(from, to).inflate(1.0), canHit)) {
            if (e instanceof PreciseHitbox shape) {
                Vec3 at = shape.clip(from, to);
                out = add(out, at == null ? null : new EntityHit(e, at, from, null, e.getY(), RoundDamageSource.LIVE));
            } else if (e instanceof LivingEntity living) {
                if (!rewound || !rewindable(living) || !EntityHistory.has(living, when)) {
                    out = add(out, hitLiving(living, from, to, null, RoundDamageSource.LIVE));
                }
            } else {
                out = add(out, boxHit(e, e.getBoundingBox(), from, to, e.getY(), RoundDamageSource.LIVE));
            }
        }
        if (out == null) {
            return List.of();
        }
        if (out.size() > 1) {
            out.sort(NEAREST);
        }
        return out;
    }

    /** Vehicle passengers: rewind 0 (the hull is live). */
    private static boolean rewindable(LivingEntity e) {
        return !(e instanceof PreciseHitbox) && !e.isPassenger();
    }

    private static List<EntityHit> add(@Nullable List<EntityHit> out, @Nullable EntityHit hit) {
        if (hit == null) {
            return out;
        }
        if (out == null) {
            out = new ArrayList<>(2);
        }
        out.add(hit);
        return out;
    }

    /**
     * AHF segment classification against {@code s} (null = live): parts, entry point. No part: a player whose rig AHF
     * used => miss (rig is the authority, PRECISE gap rule included); else AABB + 0.3 without parts (wide mobs, lying
     * bodies). Segment clear of registration box and AABB, both + 0.3 => miss before AHF.
     */
    @Nullable
    private static EntityHit hitLiving(LivingEntity e, Vec3 from, Vec3 to, @Nullable Snapshot s, long when) {
        AABB box = s != null ? s.box() : e.getBoundingBox();
        if (!EntityHistory.segmentHits(box, PICK_INFLATE, from, to) && !EntityHistory.segmentHits(
                s != null ? s.envelope() : HitRegistration.registrationBox(e, box), PICK_INFLATE, from, to)) {
            return null;
        }
        List<RayHit> rays;
        if (AhfConfig.penetrationEnabled()) {
            rays = s != null ? HitboxApi.classifyRayPierced(e, from, to, s) : HitboxApi.classifyRayPierced(e, from, to);
        } else {
            RayHit r = s != null ? HitboxApi.classifyRay(e, from, to, s) : HitboxApi.classifyRay(e, from, to);
            rays = r == null ? List.of() : List.of(r);
        }
        double feetY = s != null ? s.pos().y : e.getY();
        if (!rays.isEmpty()) {
            HitboxPart[] parts = new HitboxPart[rays.size()];
            for (int k = 0; k < parts.length; k++) {
                parts[k] = rays.get(k).part();
            }
            return new EntityHit(e, rays.get(0).point(from, to), from, List.of(parts), feetY, when);
        }
        boolean rig = s != null ? s.rig() != null : AhfConfig.riggedLimbBoxes() && HitboxApi.rigUsable(e);
        if (e instanceof Player && rig) {
            return null;
        }
        return boxHit(e, box, from, to, feetY, when);
    }

    @Nullable
    private static EntityHit boxHit(Entity e, AABB bounds, Vec3 from, Vec3 to, double feetY, long when) {
        AABB box = bounds.inflate(PICK_INFLATE);
        Vec3 at = box.contains(from) ? from : box.clip(from, to).orElse(null);
        return at == null ? null : new EntityHit(e, at, from, null, feetY, when);
    }

    /** {@code Level.clip} (collider shapes, no fluid) reading loaded chunks only; unloaded = air. */
    static BlockHitResult clipLoaded(ServerLevel level, Vec3 from, Vec3 to) {
        return clip(level, null, from, to);
    }

    /**
     * {@link #clipLoaded}, unloaded voxels from {@code terrain} (solid = unit cube, partial = its state's collision
     * shape); {@link #PENDING_HIT} on a column still decoding.
     */
    private static BlockHitResult clip(ServerLevel level, @Nullable AsyncTerrainSource terrain, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty());
        DiskCursor disk = terrain == null ? null : new DiskCursor(terrain, level.getSectionsCount());
        return BlockGetter.traverseBlocks(from, to, ctx, (c, pos) -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                return disk == null ? null : disk.clip(c.getFrom(), c.getTo(), pos);
            }
            BlockState state = chunk.getBlockState(pos);
            return level.clipWithInteractionOverride(c.getFrom(), c.getTo(), pos, c.getBlockShape(state, level, pos),
                    state);
        }, c -> {
            Vec3 d = c.getFrom().subtract(c.getTo());
            return BlockHitResult.miss(c.getTo(), Direction.getNearest(d.x, d.y, d.z), BlockPos.containing(c.getTo()));
        });
    }

    /** One clip's walk over decoded columns; last column cached. */
    private static final class DiskCursor {
        private final AsyncTerrainSource terrain;
        private final int sections;
        private long chunk = Long.MAX_VALUE;
        private SectionSolidity[] column;

        DiskCursor(AsyncTerrainSource terrain, int sections) {
            this.terrain = terrain;
            this.sections = sections;
        }

        @Nullable
        BlockHitResult clip(Vec3 from, Vec3 to, BlockPos pos) {
            int slot = (pos.getY() >> 4) - terrain.minSection();
            if (slot < 0 || slot >= sections) {
                return null;
            }
            long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (key != chunk || column == null) {
                column = terrain.column(pos.getX() >> 4, pos.getZ() >> 4);
                chunk = key;
                if (column == null) {
                    return PENDING_HIT;
                }
            }
            SectionSolidity section = column[slot];
            int index = SectionSolidity.index(pos.getX(), pos.getY(), pos.getZ());
            if (section.solid(index)) {
                return Shapes.block().clip(from, to, pos);
            }
            BlockState partial = section.partial(index);
            return partial == null ? null
                    : partial.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).clip(from, to, pos);
        }
    }

    public static Threat threat(KineticPreset preset) {
        return new Threat(kind(preset), preset.caliberMm(), preset.armorPenetrationMm());
    }

    /**
     * {@link #threat(KineticPreset)} at impact: kinetic pen x {@code |v|^2 / muzzleSpeed^2} (as {@code blockPen});
     * shaped charge, HE and motors unscaled.
     */
    public static Threat threat(KineticPreset preset, Vec3 velocity) {
        ThreatKind kind = kind(preset);
        float pen = preset.armorPenetrationMm();
        if (pen > 0.0f && preset.motorAccel() <= 0.0f
                && (kind == ThreatKind.KINETIC || kind == ThreatKind.SMALL_ARMS)) {
            pen = (float) BlockPen.capacity(pen, preset.muzzleSpeed(), velocity.length());
        }
        return new Threat(kind, preset.caliberMm(), pen);
    }

    private static ThreatKind kind(KineticPreset preset) {
        if (preset.threatKind() != null) {
            return preset.threatKind();
        }
        if (SHAPED_CHARGE.equals(preset.warheadId())) {
            return ThreatKind.HEAT;
        }
        if (preset.armorPenetrationMm() > 0.0f || INERT.equals(preset.warheadId())) {
            return ThreatKind.KINETIC;
        }
        return ThreatKind.HE;
    }

    /** Before the launch's damage scale. {@code velocity} blocks/tick. */
    static float impactDamage(KineticPreset preset, Vec3 velocity) {
        if (preset.damagePerKJ() > 0.0f) {
            double mps2 = velocity.lengthSqr() * 400.0;
            return (float) (0.5 * preset.mass() * mps2 / 1000.0 * preset.damagePerKJ());
        }
        return preset.impactDamage() > 0.0f ? preset.impactDamage()
                : (float) (0.5 * preset.mass() * velocity.lengthSqr() * ENERGY_TO_DAMAGE);
    }

    private static Holder<DamageType> damageType(ServerLevel level) {
        return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageClass.PHYSICAL.type);
    }

    /**
     * Entity hit carrying the exact point (vanilla's result stores the entity origin for projectile sweeps), AHF
     * parts, the victim's feet height at the swept tick (eye-band fallback), and that tick ({@code LIVE} = live).
     */
    static final class EntityHit extends net.minecraft.world.phys.EntityHitResult {
        final Entity entity;
        final Vec3 at;
        @Nullable
        final List<HitboxPart> parts;
        final double feetY;
        final long when;
        final double distanceSq;

        EntityHit(Entity entity, Vec3 at, Vec3 from, @Nullable List<HitboxPart> parts, double feetY, long when) {
            super(entity, at);
            this.entity = entity;
            this.at = at;
            this.parts = parts;
            this.feetY = feetY;
            this.when = when;
            this.distanceSq = from.distanceToSqr(at);
        }
    }

    /** Players who should draw a round at {@code (x, z)}. */
    static List<ServerPlayer> audience(ServerLevel level, KineticPreset preset, double x, double z) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (preset.loadsChunks() || near(player, x, z)) {
                out.add(player);
            }
        }
        return out;
    }

    static void send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
