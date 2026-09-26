package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.InterceptMode;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.sim.MissileListenerRegistry;
import com.wf.wflib.sim.MissileSimConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Interceptor role: acquire an air target, fly a lead point, roll the kill on closest approach. */
public final class MissileInterceptor {

    private final MissileEntity missile;

    boolean active;
    InterceptMode mode = InterceptMode.NEAREST;
    @Nullable
    UUID lockTargetId;
    float chance = MissileSimConfig.DEFAULT_INTERCEPT_CHANCE;
    @Nullable
    private UUID currentTargetId;
    private int noTargetTicks;
    /** No timed intercept exists: only cutting across the target's path, rolled at a reduced chance. */
    private boolean crossingShot;

    public MissileInterceptor(MissileEntity missile) {
        this.missile = missile;
    }

    /**
     * Targets claimed by interceptors in {@code missiles}, as seen by claimant {@code selfId}
     * ({@link Integer#MAX_VALUE} = a battery, yields to all). LOCK always claims; NEAREST claims its current target
     * only against higher-id peers => one keeps a shared target, the rest divert, no mutual swapping.
     */
    public static Set<UUID> claimedTargets(List<MissileEntity> missiles, int selfId) {
        Set<UUID> claimed = new HashSet<>();
        for (MissileEntity o : missiles) {
            MissileInterceptor i = o.interceptor();
            if (!i.active || o.getId() == selfId) {
                continue;
            }
            if (i.mode == InterceptMode.LOCK && i.lockTargetId != null) {
                claimed.add(i.lockTargetId);
            } else if (i.currentTargetId != null && o.getId() < selfId) {
                claimed.add(i.currentTargetId);
            }
        }
        return claimed;
    }

    public boolean isActive() {
        return this.active;
    }

    public float getChance() {
        return this.chance;
    }

    /** Switch to LOCK on one target. */
    public void setLock(UUID targetId) {
        this.mode = InterceptMode.LOCK;
        this.lockTargetId = targetId;
    }

    /**
     * Stay registered as a listener, resolve the target, aim at the lead point.
     * @return false if the interceptor removed itself this tick
     */
    boolean update(ServerLevel level, Vec3 currentPos) {
        MissileListenerRegistry.get(level).register(this.missile.getUUID(), this.missile);
        if (this.missile.tickCount >= MissileSimConfig.INTERCEPTOR_LIFETIME_TICKS) {
            this.missile.fuze().detonate(currentPos, true);
            return false;
        }
        InterceptTarget target = this.resolveTarget(level, currentPos);
        if (target == null) {
            this.currentTargetId = null;
            if (++this.noTargetTicks > MissileSimConfig.INTERCEPTOR_LOST_TARGET_TICKS) {
                this.missile.fuze().detonate(currentPos, true);
                return false;
            }
            Vec3 vel = this.missile.getDeltaMovement();
            Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(0.0, 1.0, 0.0);
            this.missile.flight().setTarget(currentPos.add(dir.scale(32.0)));
            return true;
        }
        this.currentTargetId = target.interceptEntity().getUUID();
        this.noTargetTicks = 0;
        this.missile.flight().setTarget(this.leadPoint(currentPos, target));
        return true;
    }

    /** LOCK: exactly the lock target (any side). NEAREST: closest live hostile non-interceptor in acquire range. */
    @Nullable
    private InterceptTarget resolveTarget(ServerLevel level, Vec3 currentPos) {
        if (this.mode == InterceptMode.LOCK) {
            if (this.lockTargetId != null && level.getEntity(this.lockTargetId) instanceof InterceptTarget t
                    && t.interceptEngageable()) {
                return t;
            }
            return null;
        }
        double r = MissileSimConfig.INTERCEPTOR_ACQUIRE_RANGE;
        AABB box = this.missile.getBoundingBox().inflate(r);
        List<MissileEntity> missiles = level.getEntitiesOfClass(MissileEntity.class, box, MissileEntity::isAlive);
        List<InterceptTarget> nearby = new ArrayList<>(missiles);
        nearby.addAll(level.getEntitiesOfClass(DroneEntity.class, box, DroneEntity::isAlive));
        Set<UUID> claimed = claimedTargets(missiles, this.missile.getId());
        InterceptTarget best = null;
        InterceptTarget fallback = null;
        double bestSq = r * r;
        double fallbackSq = r * r;
        for (InterceptTarget t : nearby) {
            if (t == this.missile || !t.interceptEngageable() || this.missile.isFriendly(t)) {
                continue;
            }
            Entity entity = t.interceptEntity();
            double dsq = entity.position().distanceToSqr(currentPos);
            if (t instanceof MissileEntity m && !m.signature().detectableAt(dsq, r)) {
                continue;
            }
            if (dsq <= fallbackSq) {
                fallbackSq = dsq;
                fallback = t;
            }
            if (!claimed.contains(entity.getUUID()) && dsq <= bestSq) {
                bestSq = dsq;
                best = t;
            }
        }
        return best != null ? best : fallback;
    }

