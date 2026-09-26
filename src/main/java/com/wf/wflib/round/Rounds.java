package com.wf.wflib.round;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.PreciseHitbox;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatKind;
import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.damage.DamageClass;
import com.wf.wflib.damage.WFDamage;
import com.wf.wflib.kinetic.KineticPreset;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Unguided rounds without entities (bullets, shells, bombs): one {@link RoundBatch} per level, stepped on the tick
 * thread after the level tick. Flight contract = {@code KineticPreset}: {@code pos += v; v = v * decay - g}, decay
 * in float. Transient: rounds in flight are not saved.
 */
public final class Rounds implements SimKind<RoundBatch> {

    public static final Rounds KIND = new Rounds();

    /** Fraction of kinetic energy landing as damage when the preset names no flat figure. */
    private static final float ENERGY_TO_DAMAGE = 0.1f;
    /** Spawn/end packets go to players this close; chunk-loading rounds go to the whole level. */
    private static final double AUDIENCE_RANGE = 1024.0;
    /** Head = eye height +- this, for {@link KineticPreset#headshot}. */
    private static final double HEAD_HALF_HEIGHT = 0.25;
    /** Pick slack on plain AABB targets, as vanilla's projectile sweep. */
    private static final double PICK_INFLATE = 0.3;
    /** Chunk ticket ids: {@code key} in the low half. */
    private static final long TICKET_HIGH = 0x524F554E44L;

    private static long nextKey = 1L;

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
        long key = nextKey++;
        RoundBatch b = batch(level);
        int i = b.add(key, preset, at.x, at.y, at.z, velocity.x, velocity.y, velocity.z,
                shooter == null ? -1 : shooter.getId(), faction);
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

    /** Chunk (packed) whose ticket the round holds; {@code Long.MIN_VALUE} for none or ended. */
    public static long heldChunk(ServerLevel level, long key) {
        RoundBatch b = batch(level);
        int i = b.indexOf(key);
        return i < 0 ? RoundBatch.NO_CHUNK : b.chunk[i];
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
        end(level, b, i);
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
        if (tier.view().isEmpty()) {
            return;
        }
        RoundBatch b = tier.view().get(0);
        for (int i = 0; i < b.size; ) {
            if (step(level, b, i)) {
                i++;
            }
        }
        RoundNetwork.flush(level);
    }

    /** @return false if round {@code i} ended (the next round now sits at {@code i}) */
    private static boolean step(ServerLevel level, RoundBatch b, int i) {
        KineticPreset preset = b.preset[i];
        if (--b.life[i] <= 0) {
            end(level, b, i);
            return false;
        }
        switch (ground(level, b, i)) {
            case LOST -> {
                end(level, b, i);
                return false;
            }
            case WAIT -> {
                return true;
            }
            case FLY -> {
            }
        }
        Entity shooter = b.shooter[i] < 0 ? null : level.getEntity(b.shooter[i]);
        if (b.rest[i] >= 0) {
            return rest(level, b, i, shooter);
        }
        Entity shooterRoot = shooter == null ? null : shooter.getRootVehicle();
        Predicate<Entity> canHit = !preset.entityContact() ? e -> false
                : e -> e.isAlive() && !e.isSpectator() && e.canBeHitByProjectile()
                && (shooterRoot == null || e.getRootVehicle() != shooterRoot);
        for (int attempt = 0; attempt <= preset.penetration(); attempt++) {
            Vec3 from = new Vec3(b.x[i], b.y[i], b.z[i]);
            Vec3 to = from.add(b.vx[i], b.vy[i], b.vz[i]);
            HitResult hit = sweep(level, from, to, canHit);
            if (hit.getType() == HitResult.Type.MISS) {
                break;
            }
            Vec3 velocity = new Vec3(b.vx[i], b.vy[i], b.vz[i]);
            if (hit instanceof EntityHit eh) {
                ProjectileStrikeEvent strike = strike(level, preset, null, b.key[i], eh, from, velocity, shooter);
                if (strike.outcome() == ProjectileStrikeEvent.Outcome.PASS) {
                    break;
                }
                if (strike.outcome() == ProjectileStrikeEvent.Outcome.DUD) {
                    end(level, b, i);
                    return false;
                }
                if (preset.detonateOnEntity()) {
                    detonate(level, b, i, strike.detonation() != null ? strike.detonation() : eh.at, shooter);
                    return false;
                }
                break;
            }
            BlockHitResult bh = (BlockHitResult) hit;
            if (!drill(level, b, i, bh)) {
                NeoForge.EVENT_BUS.post(new RoundImpactEvent(level, preset, bh, velocity));
                if (preset.fuseDelay() > 0 || preset.burrow() > 0.0f) {
                    land(level, b, i, bh.getLocation());
                    return true;
                }
                detonate(level, b, i, bh.getLocation(), shooter);
                return false;
            }
        }
        if (fuseTripped(level, preset, new Vec3(b.x[i], b.y[i], b.z[i]),
                new Vec3(b.vx[i], b.vy[i], b.vz[i]), canHit)) {
            detonate(level, b, i, new Vec3(b.x[i], b.y[i], b.z[i]), shooter);
            return false;
        }
        boolean wet = !level.getFluidState(BlockPos.containing(b.x[i], b.y[i], b.z[i])).isEmpty();
        b.x[i] += b.vx[i];
        b.y[i] += b.vy[i];
        b.z[i] += b.vz[i];
        double decay = preset.decay(Math.sqrt(b.vx[i] * b.vx[i] + b.vy[i] * b.vy[i] + b.vz[i] * b.vz[i]));
        float gravity = preset.gravity();
        if (wet) {
            decay = (double) (1.0f - Math.max(preset.waterDrag(), 0.0f));
            gravity *= preset.waterGravityFactor();
        }
        b.vx[i] = b.vx[i] * decay;
        b.vy[i] = b.vy[i] * decay - gravity;
        b.vz[i] = b.vz[i] * decay;
        return true;
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
        detonate(level, b, i, new Vec3(b.x[i], b.y[i], b.z[i]), shooter);
        return false;
    }

