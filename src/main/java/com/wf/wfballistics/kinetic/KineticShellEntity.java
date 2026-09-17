package com.wf.wfballistics.kinetic;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.chunk.MissileChunkLoader;
import com.wf.wfballistics.damage.DamageClass;
import com.wf.wfballistics.damage.WFDamageSources;
import com.wf.wfballistics.entity.ModelledProjectile;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.util.SweptCollision;
import com.wf.wfballistics.warhead.WarheadCarrier;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A kinetic round in flight: unguided, drag and gravity only, carrying a {@link WarheadRegistry} payload. */
public class KineticShellEntity extends Projectile implements WarheadCarrier, ModelledProjectile {

    /**
     * Fraction of a shell's kinetic energy that lands as damage on a direct hit, when the preset does not name a
     * flat figure.
     */
    private static final float ENERGY_TO_DAMAGE = 0.1f;
    /** Ticks after materialising before a shell may hand itself back to the simulator. */
    private static final int SIM_REARM_TICKS = 2;

    private static final EntityDataAccessor<String> PRESET_ID =
            SynchedEntityData.defineId(KineticShellEntity.class, EntityDataSerializers.STRING);

    private KineticPreset preset = KineticPresetRegistry.fallback();
    private WarheadRegistry.Detonation detonation = WarheadRegistry.STANDARD;
    @Nullable
    private UUID factionId;
    private int remainingLife;
    private int penetrationLeft;
    private int simRearm;
    private boolean detonated;
    private boolean drilledThisTick;
    /** Set on a shell put back by the simulator: it may be descending into chunks nobody is holding. */
    private boolean keepChunksLoaded;
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();

    public KineticShellEntity(EntityType<? extends KineticShellEntity> type, Level level) {
        super(type, level);
        this.remainingLife = preset.lifeTicks();
        this.penetrationLeft = preset.penetration();
    }

    public KineticShellEntity(Level level, KineticPreset preset) {
        this(ModEntities.KINETIC_SHELL.get(), level);
        this.applyPreset(preset);
    }

    /**
     * Put a round in the world the way a gun does.
     *
     * @param direction unit heading down the barrel; the round's speed comes from the preset
     * @param inaccuracy the gun's own spread in degrees, added to the round's {@link KineticPreset#dispersion()}
     */
    public static KineticShellEntity fire(ServerLevel level, KineticPreset preset, Vec3 from, Vec3 direction,
                                          float inaccuracy, @Nullable Entity owner, @Nullable UUID factionId) {
        KineticShellEntity shell = new KineticShellEntity(level, preset);
        shell.setPos(from.x, from.y, from.z);
        shell.setOwner(owner);
        shell.factionId = factionId;
        shell.shootDirection(direction, preset.muzzleSpeed(), inaccuracy + preset.dispersion());
        level.addFreshEntity(shell);
        return shell;
    }

