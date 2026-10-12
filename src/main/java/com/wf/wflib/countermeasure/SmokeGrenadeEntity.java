package com.wf.wflib.countermeasure;

import com.wf.wflib.WFSounds;
import com.wf.wflib.api.SightBlocker;
import com.wf.wflib.client.particle.WFParticles;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Pops on its first fast block hit into a 10-block screen. */
public class SmokeGrenadeEntity extends CountermeasureGrenade implements SightBlocker {

    static final int LIFE = 600;
    static final double SCREEN = 10.0;
    private static final int PUFFS = 12;

    public SmokeGrenadeEntity(EntityType<? extends SmokeGrenadeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected int life() {
        return LIFE;
    }

    @Override
    protected AABB makeBoundingBox() {
        return popped() ? AABB.ofSize(position(), SCREEN, SCREEN, SCREEN) : AABB.ofSize(position(), 0.3, 0.3, 0.3);
    }

    @Override
    protected void onHit(HitResult hit) {
        if (hit instanceof BlockHitResult block) {
            bounceSound(block, WFSounds.SMOKE_POP.get());
        } else if (hit instanceof EntityHitResult e && getDeltaMovement().length() > 0.1 && !level().isClientSide()) {
            e.getEntity().hurt(damageSources().thrown(this, getOwner()), 1.0f);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() && popped()) {
            for (int i = 0; i < PUFFS; i++) {
                level().addParticle(WFParticles.SMOKE_SCREEN.get(), true, getX() + random.triangle(0, SCREEN),
                        getY() + random.triangle(0, SCREEN * 0.8), getZ() + random.triangle(0, SCREEN), 0, 0, 0);
            }
        }
    }
}
