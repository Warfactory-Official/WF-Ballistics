package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.fc.FireControl;
import com.wf.wflib.recon.track.Track;
import com.wf.wflib.sim.IMissileListener;
import com.wf.wflib.sim.MissileListenerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TurretCiwsBlockEntity extends BlockEntity implements IMissileListener, ReconBound {

    //tuning
    /**
     * Firing/acquisition radius (blocks).
     */
    public static final double RANGE = 64.0;
    /**
     * Detection radius as a listener: larger than RANGE so sim missiles materialize before entering it.
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
    /** Widest track error this mount will try to shoot from. */
    public static final double MAX_TRACK_ERROR = 8.0;

    // Server-side aim direction (unit vector); gates firing so fast/crossing targets are harder to track.
    private Vec3 aimDir = new Vec3(0.0, 1.0, 0.0);
    private int cooldown = 0;
    private UUID cachedTeamId = null;
    private int teamRefresh = 0;
    // Which net this mount feeds, when the claim is not the answer. See ReconBound.
    @Nullable
    private UUID bound = null;

    public TurretCiwsBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TURRET_CIWS.get(), pos, state);
    }

    private static boolean hasLineOfSight(ServerLevel sl, Vec3 from, Vec3 to) {
        BlockHitResult res = sl.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
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
        if (--this.teamRefresh <= 0) {
            this.resolve(sl);
            this.teamRefresh = 100;
        }
        ReconNet.registerSensor(sl, this.worldPosition,
                SensorSpec.fireControlRadar(ReconNet.netId(this.cachedTeamId), RANGE).withMast(0.5));

        if (this.cooldown > 0) {
            this.cooldown--;
        }

        Vec3 muzzle = Vec3.atCenterOf(this.worldPosition).add(0.0, 0.5, 0.0);
        Track track = this.acquireTrack(sl, muzzle);
        if (track == null) {
            return;
        }

        long now = sl.getGameTime();
        Vec3 aimPoint = new Vec3(track.predictedX(now), track.predictedY(now), track.predictedZ(now));
        Vec3 toTrack = aimPoint.subtract(muzzle);
        double dist = toTrack.length();
        if (dist < 1.0E-4) {
            return;
        }
        this.aimDir = slew(this.aimDir, toTrack.scale(1.0 / dist), TURN_RATE);

        Entity resolved = FireControl.resolve(sl, Entity.class, track, MAX_TRACK_ERROR,
                e -> e instanceof InterceptTarget t && t.interceptEngageable());
        if (resolved == null) {
            return;
        }
        InterceptTarget target = (InterceptTarget) resolved;
        Vec3 targetCenter = resolved.getBoundingBox().getCenter();
        if (!hasLineOfSight(sl, muzzle, targetCenter)) {
            return;
        }
        Vec3 targetDir = targetCenter.subtract(muzzle).normalize();
        double aimError = Math.acos(Mth_clamp(this.aimDir.dot(targetDir)));
        if (aimError <= AIM_TOLERANCE && this.cooldown <= 0) {
            this.cooldown = FIRE_INTERVAL;
            this.fire(sl, muzzle, targetCenter, target);
        }
    }

    /** The nearest missile contact on this mount's network, or null. */
    private Track acquireTrack(ServerLevel sl, Vec3 muzzle) {
        return ReconNet.picture(sl, ReconNet.netId(this.cachedTeamId))
                .nearest(muzzle.x, muzzle.y, muzzle.z, RANGE,
                        t -> t.guess() == ContactClass.MISSILE || t.guess() == ContactClass.DRONE);
    }

    private void fire(ServerLevel sl, Vec3 muzzle, Vec3 targetCenter, InterceptTarget target) {
        if (sl.random.nextFloat() < HIT_CHANCE) {
            float dmg = DAMAGE_MIN + sl.random.nextFloat() * DAMAGE_VAR;
            target.interceptDamage(dmg);
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
            ReconNet.unregisterSensor(sl, this.worldPosition, ReconNet.netId(this.cachedTeamId));
        }
        super.setRemoved();
    }

    /**
     * Work out whose mount this is: the binding if it has one, and otherwise whoever claims the ground under it.
     */
    private void resolve(ServerLevel sl) {
        this.cachedTeamId = this.bound != null ? this.bound
                : ReconOwners.owningAt(sl, this.worldPosition);
    }

    @Override
    public long netId() {
        return ReconNet.netId(this.cachedTeamId);
    }

    @Nullable
    @Override
    public UUID boundNet() {
        return this.bound;
    }

    @Override
    public void bindNet(@Nullable UUID id) {
        ServerLevel sl = this.level instanceof ServerLevel s ? s : null;
        if (sl != null) {
            ReconNet.unregisterSensor(sl, this.worldPosition, this.netId());
        }
        this.bound = id;
        this.setChanged();
        if (sl != null) {
            this.resolve(sl);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.bound != null) {
            tag.putUUID("BoundNet", this.bound);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.bound = tag.hasUUID("BoundNet") ? tag.getUUID("BoundNet") : null;
    }
}
