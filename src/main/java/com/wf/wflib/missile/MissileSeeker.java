package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.api.BeamSource;
import com.wf.wflib.api.SeekerDecoy;
import com.wf.wflib.api.SeekerLockEvent;
import com.wf.wflib.api.SightBlocker;
import com.wf.wflib.api.TargetIlluminator;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.SignatureRegistry;
import com.wf.wflib.drone.cam.CameraSource;
import com.wf.wflib.drone.cam.CameraSpec;
import com.wf.wflib.tv.TvGuidance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.List;
import java.util.UUID;

/**
 * What the missile homes on: a {@link SeekerMode} lock (lead-pursued), or an operator flying it through its camera
 * ({@code spec} set = TV round). Operator on => line of sight flown; operator gone => lock what the sight line
 * last held. Lock lost => flies on to the last aim point.
 */
public final class MissileSeeker {

    /** Blocks the release lock looks down the sight line. */
    public static final double LOCK_RANGE = 512.0;
    /** Lock ray stops at the first unloaded chunk: a clip there loads (and generates) it on the tick thread. */
    private static final double LOCK_STEP = 8.0;

    /** Radar-lock warning cadence. */
    private static final int WARN_INTERVAL = 2;
    /** Active radar without a lock this long => self-destruct. */
    private static final int ACTIVE_LOST_TICKS = 60;
    /** Decoys compete within this distance of the lock. */
    private static final double DECOY_RADIUS = 16.0;
    /** Beam riding: aim this many ticks of travel ahead along the beam. */
    private static final double BEAM_LEAD_TICKS = 4.0;

    private final MissileEntity missile;

    @Nullable
    CameraSpec spec;
    SeekerMode mode = SeekerMode.DESIGNATED;
    /** Seeker half-angle, degrees. */
    float fov = 30.0f;
    /** Active radar: own seeker switches on inside this range of the lock, and scans this far. */
    double radarRange = 1024.0;
    private boolean radarOn;
    private int lostTicks;
    /** Decoys already rolled against: each gets one chance per missile. Transient. */
    private final IntSet judged = new IntOpenHashSet();
    @Nullable
    UUID designatedTargetId;
    /** Unit line of sight last ordered. */
    private Vec3 aim = Vec3.ZERO;
    private boolean operated;

    public MissileSeeker(MissileEntity missile) {
        this.missile = missile;
    }