    private enum Ground { FLY, WAIT, LOST }

    /**
     * This tick's segment end: loaded => fly; unloaded => a chunk-loading round below the build limit tickets it and
     * waits in place (life refunded) until it loads, anything else is lost. Never a synchronous load.
     */
    private static Ground ground(ServerLevel level, RoundBatch b, int i) {
        double ex = b.x[i] + b.vx[i];
        double ey = b.y[i] + b.vy[i];
        double ez = b.z[i] + b.vz[i];
        if (ey >= level.getMaxBuildHeight() && b.y[i] >= level.getMaxBuildHeight()) {
            return Ground.FLY;
        }
        int cx = Mth.floor(ex) >> 4;
        int cz = Mth.floor(ez) >> 4;
        if (level.getChunkSource().getChunkNow(cx, cz) != null) {
            return Ground.FLY;
        }
        if (!b.preset[i].loadsChunks()) {
            return Ground.LOST;
        }
        long packed = ChunkPos.asLong(cx, cz);
        if (b.chunk[i] != packed) {
            release(level, b, i);
            DetonationChunkGuard.CONTROLLER.forceChunk(level, ticket(b.key[i]), cx, cz, true, true);
            b.chunk[i] = packed;
        }
        b.life[i]++;
        return Ground.WAIT;
    }

    private static void release(ServerLevel level, RoundBatch b, int i) {
        if (b.chunk[i] != RoundBatch.NO_CHUNK) {
            DetonationChunkGuard.CONTROLLER.forceChunk(level, ticket(b.key[i]), ChunkPos.getX(b.chunk[i]),
                    ChunkPos.getZ(b.chunk[i]), false, true);
            b.chunk[i] = RoundBatch.NO_CHUNK;
        }
    }

    private static UUID ticket(long key) {
        return new UUID(TICKET_HIGH, key);
    }

    private static void end(ServerLevel level, RoundBatch b, int i) {
        release(level, b, i);
        RoundNetwork.ended(level, b, i);
        b.remove(i);
    }

    private static void detonate(ServerLevel level, RoundBatch b, int i, Vec3 at, @Nullable Entity shooter) {
        KineticPreset preset = b.preset[i];
        Vec3 v = new Vec3(b.vx[i], b.vy[i], b.vz[i]);
        Vec3 angle = v.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : v.normalize();
        UUID faction = b.faction[i];
        end(level, b, i);
        WarheadRegistry.get(preset.warheadId()).detonate(new RoundCarrier(level, preset, angle, shooter, faction), at);
    }

    /** Strike event, then the direct hit when it proceeds. */
    static ProjectileStrikeEvent strike(ServerLevel level, KineticPreset preset, @Nullable Entity projectile,
                                        Object key, EntityHit eh, Vec3 from, Vec3 velocity,
                                        @Nullable Entity shooter) {
        ProjectileStrikeEvent strike = NeoForge.EVENT_BUS.post(new ProjectileStrikeEvent(projectile, key, eh.entity,
                eh.at, velocity, threat(preset)));
        if (strike.outcome() != ProjectileStrikeEvent.Outcome.PROCEED) {
            return strike;
        }
        RoundDamageSource source = new RoundDamageSource(damageType(level), projectile, shooter, threat(preset), from,
                velocity, key);
        // One hit per round: i-frames from anything else must not swallow it (MG streams).
        if (eh.entity instanceof LivingEntity living) {
            double fromEye = eh.at.y - living.getY() - living.getEyeHeight();
            float factor = Math.abs(fromEye) < HEAD_HALF_HEIGHT ? preset.headshot() : 1.0f;
            WFDamage.hurtIgnoringIFrames(living, source, impactDamage(preset, velocity) * factor);
        } else {
            eh.entity.hurt(source, impactDamage(preset, velocity));
        }
        return strike;
    }

