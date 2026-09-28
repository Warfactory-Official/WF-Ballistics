package com.wf.wflib.round;

import com.wf.wflib.ModEntities;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatSource;
import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.round.client.RocketClient;
import com.wf.wflib.round.effect.ImpactContext;
import com.wf.wflib.round.effect.ImpactEffects;
import com.wf.wflib.round.effect.ImpactTrigger;
import com.wf.wflib.warhead.WarheadRegistry;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Unguided rocket: a {@link KineticPreset} with a motor, flown as an entity so air defence can engage it. Nose fixed
 * while burning, then follows velocity. Flight = rounds' step + thrust + quadratic drag. Transient.
 */
public class RocketEntity extends Entity implements InterceptTarget, ThreatSource, IEntityWithComplexSpawn {

    private KineticPreset preset;
    private int age;
    private float health;
    @Nullable
    private Entity shooter;
    @Nullable
    private UUID faction;
    @Nullable
    private UUID controlId;
    /** Chunks this rocket holds a ticket on (packed). */
    private final LongArrayList held = new LongArrayList(2);

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * A launcher's shot: nose = {@code direction} spread by {@code inaccuracy} + preset dispersion (degrees),
     * velocity = nose x muzzle speed + {@code carrier}. Added to the level.
     */
    public static RocketEntity fire(ServerLevel level, KineticPreset preset, Vec3 from, Vec3 direction,
                                    float inaccuracy, Vec3 carrier, @Nullable Entity shooter, @Nullable UUID faction,
                                    @Nullable UUID controlId) {
        Vec3 nose = direction.lengthSqr() < 1.0e-8 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        float spread = inaccuracy + preset.dispersion();
        if (spread > 0.0f) {
            RandomSource random = level.random;
            double s = 0.0172275 * Math.toRadians(spread) * 40.0;
            nose = nose.add(random.triangle(0.0, s), random.triangle(0.0, s), random.triangle(0.0, s)).normalize();
        }
        RocketEntity rocket = new RocketEntity(ModEntities.ROCKET.get(), level);
        rocket.preset = preset;
        rocket.health = preset.durability();
        rocket.shooter = shooter;
        rocket.faction = faction;
        rocket.controlId = controlId;
        rocket.setPos(from);
        rocket.face(nose);
        rocket.setOldPosAndRot();
        rocket.setDeltaMovement(nose.scale(preset.muzzleSpeed()).add(carrier));
        level.addFreshEntity(rocket);
        return rocket;
    }

    public KineticPreset preset() {
        return this.preset;
    }

    public boolean burning() {
        return this.age <= this.preset.burnTicks();
    }

    @Nullable
    public Entity shooter() {
        return this.shooter;
    }

    private void face(Vec3 dir) {
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        this.setYRot((float) (Mth.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG));
        this.setXRot((float) (Mth.atan2(-dir.y, horizontal) * Mth.RAD_TO_DEG));
    }

    @Override
    public void tick() {
        super.tick();
        this.age++;
        if (!(this.level() instanceof ServerLevel level)) {
            RocketClient.tick(this);
            return;
        }
        if (this.age > this.preset.lifeTicks()) {
            this.discard();
            return;
        }
        Vec3 from = this.position();
        Vec3 v = this.getDeltaMovement();
        if (!this.flyable(level, from.add(v))) {
            return;
        }
        Entity root = this.shooter == null ? null : this.shooter.getRootVehicle();
        Predicate<Entity> canHit = e -> e != this && e.isAlive() && !e.isSpectator() && e.canBeHitByProjectile()
                && (root == null || e.getRootVehicle() != root);
        Vec3 to = from.add(v);
        BlockHitResult block = Rounds.clipLoaded(level, from, to);
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        for (Rounds.EntityHit eh : Rounds.entityHits(level, from, end, canHit, RoundDamageSource.LIVE)) {
            ProjectileStrikeEvent strike = Rounds.strike(level, this.preset, this, this, eh, from, end, v,
                    this.shooter, this.faction, 1.0f);
            if (this.isRemoved()) {
                return;
            }
            if (strike.outcome() == ProjectileStrikeEvent.Outcome.DUD) {
                this.discard();
                return;
            }
            if (strike.outcome() == ProjectileStrikeEvent.Outcome.PROCEED && this.preset.detonateOnEntity()) {
                this.detonate(level, strike.detonation() != null ? strike.detonation() : eh.at);
                return;
            }
        }
        if (block.getType() == HitResult.Type.BLOCK) {
            NeoForge.EVENT_BUS.post(new RoundImpactEvent(level, this.preset, block, v));
            if (this.preset.hasEffects(ImpactTrigger.BLOCK)) {
                ImpactEffects.fire(new ImpactContext(level, ImpactTrigger.BLOCK, this.preset, this, this, this.shooter,
                        this.faction, block.getLocation(), v, block, null, null, null, null));
            }
            if (!this.isRemoved()) {
                this.detonate(level, block.getLocation());
            }
            return;
        }
        if (Rounds.fuseTripped(level, this.preset, from, v, canHit)) {
            this.detonate(level, from);
            return;
        }
        boolean wet = !level.getFluidState(BlockPos.containing(from)).isEmpty();
        this.setPos(from.add(v));
        double decay = this.preset.decay(v.length());
        float gravity = this.preset.gravity();
        if (wet) {
            decay = 1.0f - Math.max(this.preset.waterDrag(), 0.0f);
            gravity *= this.preset.waterGravityFactor();
        }
        Vec3 next = v.scale(decay).subtract(0.0, gravity, 0.0);
        if (this.burning()) {
            next = next.add(this.getLookAngle().scale(this.preset.motorAccel()));
        } else if (next.lengthSqr() > 1.0e-8) {
            this.face(next);
        }
        this.setDeltaMovement(next);
    }

