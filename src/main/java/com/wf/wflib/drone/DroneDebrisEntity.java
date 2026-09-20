package com.wf.wflib.drone;

import com.wf.wflib.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** One piece of a drone that came apart in the air: a hull, a rotor disc, a gripper jaw. */
public class DroneDebrisEntity extends Entity {

    private static final EntityDataAccessor<String> PART =
            SynchedEntityData.defineId(DroneDebrisEntity.class, EntityDataSerializers.STRING);
    /** Tumble rate, packed into a byte per axis as hundredths of a radian per tick. */
    private static final EntityDataAccessor<Byte> SPIN_X =
            SynchedEntityData.defineId(DroneDebrisEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> SPIN_Y =
            SynchedEntityData.defineId(DroneDebrisEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> SPIN_Z =
            SynchedEntityData.defineId(DroneDebrisEntity.class, EntityDataSerializers.BYTE);
    /** Ticks of tumble the piece had left when it landed, or -1 while it is still falling. */
    private static final EntityDataAccessor<Byte> ROLL_OUT =
            SynchedEntityData.defineId(DroneDebrisEntity.class, EntityDataSerializers.BYTE);
    /** Set a second before the piece goes, whichever limit it is about to reach. */
    private static final EntityDataAccessor<Boolean> FADING =
            SynchedEntityData.defineId(DroneDebrisEntity.class, EntityDataSerializers.BOOLEAN);

    private static final double GRAVITY = 0.055;
    private static final double TERMINAL_FALL = -1.4;
    private static final double AIR_DRAG = 0.985;
    private static final double GROUND_DRAG = 0.55;
    /** Radians per tick per point of the packed spin byte. */
    public static final float SPIN_STEP = 0.01f;
    /** Ticks a piece lies where it landed before it goes. Long enough to be seen, short of being litter. */
    private static final int SETTLE_TICKS = 140;
    /** Hard cap, in case a piece finds somewhere it can neither settle nor fall out of. */
    private static final int LIFETIME_TICKS = 600;
    /** How long a piece that hit the ground at terminal speed goes on turning. Nothing lands harder. */
    private static final int ROLL_OUT_TICKS = 18;
    /** How long a piece takes to fade out, rather than being there one frame and not the next. */
    private static final int FADE_TICKS = 20;

    private int settled = -1;
    /** Client-side count of ticks since it landed, which is all the client needs to bleed the tumble out. */
    private int grounded;
    /** Client-side count of ticks since the fade began. */
    private int fading;

    public DroneDebrisEntity(EntityType<? extends DroneDebrisEntity> type, Level level) {
        super(type, level);
    }

    /**
     * @param part the mesh this piece is, e.g. {@code wflib:entity/drones/drone_prop_px}
     * @param velocity where it is thrown, before gravity
     * @param spin tumble in radians/tick about each axis
     */
    public DroneDebrisEntity(Level level, Vec3 pos, ResourceLocation part, Vec3 velocity, Vec3 spin) {
        this(ModEntities.DRONE_DEBRIS.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.entityData.set(PART, part.toString());
        this.entityData.set(SPIN_X, pack(spin.x));
        this.entityData.set(SPIN_Y, pack(spin.y));
        this.entityData.set(SPIN_Z, pack(spin.z));
        this.setYRot((float) (level.getRandom().nextFloat() * 360.0f));
    }

    private static byte pack(double radiansPerTick) {
        return (byte) Math.max(-127, Math.min(127, Math.round(radiansPerTick / SPIN_STEP)));
    }

    /**
     * @return the piece of the airframe this is: an id naming the asset and the node inside it.
     */
    public ResourceLocation part() {
        ResourceLocation parsed = ResourceLocation.tryParse(this.entityData.get(PART));
        return parsed != null ? parsed : DroneModels.fallbackPiece();
    }

    /**
     * @return tumble about each axis, radians/tick. Read on the client, which is the only place it matters.
     */
    public Vec3 spin() {
        return new Vec3(this.entityData.get(SPIN_X) * SPIN_STEP,
                this.entityData.get(SPIN_Y) * SPIN_STEP,
                this.entityData.get(SPIN_Z) * SPIN_STEP);
    }

    /**
     * @return true once this piece has hit the ground, on either side.
     */
    public boolean isSettled() {
        return this.entityData.get(ROLL_OUT) >= 0;
    }

    /**
     * @return how much of the tumble is left: 1 the whole way down, then off to 0 across whatever roll-out
     *      the landing bought it. A piece that came down at speed turns a moment longer than one that was
     *      nearly stopped when it touched, and neither goes on turning where it lies.
     */
    public float spinScale() {
        byte rollOut = this.entityData.get(ROLL_OUT);
        if (rollOut < 0) {
            return 1.0f;
        }
        if (rollOut == 0) {
            return 0.0f;
        }
        return Math.max(0.0f, 1.0f - (float) this.grounded / rollOut);
    }

    /**
     * @return true once the piece has started going, which is when it stops being drawn at full opacity.
     */
    public boolean isFading() {
        return this.entityData.get(FADING);
    }

    /**
     * @param partialTick where the frame falls between ticks, so the fade is smooth rather than 20 steps
     * @return how opaque to draw the piece, 1 until its last second and 0 by the time it is removed
     */
    public float alpha(float partialTick) {
        if (!this.isFading()) {
            return 1.0f;
        }
        return Mth.clamp(1.0f - (this.fading + partialTick) / FADE_TICKS, 0.0f, 1.0f);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            if (this.isSettled()) {
                this.grounded++;
            }
            if (this.isFading()) {
                this.fading++;
            }
            return;
        }
        if (this.tickCount > LIFETIME_TICKS) {
            this.discard();
            return;
        }
        if (this.settled >= 0 && ++this.settled > SETTLE_TICKS) {
            this.discard();
            return;
        }
        if (this.settled >= SETTLE_TICKS - FADE_TICKS || this.tickCount >= LIFETIME_TICKS - FADE_TICKS) {
            this.entityData.set(FADING, true);
        }
        if (this.settled >= 0) {
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        double descent = Math.max(TERMINAL_FALL, velocity.y - GRAVITY);
        this.setDeltaMovement(velocity.x * AIR_DRAG, descent, velocity.z * AIR_DRAG);
        this.move(MoverType.SELF, this.getDeltaMovement());
        if (this.onGround()) {
            this.land(-descent);
        }
    }

    /**
     * Set the piece down and decide how long it keeps turning, from how hard it arrived.
     *
     * @param impact how fast it was going down when it hit, in blocks/tick
     */
    private void land(double impact) {
        this.settled = 0;
        double share = Mth.clamp(impact / -TERMINAL_FALL, 0.0, 1.0);
        this.entityData.set(ROLL_OUT, (byte) Math.round(ROLL_OUT_TICKS * share));
        Vec3 velocity = this.getDeltaMovement();
        this.setDeltaMovement(velocity.x * GROUND_DRAG, 0.0, velocity.z * GROUND_DRAG);
        if (this.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SMOKE, this.getX(), this.getY() + 0.1, this.getZ(),
                    1 + (int) (share * 4.0), 0.15, 0.05, 0.15, 0.01);
        }
    }

    /**
     * Break a drone into its bones: the hull and every moving part it is drawn with, each thrown out from the
     * centre and left to fall on its own.
     *
     * @param at where the airframe was when it came apart
     * @param yaw which way it was facing, so the pieces leave from where they actually were
     * @param motion what it was doing at the time, which the pieces inherit
     */
    public static void shatter(ServerLevel level, ResourceLocation modelId, Vec3 at, float yaw, Vec3 motion) {
        Vec3 centre = at.add(0.0, DroneModels.center(modelId).y, 0.0);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        for (ResourceLocation part : DroneModels.pieces(modelId)) {
            Vec3 local = DroneModels.pieceOffset(modelId, part);
            Vec3 offset = new Vec3(local.x * cos + local.z * sin, local.y, local.z * cos - local.x * sin);
            Vec3 out = offset.lengthSqr() > 1.0E-6 ? offset.normalize() : randomHorizontal(level);
            double kick = 0.12 + level.getRandom().nextDouble() * 0.16;
            Vec3 velocity = motion.scale(0.6)
                    .add(out.scale(kick))
                    .add(0.0, 0.10 + level.getRandom().nextDouble() * 0.12, 0.0);
            Vec3 spin = new Vec3(spread(level, 0.5), spread(level, 0.8), spread(level, 0.5));
            level.addFreshEntity(new DroneDebrisEntity(level, centre.add(offset), part, velocity, spin));
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, centre.x, centre.y, centre.z, 12, 0.4, 0.3, 0.4, 0.02);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.NEUTRAL, 0.7f, 1.6f);
    }

    private static Vec3 randomHorizontal(ServerLevel level) {
        double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
        return new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
    }

    private static double spread(ServerLevel level, double magnitude) {
        return (level.getRandom().nextDouble() * 2.0 - 1.0) * magnitude;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(PART, DroneModels.fallbackPiece().toString());
        builder.define(SPIN_X, (byte) 0);
        builder.define(SPIN_Y, (byte) 0);
        builder.define(SPIN_Z, (byte) 0);
        builder.define(ROLL_OUT, (byte) -1);
        builder.define(FADING, false);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Part")) {
            this.entityData.set(PART, tag.getString("Part"));
        }
        this.entityData.set(SPIN_X, tag.getByte("SpinX"));
        this.entityData.set(SPIN_Y, tag.getByte("SpinY"));
        this.entityData.set(SPIN_Z, tag.getByte("SpinZ"));
        this.entityData.set(ROLL_OUT, tag.contains("RollOut") ? tag.getByte("RollOut") : (byte) -1);
        this.entityData.set(FADING, tag.getBoolean("Fading"));
        this.settled = tag.getInt("Settled");
        // A piece that was already down when the chunk unloaded comes back down, not mid-tumble.
        this.grounded = Math.max(0, this.settled);
        this.fading = 0;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("Part", this.entityData.get(PART));
        tag.putByte("SpinX", this.entityData.get(SPIN_X));
        tag.putByte("SpinY", this.entityData.get(SPIN_Y));
        tag.putByte("SpinZ", this.entityData.get(SPIN_Z));
        tag.putByte("RollOut", this.entityData.get(ROLL_OUT));
        tag.putBoolean("Fading", this.entityData.get(FADING));
        tag.putInt("Settled", this.settled);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
