package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.Phase;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.sim.SimMissileManager;
import com.wf.wflib.swarm.SwarmManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Swarm membership: formation on a commander, saturation break, deconfliction between friendlies. */
public final class MissileSwarm {

    private static final double FORMATION_GAIN = 0.25;
    private static final double FORMATION_MAX_OVERSPEED = 1.6;
    private static final double SATURATION_SPREAD = 10.0;
    private static final double AVOID_RADIUS = 24.0;
    private static final int AVOID_HORIZON = 20;
    private static final double AVOID_MIN_SEP = 4.0;
    private static final double AVOID_STRENGTH = 1.5;

    private final MissileEntity missile;

    long swarmId;
    boolean commander;
    private boolean brokeFormation;
    int splitDepth;

    public MissileSwarm(MissileEntity missile) {
        this.missile = missile;
    }

    /**
     * @return the live commander this missile holds formation on, or null (none, is commander, broke off).
     *      Commander in ATTACK => saturation break: this missile takes a dispersed aim point and attacks too.
     */
    @Nullable
    MissileEntity formationLead(ServerLevel level) {
        if (this.swarmId == 0L || this.commander || this.brokeFormation) {
            return null;
        }
        MissileEntity lead = SwarmManager.commander(level, this.swarmId);
        if (lead == null || !lead.isAlive() || lead == this.missile) {
            return null;
        }
        if (lead.flight().getPhase() == Phase.ATTACK) {
            this.brokeFormation = true;
            this.missile.flight().setTarget(this.saturationAim(lead.flight().getTarget()));
            this.missile.flight().phase = Phase.ATTACK;
            this.missile.recordEvent(WFEventType.ATTACK, "saturation break");
            return null;
        }
        return lead;
    }

    /** Match the lead's velocity plus a pull toward the wedge slot, capped at an overspeed. */
    Vec3 formationGuide(MissileEntity lead) {
        Vec3 slot = SwarmManager.formationSlot(lead, this.missile);
        Vec3 leadVel = lead.getDeltaMovement();
        Vec3 desired = leadVel.add(slot.subtract(this.missile.position()).scale(FORMATION_GAIN));
        double maxSpeed = Math.max(this.missile.flight().getCruiseSpeed(), leadVel.length()) * FORMATION_MAX_OVERSPEED;
        double sp = desired.length();
        return sp > maxSpeed && sp > 1.0E-6 ? desired.scale(maxSpeed / sp) : desired;
    }

    /**
     * @return this commander's subordinates when the whole swarm may offload as one object (every member alive, in
     *      formation, clear of listeners), else null. Empty = eligible.
     */
    @Nullable
    List<MissileEntity> offloadableSubordinates(ServerLevel level) {
        List<MissileEntity> subs = new ArrayList<>();
        for (MissileEntity m : SwarmManager.members(level, this.swarmId)) {
            if (m == this.missile) {
                continue;
            }
            if (!m.isAlive() || m.isRemoved() || m.swarm().brokeFormation || m.swarm().commander) {
                return null;
            }
            if (SimMissileManager.nearAnyListener(level, m.position())) {
                return null;
            }
            subs.add(m);
        }
        return subs;
    }

    /** Golden-angle spiral keyed on entity id: spread over the target area, not stacked on one point. */
    private Vec3 saturationAim(Vec3 base) {
        int seed = this.missile.getId();
        double angle = seed * 2.399963229;
        double r = SATURATION_SPREAD * (1.0 + (seed % 3) * 0.5);
        return new Vec3(base.x + Math.cos(angle) * r, base.y, base.z + Math.sin(angle) * r);
    }

    /** Predictive deconfliction against nearby friendlies (same swarm or launcher). */
    Vec3 avoidFriendlies() {
        AABB box = this.missile.getBoundingBox().inflate(AVOID_RADIUS);
        List<MissileEntity> others = this.missile.level().getEntitiesOfClass(MissileEntity.class, box,
                m -> m != this.missile && m.isAlive() && this.missile.isFriendly(m));
        if (others.isEmpty()) {
            return Vec3.ZERO;
        }
        Vec3 pos = this.missile.position();
        Vec3 vel = this.missile.getDeltaMovement();
        double ox = 0.0, oy = 0.0, oz = 0.0;
        for (MissileEntity other : others) {
            double rpx = other.getX() - pos.x, rpy = other.getY() - pos.y, rpz = other.getZ() - pos.z;
            Vec3 ov = other.getDeltaMovement();
            double rvx = ov.x - vel.x, rvy = ov.y - vel.y, rvz = ov.z - vel.z;
            double rv2 = rvx * rvx + rvy * rvy + rvz * rvz;
            double t = rv2 < 1.0e-6 ? 0.0 : -(rpx * rvx + rpy * rvy + rpz * rvz) / rv2;
            if (t < 0.0 || t > AVOID_HORIZON) {
                continue;
            }
            double sx = rpx + rvx * t, sy = rpy + rvy * t, sz = rpz + rvz * t;
            double miss = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (miss > AVOID_MIN_SEP) {
                continue;
            }
            double urgency = (AVOID_MIN_SEP - miss) / AVOID_MIN_SEP;
            if (miss > 1.0e-3) {
                double inv = urgency / miss;
                ox -= sx * inv;
                oy -= sy * inv;
                oz -= sz * inv;
            } else {
                // Dead-on: no "away"; split sideways, sign by id.
                double clen = Math.sqrt(rvx * rvx + rvz * rvz);
                double perpx = clen > 1.0e-4 ? -rvz / clen : 1.0;
                double perpz = clen > 1.0e-4 ? rvx / clen : 0.0;
                double dir = this.missile.getId() < other.getId() ? 1.0 : -1.0;
                ox += perpx * dir * urgency;
                oz += perpz * dir * urgency;
            }
        }
        double len = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (len < 1.0e-6) {
            return Vec3.ZERO;
        }
        double scale = Math.min(AVOID_STRENGTH, len) / len;
        return new Vec3(ox * scale, oy * scale, oz * scale);
    }

    public long getSwarmId() {
        return this.swarmId;
    }

    public void setSwarmId(long swarmId) {
        this.swarmId = swarmId;
    }

    public boolean isCommander() {
        return this.commander;
    }

    public void setCommander(boolean commander) {
        this.commander = commander;
    }

    public int getSplitDepth() {
        return this.splitDepth;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("SwarmId", this.swarmId);
        tag.putBoolean("Commander", this.commander);
        tag.putBoolean("BrokeFormation", this.brokeFormation);
        tag.putInt("SplitDepth", this.splitDepth);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.swarmId = tag.getLong("SwarmId");
        this.commander = tag.getBoolean("Commander");
        this.brokeFormation = tag.getBoolean("BrokeFormation");
        this.splitDepth = tag.getInt("SplitDepth");
    }
}