    /** Aim so a run at cruise speed meets the target's straight-line motion; else cut across its path. */
    private Vec3 leadPoint(Vec3 currentPos, InterceptTarget target) {
        Vec3 tPos = target.interceptEntity().getBoundingBox().getCenter();
        Vec3 vt = target.interceptEntity().getDeltaMovement();
        Vec3 d = tPos.subtract(currentPos);
        double t = Lead.time(d, vt, this.missile.flight().getCruiseSpeed());
        if (t > 0.0) {
            this.crossingShot = false;
            return tPos.add(vt.scale(t));
        }
        this.crossingShot = true;
        double vlen2 = vt.lengthSqr();
        if (vlen2 < 1.0E-8) {
            return tPos;
        }
        double u = Math.max(0.0, -d.dot(vt) / vlen2);
        return tPos.add(vt.scale(u));
    }

    /**
     * Closest-approach kill test over this tick's motion.
     * @return true if resolved (kill or spent); caller stops ticking
     */
    boolean tryIntercept(Vec3 currentPos) {
        if (this.currentTargetId == null || !(this.missile.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!(level.getEntity(this.currentTargetId) instanceof InterceptTarget tgt) || !tgt.interceptEngageable()) {
            return false;
        }
        Entity tgtEntity = tgt.interceptEntity();
        Vec3 iV = this.missile.getDeltaMovement();
        Vec3 tEnd = tgtEntity.getBoundingBox().getCenter();
        Vec3 tV = tgtEntity.getDeltaMovement();
        Vec3 rp = tEnd.subtract(tV).subtract(currentPos);
        Vec3 rv = tV.subtract(iV);
        double rv2 = rv.lengthSqr();
        double tStar = rv2 < 1.0E-9 ? 0.0 : Mth.clamp(-rp.dot(rv) / rv2, 0.0, 1.0);
        if (rp.add(rv.scale(tStar)).length() > MissileSimConfig.INTERCEPTOR_KILL_RADIUS) {
            return false;
        }
        Vec3 point = currentPos.add(iV.scale(tStar));
        float chance = this.crossingShot
                ? this.chance * MissileSimConfig.INTERCEPTOR_CROSSING_HIT_FACTOR : this.chance;
        boolean kill = level.random.nextFloat() < chance;
        if (kill && tgt.interceptEvade(this.missile.flight().getCruiseSpeed(), this.missile.position())) {
            kill = false;
        }
        burst(level, point, kill);
        if (MissileSimConfig.INTERCEPTOR_CHIP_MODE) {
            tgt.interceptDamage(kill ? MissileSimConfig.INTERCEPTOR_HIT_DAMAGE : MissileSimConfig.INTERCEPTOR_GRAZE_DAMAGE);
        } else if (kill) {
            tgt.interceptKill();
        }
        this.missile.fuze().detonate(point, true);
        return true;
    }

    /** Readable at range: big burst + boom on a kill, small puff + pop on a miss. */
    private static void burst(ServerLevel level, Vec3 p, boolean kill) {
        level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, kill ? 3 : 1, 1.0, 1.0, 1.0, 0.0);
        level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, kill ? 24 : 6, 1.4, 1.4, 1.4, 0.06);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, kill ? 14 : 5, 1.6, 1.6, 1.6, 0.02);
        level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
                kill ? 3.0f : 1.3f, kill ? 1.0f : 1.5f);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Active", this.active);
        tag.putString("Mode", this.mode.name());
        if (this.lockTargetId != null) {
            tag.putUUID("LockTargetId", this.lockTargetId);
        }
        tag.putFloat("Chance", this.chance);
        tag.putInt("NoTargetTicks", this.noTargetTicks);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.active = tag.getBoolean("Active");
        this.mode = InterceptMode.valueOf(tag.getString("Mode"));
        this.lockTargetId = tag.hasUUID("LockTargetId") ? tag.getUUID("LockTargetId") : null;
        this.chance = tag.getFloat("Chance");
        this.noTargetTicks = tag.getInt("NoTargetTicks");
    }
}
