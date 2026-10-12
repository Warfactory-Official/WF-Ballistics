package com.wf.wflib.countermeasure;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.Blasts;
import com.wf.wflib.audio.WfAudio;
import com.wf.wflib.round.RocketEntity;
import com.wf.wflib.round.Rounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Hard-kill charge: destroys the fastest projectile, rocket or entity-less round within 8 blocks, then itself. */
public class ApsGrenadeEntity extends CountermeasureGrenade {

    static final int LIFE = 1200;
    static final double REACH = 8.0;

    public ApsGrenadeEntity(EntityType<? extends ApsGrenadeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected int life() {
        return LIFE;
    }

    @Override
    protected void onHit(HitResult hit) {
        discard();
    }

    @Override
    public void tick() {
        super.tick();
        if (isRemoved() || !(level() instanceof ServerLevel level)) {
            return;
        }
        AABB box = getBoundingBox().inflate(REACH);
        Entity target = null;
        long round = 0L;
        double best = -1.0;
        for (Entity e : level.getEntities(this, box, this::catches)) {
            double speed = e.getDeltaMovement().lengthSqr();
            if (speed > best) {
                best = speed;
                target = e;
            }
        }
        for (long key : Rounds.within(level, box)) {
            double speed = Rounds.velocity(level, key).lengthSqr();
            if (speed > best) {
                best = speed;
                target = null;
                round = key;
            }
        }
        if (best < 0.0) {
            return;
        }
        Entity attacker = target instanceof Projectile p ? p.getOwner()
                : target instanceof RocketEntity r ? r.shooter() : Rounds.shooter(level, round);
        Vec3 from = position();
        Vec3 to = target != null ? target.position() : Rounds.position(level, round);
        level.sendParticles(ParticleTypes.EXPLOSION, from.x, from.y, from.z, 3, 0.2, 0.2, 0.2, 0.15);
        level.sendParticles(ParticleTypes.EXPLOSION, to.x, to.y, to.z, 3, 0.2, 0.2, 0.2, 0.15);
        Vec3 step = to.subtract(from).scale(1.0 / 16);
        for (int i = 0; i <= 16; i++) {
            Vec3 p = from.add(step.scale(i));
            level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 1, 0.0, 0.02, 0.0, 0.0);
        }
        WfAudio.at(level, from, AudioCategory.EXPLOSIONS, Blasts.pop(64, 1.6F));
        discard();
        if (target != null) {
            target.discard();
        } else {
            Rounds.destroy(level, round);
        }
        if (attacker instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("message.wflib.aps_intercept"), true);
        }
    }

    private boolean catches(Entity e) {
        return e.isAlive() && e != this && !(e instanceof CountermeasureGrenade)
                && (e instanceof Projectile || e instanceof RocketEntity);
    }
}