    /** Aim and launch: the direction is spread by {@code spreadDegrees} and scaled to the muzzle speed. */
    public void shootDirection(Vec3 direction, float speed, float spreadDegrees) {
        Vec3 heading = direction.lengthSqr() < 1.0e-8 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        if (spreadDegrees > 0.0f) {
            double spread = Math.toRadians(spreadDegrees);
            heading = heading
                    .add(this.random.triangle(0.0, 0.0172275 * spread * 40.0),
                            this.random.triangle(0.0, 0.0172275 * spread * 40.0),
                            this.random.triangle(0.0, 0.0172275 * spread * 40.0))
                    .normalize();
        }
        this.setDeltaMovement(heading.scale(speed));
        this.alignToMotion();
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    /** Adopt a preset: the round's identity, and everything derived from it. */
    public void applyPreset(KineticPreset preset) {
        this.preset = preset != null ? preset : KineticPresetRegistry.fallback();
        this.detonation = WarheadRegistry.get(this.preset.warheadId());
        this.remainingLife = this.preset.lifeTicks();
        this.penetrationLeft = this.preset.penetration();
        if (!this.level().isClientSide()) {
            this.entityData.set(PRESET_ID, this.preset.id().toString());
        }
    }

    public KineticPreset preset() {
        return preset;
    }

    @Override
    public ResourceLocation getModelId() {
        return preset.modelId();
    }

    /** Ticks of flight left. Survives a trip through the simulator, unlike {@link #tickCount}. */
    public int remainingLife() {
        return remainingLife;
    }

    public void setRemainingLife(int ticks) {
        this.remainingLife = ticks;
    }

    @Nullable
    public UUID factionId() {
        return factionId;
    }

    public void setFactionId(@Nullable UUID factionId) {
        this.factionId = factionId;
    }

    /**
     * Mark this round as one the simulator has just put back, which is the only case where a shell holds its own
     * chunks: it is descending into terrain that nothing else is keeping loaded.
     */
    public void setResumedFromSim() {
        this.keepChunksLoaded = true;
        this.simRearm = SIM_REARM_TICKS;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.detonated) {
            return;
        }

        if (this.level() instanceof ServerLevel level) {
            if (--this.remainingLife <= 0) {
                this.discard();
                return;
            }
            if (this.simRearm > 0) {
                this.simRearm--;
            }
            for (int attempt = 0; attempt <= preset.penetration(); attempt++) {
                this.drilledThisTick = false;
                HitResult hit = this.sweepForImpact();
                if (hit.getType() == HitResult.Type.MISS) {
                    break;
                }
                this.onHit(hit);
                if (this.isRemoved() || this.detonated) {
                    return;
                }
                if (!this.drilledThisTick) {
                    break;
                }
            }
            if (this.tripsFuse(level)) {
                this.detonate(this.position());
                return;
            }
            if (this.keepChunksLoaded) {
                this.chunkLoader.update(this, level, this.position(), this.getDeltaMovement(), false);
            }
        }

        this.move();

        if (this.level() instanceof ServerLevel level) {
            this.tryHandOffToSim(level);
        }
    }

    /** One tick of flight. */
    private void move() {
        Vec3 velocity = this.getDeltaMovement();
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);