    /** Armour-piercing: each block no harder than the preset's resistance costs one penetration and is destroyed. */
    private static boolean drill(ServerLevel level, RoundBatch b, int i, BlockHitResult hit) {
        if (b.pen[i] <= 0) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock().getExplosionResistance() > b.preset[i].penetrationResistance()) {
            return false;
        }
        b.pen[i]--;
        level.destroyBlock(pos, false);
        return true;
    }

    static boolean fuseTripped(ServerLevel level, KineticPreset preset, Vec3 at, Vec3 v, Predicate<Entity> canHit) {
        double proximity = preset.proximityRadius();
        if (proximity > 0.0) {
            for (Entity e : level.getEntities((Entity) null, new AABB(at, at).inflate(proximity), canHit)) {
                if (e.distanceToSqr(at) <= proximity * proximity) {
                    return true;
                }
            }
        }
        double airburst = preset.airburstHeight();
        if (airburst > 0.0 && v.y < 0.0 && level.hasChunkAt(BlockPos.containing(at))) {
            BlockHitResult below = level.clip(new ClipContext(at, at.subtract(0.0, airburst + v.length(), 0.0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            return below.getType() == HitResult.Type.BLOCK;
        }
        return false;
    }

    /** First block or entity on {@code from -> to}; shaped entities by their {@link PreciseHitbox}. */
    static HitResult sweep(ServerLevel level, Vec3 from, Vec3 to, Predicate<Entity> canHit) {
        BlockHitResult block = clipLoaded(level, from, to);
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        Entity best = null;
        Vec3 bestAt = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, new AABB(from, end).inflate(1.0), canHit)) {
            Vec3 at;
            if (e instanceof PreciseHitbox shape) {
                at = shape.clip(from, end);
            } else {
                AABB box = e.getBoundingBox().inflate(PICK_INFLATE);
                at = box.contains(from) ? from : box.clip(from, end).orElse(null);
            }
            if (at != null) {
                double d = from.distanceToSqr(at);
                if (d < bestSq) {
                    bestSq = d;
                    best = e;
                    bestAt = at;
                }
            }
        }
        return best != null ? new EntityHit(best, bestAt) : block;
    }

    /** {@code Level.clip} (collider shapes, no fluid) reading loaded chunks only; unloaded = air. */
    private static BlockHitResult clipLoaded(ServerLevel level, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty());
        return BlockGetter.traverseBlocks(from, to, ctx, (c, pos) -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                return null;
            }
            BlockState state = chunk.getBlockState(pos);
            return level.clipWithInteractionOverride(c.getFrom(), c.getTo(), pos, c.getBlockShape(state, level, pos),
                    state);
        }, c -> {
            Vec3 d = c.getFrom().subtract(c.getTo());
            return BlockHitResult.miss(c.getTo(), Direction.getNearest(d.x, d.y, d.z), BlockPos.containing(c.getTo()));
        });
    }

    public static Threat threat(KineticPreset preset) {
        ThreatKind kind;
        if (preset.threatKind() != null) {
            kind = preset.threatKind();
        } else if (WarheadRegistry.rl("shaped_charge").equals(preset.warheadId())) {
            kind = ThreatKind.HEAT;
        } else if (preset.armorPenetrationMm() > 0.0f || WarheadRegistry.rl("inert").equals(preset.warheadId())) {
            kind = ThreatKind.KINETIC;
        } else {
            kind = ThreatKind.HE;
        }
        return new Threat(kind, preset.caliberMm(), preset.armorPenetrationMm());
    }

    static float impactDamage(KineticPreset preset, Vec3 velocity) {
        return preset.impactDamage() > 0.0f ? preset.impactDamage()
                : (float) (0.5 * preset.mass() * velocity.lengthSqr() * ENERGY_TO_DAMAGE);
    }

    private static Holder<DamageType> damageType(ServerLevel level) {
        return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageClass.PHYSICAL.type);
    }

    /** Entity hit carrying the exact point (vanilla's result stores the entity origin for projectile sweeps). */
    static final class EntityHit extends net.minecraft.world.phys.EntityHitResult {
        final Entity entity;
        final Vec3 at;

        EntityHit(Entity entity, Vec3 at) {
            super(entity, at);
            this.entity = entity;
            this.at = at;
        }
    }

    /** Players who should draw a round at {@code at}. */
    static List<ServerPlayer> audience(ServerLevel level, KineticPreset preset, double x, double z) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (preset.loadsChunks() || dx * dx + dz * dz <= AUDIENCE_RANGE * AUDIENCE_RANGE) {
                out.add(player);
            }
        }
        return out;
    }

    static void send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