    /**
     * Segment end in an entity-ticking chunk => fly. Else a chunk-loading preset tickets it and its own chunk and waits
     * (life refunded); anything else is lost. Tickets behind the rocket are dropped.
     */
    private boolean flyable(ServerLevel level, Vec3 to) {
        long here = ChunkPos.asLong(this.getBlockX() >> 4, this.getBlockZ() >> 4);
        long there = ChunkPos.asLong(Mth.floor(to.x) >> 4, Mth.floor(to.z) >> 4);
        for (int k = this.held.size() - 1; k >= 0; k--) {
            long c = this.held.getLong(k);
            if (c != here && c != there) {
                this.ticket(level, c, false);
                this.held.removeLong(k);
            }
        }
        if (level.isPositionEntityTicking(BlockPos.containing(to))) {
            return true;
        }
        if (!this.preset.loadsChunks()) {
            this.discard();
            return false;
        }
        for (long c : new long[]{here, there}) {
            if (!this.held.contains(c)) {
                this.ticket(level, c, true);
                this.held.add(c);
            }
        }
        this.age--;
        return false;
    }

    private void ticket(ServerLevel level, long chunk, boolean add) {
        DetonationChunkGuard.CONTROLLER.forceChunk(level, this.getUUID(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk),
                add, true);
    }

    private void detonate(ServerLevel level, Vec3 at) {
        Vec3 v = this.getDeltaMovement();
        Vec3 angle = v.lengthSqr() < 1.0e-8 ? this.getLookAngle() : v.normalize();
        this.discard();
        if (this.preset.hasEffects(ImpactTrigger.END)) {
            ImpactEffects.fire(new ImpactContext(level, ImpactTrigger.END, this.preset, this, this, this.shooter,
                    this.faction, at, v, null, null, null, null, null));
        }
        WarheadRegistry.get(this.preset.warheadId())
                .detonate(new RoundCarrier(level, this.preset, angle, this.shooter, this.faction), at);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel level) {
            for (int k = 0; k < this.held.size(); k++) {
                this.ticket(level, this.held.getLong(k), false);
            }
            this.held.clear();
        }
        super.remove(reason);
    }

    @Override
    public Threat threat() {
        return Rounds.threat(this.preset);
    }

    @Override
    public boolean interceptEngageable() {
        return !this.isRemoved() && this.health > 0.0f;
    }

    @Override
    public ContactClass interceptClass() {
        return ContactClass.MISSILE;
    }

    @Nullable
    @Override
    public UUID interceptControlId() {
        return this.controlId;
    }

    @Nullable
    @Override
    public UUID interceptTeamId() {
        return this.faction;
    }

    @Override
    public void interceptDamage(float amount) {
        this.health -= amount;
        if (this.health <= 0.0f) {
            this.interceptKill();
        }
    }

    /** Warhead functions where it is shot. */
    @Override
    public void interceptKill() {
        if (!this.isRemoved() && this.level() instanceof ServerLevel level) {
            this.health = 0.0f;
            this.detonate(level, this.position());
        }
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(this.preset.id());
        buf.writeVarInt(this.age);
        buf.writeFloat(this.getXRot());
        buf.writeFloat(this.getYRot());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        this.preset = KineticPresetRegistry.get(buf.readResourceLocation());
        this.age = buf.readVarInt();
        this.setXRot(buf.readFloat());
        this.setYRot(buf.readFloat());
        this.setOldPosAndRot();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 512.0 * 512.0;
    }
}