        double decay = preset.decay();
        float gravity = preset.gravity();
        if (this.isInWater()) {
            decay = (double) (1.0f - Math.max(preset.waterDrag(), 0.0f));
            gravity *= preset.waterGravityFactor();
        }
        this.setDeltaMovement(this.getDeltaMovement().scale(decay).subtract(0.0, gravity, 0.0));
        this.alignToMotion();
    }

    /** The first thing this tick's travel runs into, swept in substeps. */
    private HitResult sweepForImpact() {
        return SweptCollision.sweep(this, this.level(), this.position(), this.getDeltaMovement(), 0.0,
                this::canHitEntity,
                MissileSimConfig.COLLISION_MAX_SUBSTEP_DIST, MissileSimConfig.COLLISION_MAX_SUBSTEPS);
    }

    /** Point the shell where it is going. */
    public void alignToMotion() {
        Vec3 velocity = this.getDeltaMovement();
        double horizontal = velocity.horizontalDistance();
        if (velocity.lengthSqr() < 1.0e-8) {
            return;
        }
        this.setYRot((float) (Mth.atan2(velocity.x, velocity.z) * (180.0 / Math.PI)));
        this.setXRot((float) (Mth.atan2(velocity.y, horizontal) * (180.0 / Math.PI)));
    }

    /**
     * Hand the round to the simulator if it is climbing above the top of the world.
     *
     * @return true if the round left the world, in which case this entity is gone
     */
    private boolean tryHandOffToSim(ServerLevel level) {
        if (!preset.simulated() || this.simRearm > 0) {
            return false;
        }
        if (this.getY() <= level.getMaxBuildHeight() || this.getDeltaMovement().y <= 0.0) {
            return false;
        }
        KineticSimManager.handOff(level, this);
        this.releaseChunks(level);
        this.discard();
        return true;
    }

    /** Airburst and proximity fuses: the two ways a round goes off without touching anything. */
    private boolean tripsFuse(ServerLevel level) {
        double proximity = preset.proximityRadius();
        if (proximity > 0.0) {
            for (Entity entity : level.getEntities(this, this.getBoundingBox().inflate(proximity),
                    this::canHitEntity)) {
                if (entity.distanceToSqr(this) <= proximity * proximity) {
                    return true;
                }
            }
        }
        double airburst = preset.airburstHeight();
        if (airburst > 0.0 && this.getDeltaMovement().y < 0.0 && level.hasChunkAt(this.blockPosition())) {
            Vec3 from = this.position();
            Vec3 to = from.subtract(0.0, airburst + this.getDeltaMovement().length(), 0.0);
            BlockHitResult below = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, this));
            return below.getType() == HitResult.Type.BLOCK;
        }
        return false;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide()) {
            return;
        }
        Entity target = result.getEntity();
        target.hurt(WFDamageSources.create(this.level(), DamageClass.PHYSICAL, this.getOwner(), this),
                this.impactDamage());
        if (preset.detonateOnEntity()) {
            this.detonate(result.getLocation());
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (this.level().isClientSide()) {
            return;
        }
        if (this.drillThrough(result)) {
            return;
        }
        this.detonate(result.getLocation());
    }

    /**
     * An armour-piercing round buries itself before it goes off: each block of cover it can get through costs it
     * one of its {@link KineticPreset#penetration()} and is left destroyed behind it.
     *
     * @return true if the round kept going
     */
    private boolean drillThrough(BlockHitResult result) {
        if (this.penetrationLeft <= 0) {
            return false;
        }
        BlockPos pos = result.getBlockPos();
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir() || state.getBlock().getExplosionResistance() > preset.penetrationResistance()) {
            return false;
        }
        this.penetrationLeft--;
        this.drilledThisTick = true;
        this.level().destroyBlock(pos, false, this);
        return true;
    }

    /** What a direct hit is worth: the preset's flat figure, or the round's own kinetic energy. */
    public float impactDamage() {
        if (preset.impactDamage() > 0.0f) {
            return preset.impactDamage();
        }
        double speedSq = this.getDeltaMovement().lengthSqr();
        return (float) (0.5 * preset.mass() * speedSq * ENERGY_TO_DAMAGE);
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        if (entity == this.getOwner() || entity instanceof KineticShellEntity) {
            return false;
        }
        return super.canHitEntity(entity);
    }

    /** Fire the warhead and remove the round. Safe to call twice; only the first one goes off. */
    public void detonate(Vec3 pos) {
        if (this.detonated) {
            return;
        }
        // Set before the blast: it can hurt or hit this shell before discard() runs.
        this.detonated = true;
        this.detonation.detonate(this, pos);
        if (this.level() instanceof ServerLevel level) {
            this.releaseChunks(level);
        }
        this.discard();
    }

    private void releaseChunks(ServerLevel level) {
        if (this.keepChunksLoaded) {
            this.chunkLoader.releaseAll(this, level);
            this.keepChunksLoaded = false;
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel level) {
            this.releaseChunks(level);
        }
        super.remove(reason);
    }

    @Override
    public Level level() {
        return super.level();
    }

    @Override
    public int getFragmentCount() {
        return preset.fragmentCount();
    }

    @Override
    public Vec3 angle() {
        Vec3 velocity = this.getDeltaMovement();
        return velocity.lengthSqr() < 1.0e-8 ? new Vec3(0.0, -1.0, 0.0) : velocity.normalize();
    }

    @Override
    public float blastHalfAngleDeg() {
        return preset.blastHalfAngleDeg();
    }

    @Override
    @Nullable
    public UUID igniterFactionId() {
        return factionId;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(PRESET_ID, KineticPresetRegistry.fallback().id().toString());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PRESET_ID.equals(key) && this.level().isClientSide()) {
            // The client only ever learns which round this is from the sync, and it decides the model.
            ResourceLocation id = ResourceLocation.tryParse(this.entityData.get(PRESET_ID));
            KineticPreset synced = id == null ? null : KineticPresetRegistry.get(id);
            this.preset = synced != null ? synced : KineticPresetRegistry.fallback();
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Preset", preset.id().toString());
        tag.putInt("RemainingLife", remainingLife);
        tag.putInt("Penetration", penetrationLeft);
        tag.putBoolean("Detonated", detonated);
        if (factionId != null) {
            tag.putUUID("Faction", factionId);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Preset")) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("Preset"));
            KineticPreset saved = id == null ? null : KineticPresetRegistry.get(id);
            this.applyPreset(saved != null ? saved : KineticPresetRegistry.fallback());
        }
        if (tag.contains("RemainingLife")) {
            this.remainingLife = tag.getInt("RemainingLife");
        }
        if (tag.contains("Penetration")) {
            this.penetrationLeft = tag.getInt("Penetration");
        }
        this.detonated = tag.getBoolean("Detonated");
        this.factionId = tag.hasUUID("Faction") ? tag.getUUID("Faction") : null;
    }
}