    /** MC yaw/pitch (degrees, pitch + = down) to a unit vector. */
    public static Vec3 sight(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    void tick(ServerLevel level) {
        if (this.spec != null) {
            this.tickOperator(level);
        }
        if (this.missile.interceptor().isActive() || this.operated) {
            return;
        }
        switch (this.mode) {
            case DESIGNATED -> {
                Entity designated = this.lock(level);
                if (designated != null) {
                    this.missile.flight().setTarget(designated.getBoundingBox().getCenter());
                }
            }
            case INFRARED, OPTICAL -> this.home(level, this.opticalLock(level));
            case SEMI_ACTIVE_RADAR -> this.home(level, this.decoyed(level, this.illuminated(level)));
            case ACTIVE_RADAR -> this.home(level, this.activeLock(level));
            case BEAM_RIDING -> this.rideBeam(level);
        }
    }

    @Nullable
    private Entity lock(ServerLevel level) {
        if (this.designatedTargetId == null) {
            return null;
        }
        Entity e = level.getEntity(this.designatedTargetId);
        return e != null && e.isAlive() && e != this.missile ? e : null;
    }

    /** Lock kept => aim at its lead point (warn if emitting); lost => fly on to the last aim point. */
    private void home(ServerLevel level, @Nullable Entity lock) {
        this.designatedTargetId = lock == null ? null : lock.getUUID();
        if (lock == null) {
            return;
        }
        Vec3 at = lock.getBoundingBox().getCenter();
        Vec3 v = lock.getDeltaMovement();
        double t = Lead.time(at.subtract(this.missile.position()), v, this.missile.flight().getCruiseSpeed());
        this.missile.flight().setTarget(t > 0.0 ? at.add(v.scale(t)) : at);
        if (this.mode.emits() && this.missile.tickCount % WARN_INTERVAL == 0) {
            NeoForge.EVENT_BUS.post(new SeekerLockEvent(this.missile, lock, this.mode));
        }
    }

    /** IR/EO: lost behind opaque blocks or smoke, or outside the seeker cone; IR may take a flare. */
    @Nullable
    private Entity opticalLock(ServerLevel level) {
        Entity lock = this.lock(level);
        if (lock == null || !this.inCone(lock) || !this.canSee(level, lock)) {
            return null;
        }
        return this.decoyed(level, lock);
    }

    /** Launcher's fire-control track (semi-active, active midcourse); null when it has none. */
    @Nullable
    private Entity illuminated(ServerLevel level) {
        Entity launcher = this.launcher(level);
        return launcher instanceof TargetIlluminator illuminator ? illuminator.illuminatedTarget(this.missile) : null;
    }

    /** Midcourse on the launcher's track; inside {@link #radarRange} on its own radar; lost too long => destruct. */
    @Nullable
    private Entity activeLock(ServerLevel level) {
        Entity lock = this.lock(level);
        if (!this.radarOn) {
            Entity track = this.illuminated(level);
            if (track != null) {
                lock = track;
            } else if (this.launcher(level) instanceof TargetIlluminator) {
                lock = null;
            }
            if (lock != null && lock.distanceToSqr(this.missile) <= this.radarRange * this.radarRange) {
                this.radarOn = true;
                this.missile.recordEvent(WFEventType.OPERATOR, "radar on");
            }
            return lock;
        }
        Entity held = lock != null && this.inCone(lock) && this.radarVisible(lock) ? lock : null;
        if (held == null) {
            held = this.scan(level);
        }
        if (held == null) {
            if (++this.lostTicks >= ACTIVE_LOST_TICKS) {
                this.missile.fuze().detonate(this.missile.position(), true);
            }
            return null;
        }
        this.lostTicks = 0;
        return this.decoyed(level, held);
    }

    /** Own radar: radar-reflective, hostile, inside the cone and range; nearest the boresight wins. */
    @Nullable
    private Entity scan(ServerLevel level) {
        Entity best = null;
        double bestAngle = Double.MAX_VALUE;
        AABB box = this.missile.getBoundingBox().inflate(this.radarRange);
        for (Entity e : level.getEntities(this.missile, box, e -> e.isAlive() && !e.isSpectator())) {
            if (e.getVehicle() != null || e == this.launcher(level) || !this.radarVisible(e)
                    || e.distanceToSqr(this.missile) > this.radarRange * this.radarRange
                    || e instanceof MissileEntity other && this.missile.isFriendly(other)) {
                continue;
            }
            double angle = this.offBoresight(e);
            if (angle <= this.fov && angle < bestAngle) {
                bestAngle = angle;
                best = e;
            }
        }
        return best;
    }

    private boolean radarVisible(Entity e) {
        return SignatureRegistry.of(e).radarRcs() > 0.0f;
    }

    /**
     * Each new decoy in the mode's band near the lock and in the cone gets one roll:
     * p = strength / (strength + lock signature in that band). Seduced => the decoy is the lock.
     */
    @Nullable
    private Entity decoyed(ServerLevel level, @Nullable Entity lock) {
        Band band = this.mode.band();
        if (lock == null || band == null || lock instanceof SeekerDecoy) {
            return lock;
        }
        float lockWeight = SignatureRegistry.of(lock).on(band);
        List<Entity> decoys = level.getEntities(lock, lock.getBoundingBox().inflate(DECOY_RADIUS),
                e -> e instanceof SeekerDecoy d && d.decoyStrength(band) > 0.0f && !this.judged.contains(e.getId())
                        && this.inCone(e));
        for (Entity e : decoys) {
            this.judged.add(e.getId());
            float strength = ((SeekerDecoy) e).decoyStrength(band);
            if (this.missile.getRandom().nextFloat() * (strength + lockWeight) < strength) {
                this.missile.recordEvent(WFEventType.OPERATOR, "decoyed");
                return e;
            }
        }
        return lock;
    }

    /** Steer onto the launcher's beam, a few ticks of travel ahead of the projection. */
    private void rideBeam(ServerLevel level) {
        if (!(this.launcher(level) instanceof BeamSource source)) {
            return;
        }
        BeamSource.Beam beam = source.guidanceBeam(this.missile);
        if (beam == null) {
            return;
        }
        Vec3 rel = this.missile.position().subtract(beam.origin());
        double along = Math.max(0.0, rel.dot(beam.direction()))
                + this.missile.flight().getCruiseSpeed() * BEAM_LEAD_TICKS;
        this.missile.flight().setTarget(beam.origin().add(beam.direction().scale(along)));
    }

    private boolean inCone(Entity e) {
        return this.offBoresight(e) <= this.fov;
    }

    /** Degrees between the heading and the line to {@code e}. */
    private double offBoresight(Entity e) {
        Vec3 heading = CameraSource.Guided.heading(this.missile);
        Vec3 to = e.getBoundingBox().getCenter().subtract(this.missile.position());
        double len = to.length();
        if (len < 1.0E-6) {
            return 0.0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, heading.dot(to) / len))));
    }

    /** No occluding block and no {@link SightBlocker} between the nose and {@code e}. */
    private boolean canSee(ServerLevel level, Entity e) {
        Vec3 from = this.missile.position();
        Vec3 to = e.getBoundingBox().getCenter();
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, this.missile));
        if (block.getType() != HitResult.Type.MISS) {
            BlockPos pos = block.getBlockPos();
            if (level.getBlockState(pos).canOcclude()) {
                return false;
            }
        }
        for (Entity blocker : level.getEntities(this.missile, new AABB(from, to).inflate(1.0),
                b -> b instanceof SightBlocker)) {
            if (blocker.getBoundingBox().clip(from, to).isPresent() || blocker.getBoundingBox().contains(from)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private Entity launcher(ServerLevel level) {
        UUID control = this.missile.getControlId();
        Entity byControl = control == null ? null : level.getEntity(control);
        return byControl != null ? byControl : this.missile.getOwner();
    }

    public SeekerMode mode() {
        return this.mode;
    }

    private void tickOperator(ServerLevel level) {
        Vec3 order = TvGuidance.order(level, this.missile);
        if (order != null) {
            if (!this.operated) {
                this.operated = true;
                this.designatedTargetId = null;
                this.missile.recordEvent(WFEventType.OPERATOR, "on");
            }
            this.aim = order;
        } else if (this.operated) {
            this.operated = false;
            this.release(level);
        }
    }

    /** Operator's order this tick, or null when flying on the stages. Not during ASCEND. */
    @Nullable
    Vec3 orderedVelocity() {
        return this.operated && this.missile.flight().getPhase() != MissileEntity.Phase.ASCEND
                ? this.aim.scale(this.missile.flight().getCruiseSpeed()) : null;
    }

    /**
     * Aim point = first thing on the sight line. Entity => designated (homes on it); block => that point;
     * nothing => {@link #LOCK_RANGE} on along the line.
     */
    private void release(ServerLevel level) {
        Vec3 heading = CameraSource.Guided.heading(this.missile);
        Vec3 dir = this.aim.lengthSqr() < 1.0E-8 ? heading : this.aim.normalize();
        Vec3 from = this.missile.position().add(heading.scale(this.missile.noseForward()));
        Vec3 to = from;
        for (double d = LOCK_STEP; d <= LOCK_RANGE; d += LOCK_STEP) {
            Vec3 next = from.add(dir.scale(d));
            if (!level.isLoaded(BlockPos.containing(next))) {
                break;
            }
            to = next;
        }
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this.missile));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, this.missile, from, end,
                new AABB(from, end).inflate(1.0),
                e -> e.isPickable() && !e.isSpectator() && !(e instanceof MissileEntity), 0.0f);
        if (hit != null) {
            this.designatedTargetId = hit.getEntity().getUUID();
            this.missile.flight().setTarget(hit.getEntity().getBoundingBox().getCenter());
            this.missile.recordEvent(WFEventType.OPERATOR, "entity");
            return;
        }
        this.designatedTargetId = null;
        this.missile.flight().setTarget(block.getType() == HitResult.Type.MISS ? from.add(dir.scale(LOCK_RANGE)) : end);
        this.missile.recordEvent(WFEventType.OPERATOR, block.getType() == HitResult.Type.MISS ? "sight" : "block");
    }

    /** Camera of a TV round; null = not operable. */
    @Nullable
    public CameraSpec spec() {
        return this.spec;
    }

    /** Server side. */
    public boolean operated() {
        return this.operated;
    }

    public void setDesignatedTarget(@Nullable UUID entityId) {
        this.designatedTargetId = entityId;
    }

    @Nullable
    public UUID getDesignatedTargetId() {
        return this.designatedTargetId;
    }

    public boolean hasDesignatedTarget() {
        return this.designatedTargetId != null;
    }

    public boolean hasLiveDesignatedTarget() {
        if (this.designatedTargetId == null || !(this.missile.level() instanceof ServerLevel sl)) {
            return false;
        }
        Entity e = sl.getEntity(this.designatedTargetId);
        return e != null && e.isAlive();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (this.spec != null) {
            tag.put("Spec", CameraSpec.CODEC.encodeStart(NbtOps.INSTANCE, this.spec).getOrThrow());
        }
        if (this.designatedTargetId != null) {
            tag.putUUID("Designated", this.designatedTargetId);
        }
        tag.putString("Mode", this.mode.name());
        tag.putFloat("Fov", this.fov);
        tag.putDouble("RadarRange", this.radarRange);
        tag.putBoolean("RadarOn", this.radarOn);
        tag.putInt("LostTicks", this.lostTicks);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.spec = tag.contains("Spec") ? CameraSpec.CODEC.parse(NbtOps.INSTANCE, tag.get("Spec")).getOrThrow() : null;
        this.designatedTargetId = tag.hasUUID("Designated") ? tag.getUUID("Designated") : null;
        this.mode = SeekerMode.valueOf(tag.getString("Mode"));
        this.fov = tag.getFloat("Fov");
        this.radarRange = tag.getDouble("RadarRange");
        this.radarOn = tag.getBoolean("RadarOn");
        this.lostTicks = tag.getInt("LostTicks");
    }
}
