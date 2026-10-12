package com.wf.wflib.countermeasure;

import com.wf.wflib.api.SeekerDecoy;
import com.wf.wflib.recon.Band;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Decoy flare (+ chaff): seduces thermal and radar seekers; burns out after {@link #LIFE} or on any block. */
public class FlareEntity extends Projectile implements SeekerDecoy {

    public static final int LIFE = 200;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private boolean lerp;

    public FlareEntity(EntityType<? extends FlareEntity> type, Level level) {
        super(type, level);
    }

    /** Thermal vs a jet at full power ~1-1.5. */
    @Override
    public float decoyStrength(Band band) {
        return switch (band) {
            case THERMAL -> 2.0f;
            case RADAR -> 1.0f;
            default -> 0.0f;
        };
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerp = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            if (lerp) {
                setPos(lerpX, lerpY, lerpZ);
                lerp = false;
            }
            if (tickCount % 2 == 0) {
                level().addParticle(ParticleTypes.FLASH, true, xo, yo, zo, 0, 0, 0);
            }
            for (int i = 0; i < (tickCount < 60 ? 1 : 3); i++) {
                level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, xo, yo, zo, 0, 0, 0);
            }
            if (tickCount == 1) {
                com.wf.wflib.countermeasure.client.FlareSound.play(this);
            }
            return;
        }
        if (tickCount > LIFE) {
            discard();
            return;
        }
        Vec3 from = position();
        Vec3 to = from.add(getDeltaMovement());
        if (level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() != HitResult.Type.MISS) {
            discard();
            return;
        }
        setPos(to);
        setDeltaMovement(getDeltaMovement().scale(0.99).add(0, -0.1, 0));
    }

    @Override
    public boolean shouldRender(double x, double y, double z) {
        return true;
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
