package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.sim.IMissileListener;
import com.wf.wfballistics.sim.MissileListenerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;


public class TurretCiwsBlockEntity extends BlockEntity implements IMissileListener {

    //tuning
    /**
     * Firing/acquisition radius (blocks).
     */
    public static final double RANGE = 64.0;
    /**
     * Detection radius as a listener — larger than RANGE so sim missiles materialize before entering it.
     */
    public static final double LISTENER_RANGE = 96.0;
    /**
     * Ticks between shots (HBM CIWS fires every 2 ticks).
     */
    public static final int FIRE_INTERVAL = 2;
    /**
     * Per-shot hit probability.
     */
    public static final float HIT_CHANCE = 0.5f;
    /**
     * Damage applied per successful hit: DAMAGE_MIN + rand[0, DAMAGE_VAR).
     */
    public static final float DAMAGE_MIN = 2.0f;
    public static final float DAMAGE_VAR = 2.0f;
    /**
     * Max slew per tick (radians) and the alignment cone within which it will fire.
     */
    public static final double TURN_RATE = Math.toRadians(15.0);
    public static final double AIM_TOLERANCE = Math.toRadians(10.0);

    // Server-side aim direction (unit vector); gates firing so fast/crossing targets are harder to track.
    private Vec3 aimDir = new Vec3(0.0, 1.0, 0.0);
    private int cooldown = 0;

    public TurretCiwsBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TURRET_CIWS.get(), pos, state);
    }

    private static boolean hasLineOfSight(ServerLevel sl, Vec3 from, Vec3 to) {
        BlockHitResult res = sl.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        return res.getType() == HitResult.Type.MISS;
    }

    /**
     * Rotates {@code from} toward {@code to} by at most {@code maxAngle} radians (both unit vectors).
     */
    private static Vec3 slew(Vec3 from, Vec3 to, double maxAngle) {
        double dot = Mth_clamp(from.dot(to));
        double angle = Math.acos(dot);
        if (angle <= maxAngle || angle < 1.0E-6) {
            return to;
        }
        Vec3 axis = from.cross(to);
        if (axis.lengthSqr() < 1.0E-12) {
            // Antiparallel: pick any perpendicular axis to swing through.
            Vec3 reference = Math.abs(from.y) < 0.99 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
            axis = from.cross(reference);
        }
        axis = axis.normalize();
        return from.scale(Math.cos(maxAngle))
                .add(axis.cross(from).scale(Math.sin(maxAngle)))
                .normalize();
    }

    private static void spawnTracer(ServerLevel sl, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double dist = delta.length();
        int steps = (int) Math.min(24, Math.max(2, dist / 3.0));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / (steps + 1);
            Vec3 p = from.add(delta.scale(t));
            sl.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        sl.sendParticles(ParticleTypes.SMOKE, from.x, from.y, from.z, 2, 0.02, 0.02, 0.02, 0.0);
    }

    private static double Mth_clamp(double dot) {
        return dot < -1.0 ? -1.0 : (dot > 1.0 ? 1.0 : dot);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        // Pull simulated missiles back into the real world as they approach, so we can actually shoot them.
        MissileListenerRegistry.get(sl).register(this.worldPosition, this);

        if (this.cooldown > 0) {
            this.cooldown--;
        }

        Vec3 muzzle = Vec3.atCenterOf(this.worldPosition).add(0.0, 0.5, 0.0);
        MissileEntity target = this.acquireTarget(sl, muzzle);
        if (target == null) {
            return;
        }

        Vec3 targetCenter = target.getBoundingBox().getCenter();
        Vec3 toTarget = targetCenter.subtract(muzzle);
        double dist = toTarget.length();
        if (dist < 1.0E-4) {
            return;
        }
        Vec3 targetDir = toTarget.scale(1.0 / dist);

        // Slew toward the target, capped at TURN_RATE per tick, then fire only once roughly on-aim.
        this.aimDir = slew(this.aimDir, targetDir, TURN_RATE);
        double aimError = Math.acos(Mth_clamp(this.aimDir.dot(targetDir)));
        if (aimError <= AIM_TOLERANCE && this.cooldown <= 0) {
            this.cooldown = FIRE_INTERVAL;
            this.fire(sl, muzzle, targetCenter, target);
        }
    }

    /**
     * Nearest live missile within RANGE that has clear line-of-sight, or null.
     */
    private MissileEntity acquireTarget(ServerLevel sl, Vec3 muzzle) {
        AABB box = new AABB(this.worldPosition).inflate(RANGE);
        List<MissileEntity> candidates = sl.getEntitiesOfClass(MissileEntity.class, box, e -> !e.isRemoved());
        MissileEntity best = null;
        double bestSq = RANGE * RANGE;
        for (MissileEntity m : candidates) {
            Vec3 c = m.getBoundingBox().getCenter();
            double dsq = c.distanceToSqr(muzzle);
            if (dsq <= bestSq && m.detectableAt(dsq, sl.random) && hasLineOfSight(sl, muzzle, c)) {
                bestSq = dsq;
                best = m;
            }
        }
        return best;
    }

    private void fire(ServerLevel sl, Vec3 muzzle, Vec3 targetCenter, MissileEntity target) {
        if (sl.random.nextFloat() < HIT_CHANCE) {
            float dmg = DAMAGE_MIN + sl.random.nextFloat() * DAMAGE_VAR;
            target.damageMissile(dmg);
        }
        spawnTracer(sl, muzzle, targetCenter);
    }

    @Override
    public Vec3 listenerCenter() {
        return Vec3.atCenterOf(this.worldPosition);
    }

    @Override
    public double listenerRange() {
        return LISTENER_RANGE;
    }

    @Override
    public boolean listenerValid() {
        return !this.isRemoved() && this.level instanceof ServerLevel sl && sl.isLoaded(this.worldPosition);
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            MissileListenerRegistry.get(sl).deregister(this.worldPosition);
        }
        super.setRemoved();
    }
}
