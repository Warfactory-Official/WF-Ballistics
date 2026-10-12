package com.wf.wflib.countermeasure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Bouncing launcher grenade. Both sides step the bounce; client snaps to each server position first. Never hits
 * its shooter or carrier.
 */
public abstract class CountermeasureGrenade extends Projectile {

    protected static final EntityDataAccessor<Boolean> POPPED =
            SynchedEntityData.defineId(CountermeasureGrenade.class, EntityDataSerializers.BOOLEAN);
    static final float GRAVITY = 0.07f;
    static final double BOUNCE = 0.75;

    @Nullable
    private UUID carrier;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private boolean lerp;

    protected CountermeasureGrenade(EntityType<? extends CountermeasureGrenade> type, Level level) {
        super(type, level);
    }

    /** Ticks before it is removed. */
    protected abstract int life();

    void carrier(@Nullable Entity carrier) {
        this.carrier = carrier == null ? null : carrier.getUUID();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(POPPED, false);
    }

    public boolean popped() {
        return entityData.get(POPPED);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerp = true;
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && (carrier == null || !carrier.equals(target.getUUID()));
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() && lerp) {
            setPos(lerpX, lerpY, lerpZ);
            lerp = false;
        }
        Vec3 start = position();
        Vec3 motion = getDeltaMovement();
        Vec3 end = start.add(motion);
        Vec3 rest = motion;
        // <= 3 hits/tick; third block hit stops it.
        for (int i = 0; i < 3; i++) {
            HitResult hit = hitResult(start, end, rest);
            if (hit.getType() == HitResult.Type.MISS) {
                break;
            }
            if (hit instanceof BlockHitResult block) {
                Vec3 at = block.getLocation();
                if (block.getDirection() == Direction.UP && start.y - at.y < 0.01) {
                    at = new Vec3(at.x, start.y, at.z);
                }
                if (i < 2) {
                    start = start.lerp(at, 0.8);
                    rest = bounce(block.getDirection(), end.subtract(start));
                    end = start.add(rest);
                    motion = bounce(block.getDirection(), motion);
                } else {
                    end = start.lerp(at, 0.8);
                    motion = Vec3.ZERO;
                }
            } else {
                Direction d = Direction.getNearest(rest.x, rest.y, rest.z).getOpposite();
                start = start.lerp(hit.getLocation(), 0.8);
                rest = bounce(d, end.subtract(start));
                end = start.add(rest);
                motion = bounce(d, motion);
            }
            onHit(hit);
            if (isRemoved()) {
                return;
            }
        }
        checkInsideBlocks();
        float friction = isInWater() ? 0.8f : 0.99f;
        if (isInWater() && level().isClientSide()) {
            for (int i = 0; i < 4; i++) {
                level().addParticle(ParticleTypes.BUBBLE, end.x - motion.x * 0.25, end.y - motion.y * 0.25,
                        end.z - motion.z * 0.25, motion.x, motion.y, motion.z);
            }
        }
        setDeltaMovement(motion.scale(friction).add(0, -GRAVITY, 0));
        setPos(end);
        if (!level().isClientSide() && tickCount >= life()) {
            discard();
        }
    }

    private HitResult hitResult(Vec3 start, Vec3 end, Vec3 offset) {
        HitResult hit = level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                this));
        Vec3 to = hit.getType() != HitResult.Type.MISS ? hit.getLocation() : end;
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(level(), this, start, to,
                getBoundingBox().expandTowards(offset).inflate(1.0), this::canHitEntity);
        return entity != null ? entity : hit;
    }

    private static Vec3 bounce(Direction face, Vec3 v) {
        return switch (face.getAxis()) {
            case X -> v.multiply(-BOUNCE / 1.5, BOUNCE, BOUNCE);
            case Y -> {
                Vec3 out = v.multiply(BOUNCE, -BOUNCE / 2.5, BOUNCE);
                yield out.y < GRAVITY ? out.multiply(1, 0, 1) : out;
            }
            case Z -> v.multiply(BOUNCE, BOUNCE, -BOUNCE / 1.5);
        };
    }

    /** Block hit at speed > 0.1 => step sound + {@code pop} + {@link #POPPED}. */
    protected void bounceSound(BlockHitResult hit, SoundEvent pop) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = level().getBlockState(pos);
        if (getDeltaMovement().length() > 0.1 && !level().isClientSide()) {
            Vec3 at = hit.getLocation();
            level().playSound(null, at.x, at.y, at.z, state.getSoundType(level(), pos, this).getStepSound(),
                    SoundSource.AMBIENT, 2.0f, 1.0f);
            level().playSound(null, at.x, at.y, at.z, pop, SoundSource.AMBIENT, 2.0f, 1.0f);
            entityData.set(POPPED, true);
        }
        state.onProjectileHit(level(), state, hit, this);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double d = Mth.clamp(getBoundingBox().getSize() * 4.0, 4.0, 64.0) * 64.0;
        return distance < d * d;
    }
}
