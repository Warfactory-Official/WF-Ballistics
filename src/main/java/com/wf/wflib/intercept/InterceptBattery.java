package com.wf.wflib.intercept;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.missile.Lead;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconNetwork;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.fc.FireControl;
import com.wf.wflib.recon.track.IffState;
import com.wf.wflib.recon.track.Track;
import com.wf.wflib.recon.track.TrackQuality;
import com.wf.wflib.sim.IMissileListener;
import com.wf.wflib.sim.MissileListenerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Automatic air defence fire control: pick a track off a recon net picture, claim it, lay, fire interceptors locked
 * on it. The {@link Mount} is the launcher (a turret block, a vehicle's rack).
 */
public final class InterceptBattery {

    public static final Set<ContactClass> ENGAGED_CLASSES = EnumSet.of(ContactClass.MISSILE, ContactClass.DRONE);
    /** Widest track error a battery launches on. */
    public static final double MAX_TRACK_ERROR = 32.0;
    /** Ticks a battery holds its claim on a track. */
    public static final int CLAIM_TICKS = 120;
    /** Listener reach past {@link Settings#range}: sim missiles materialise before they are in range. */
    private static final double LISTENER_MARGIN = 80.0;
    private static final int SWEEP_TICKS = 4;
    /** Not laid by then => give the track up. */
    private static final int LAY_TIMEOUT = 100;

    /**
     * @param organic register own surveillance radar ({@code range}, {@code mast}) on the net; false => network
     *                coverage only
     * @param salvo   interceptors per track
     * @param spacing ticks between rounds of a salvo
     * @param cooldown ticks after a salvo
     */
    public record Settings(double range, boolean organic, double mast, int salvo, int spacing, int cooldown) {
    }

    /** The launcher a battery fires from. */
    public interface Mount {

        /** Where range and lead are measured from. */
        Vec3 muzzle();

        UUID controlId();

        /** Side; null = unaffiliated. */
        @Nullable
        UUID team();

        /** Net read (and fed when organic). */
        long netId();

        /** Speed of the next interceptor; NaN = none ready. */
        double interceptorSpeed();

        /**
         * Lay toward the lead point.
         *
         * @return true once laid
         */
        boolean lay(Vec3 aimPoint);

        /** @return true if an interceptor locked on {@code target} left */
        boolean fire(InterceptTarget target);

        boolean valid();
    }

    /** {@link #tick} outcome, for status displays. */
    public enum State {
        IDLE, NO_ROUND, NO_PICTURE, LAYING, FIRED, COOLDOWN
    }

    private final Mount mount;
    private final long claimant;
    /** {@link BlockPos} = saved block listener (wakes its chunk); {@link UUID} = entity listener. */
    private final Object listenerKey;
    private final Listener listener = new Listener();
    private double listenRange;
    @Nullable
    private UUID target;
    @Nullable
    private ReconNetwork targetNet;
    @Nullable
    private Track targetTrack;
    private int left;
    private int wait;
    private int laying;
    @Nullable
    private BlockPos sensorAt;
    private long sensorNet;

    public InterceptBattery(Mount mount, long claimant, Object listenerKey) {
        this.mount = mount;
        this.claimant = claimant;
        this.listenerKey = listenerKey;
    }

    @Nullable
    public UUID target() {
        return this.target;
    }

    public State tick(ServerLevel sl, Settings settings) {
        this.listenRange = settings.range() + LISTENER_MARGIN;
        MissileListenerRegistry.get(sl).register(this.listenerKey, this.listener);
        long net = this.mount.netId();
        if (settings.organic()) {
            BlockPos at = BlockPos.containing(this.mount.muzzle());
            if (this.sensorAt != null && (!this.sensorAt.equals(at) || this.sensorNet != net)) {
                ReconNet.unregisterSensor(sl, this.sensorAt, this.sensorNet);
            }
            ReconNet.registerSensor(sl, at, SensorSpec.surveillanceRadar(net, settings.range())
                    .withMast(settings.mast()).withSweep(SWEEP_TICKS));
            this.sensorAt = at;
            this.sensorNet = net;
        } else {
            this.dropSensor(sl);
        }
        if (this.wait > 0) {
            this.wait--;
            return this.target == null ? State.COOLDOWN : State.LAYING;
        }
        double speed = this.mount.interceptorSpeed();
        if (Double.isNaN(speed)) {
            this.forget();
            return State.NO_ROUND;
        }
        InterceptTarget engaged = this.engaged(sl);
        if (engaged == null) {
            ReconNetwork picture = ReconNet.existing(sl, net);
            if (picture == null) {
                return State.NO_PICTURE;
            }
            engaged = this.acquire(sl, picture, settings.range());
            if (engaged == null) {
                return State.IDLE;
            }
            this.left = settings.salvo();
            this.laying = 0;
        }
        this.targetNet.claim(this.targetTrack.id(), this.claimant, sl.getGameTime(), CLAIM_TICKS);
        Entity e = engaged.interceptEntity();
        Vec3 muzzle = this.mount.muzzle();
        Vec3 d = e.position().subtract(muzzle);
        double t = Lead.time(d, e.getDeltaMovement(), speed);
        if (!this.mount.lay(t > 0.0 ? e.position().add(e.getDeltaMovement().scale(t)) : e.position())) {
            if (++this.laying > LAY_TIMEOUT) {
                this.forget();
            }
            return State.LAYING;
        }
        if (!this.mount.fire(engaged)) {
            return State.LAYING;
        }
        if (--this.left > 0) {
            this.wait = settings.spacing();
        } else {
            this.forget();
            this.wait = settings.cooldown();
        }
        return State.FIRED;
    }

    /** The current target, still worth a shot, or null (forgotten). */
    @Nullable
    private InterceptTarget engaged(ServerLevel sl) {
        if (this.target == null) {
            return null;
        }
        if (sl.getEntity(this.target) instanceof InterceptTarget t && this.engageable(t)) {
            return t;
        }
        this.forget();
        return null;
    }

    private void forget() {
        this.target = null;
        this.targetNet = null;
        this.targetTrack = null;
    }

    /**
     * Nearest confirmed, non-friendly, unclaimed track in range that resolves to a hostile entity. Class
     * {@code UNKNOWN} counts: classification needs a strong return, which a distant missile does not give.
     */
    @Nullable
    private InterceptTarget acquire(ServerLevel sl, ReconNetwork net, double range) {
        long now = sl.getGameTime();
        Vec3 m = this.mount.muzzle();
        double r2 = range * range;
        List<Track> candidates = net.picture().tracks().stream()
                .filter(t -> (t.guess() == ContactClass.UNKNOWN || ENGAGED_CLASSES.contains(t.guess()))
                        && t.quality().atLeast(TrackQuality.CONFIRMED)
                        && t.iff() != IffState.FRIENDLY
                        && t.distanceSqTo(m.x, m.y, m.z) <= r2
                        && !net.claimedByOther(t.id(), this.claimant, now))
                .sorted(Comparator.comparingDouble(t -> t.distanceSqTo(m.x, m.y, m.z)))
                .toList();
        for (Track track : candidates) {
            Entity resolved = FireControl.resolve(sl, Entity.class, track, MAX_TRACK_ERROR,
                    e -> e instanceof InterceptTarget t && this.engageable(t));
            if (resolved != null) {
                this.target = resolved.getUUID();
                this.targetNet = net;
                this.targetTrack = track;
                return (InterceptTarget) resolved;
            }
        }
        return null;
    }

    private boolean engageable(InterceptTarget t) {
        return t.interceptEngageable() && hostile(t, this.mount.controlId(), this.mount.team());
    }

    /** Not fired by {@code controlId}, not a friendly side's. */
    public static boolean hostile(InterceptTarget t, UUID controlId, @Nullable UUID team) {
        return !controlId.equals(t.interceptControlId()) && !WarforgeCompat.areFactionsFriendly(team, t.interceptTeamId());
    }

    /** Stamp an interceptor as fired by {@code controlId} for {@code team}, locked on {@code target}. */
    public static void arm(MissileEntity interceptor, UUID controlId, @Nullable UUID team, Entity target) {
        interceptor.setControlId(controlId);
        interceptor.setTeamId(team);
        interceptor.interceptor().setLock(target.getUUID());
    }

    /**
     * Engageable target nearest the look ray within {@code cone} radians, out to {@code range}. Either side: the
     * client lock pick reads what it is tracking.
     */
    @Nullable
    public static Entity lockCandidate(Level level, Vec3 eye, Vec3 look, double range, double cone,
                                       Predicate<Entity> filter) {
        Vec3 dir = look.normalize();
        Vec3 end = eye.add(dir.scale(range));
        double cos = Math.cos(cone);
        Entity best = null;
        double bestCos = cos;
        for (Entity e : level.getEntities((Entity) null, new AABB(eye, end).inflate(range * Math.tan(cone) + 1.0),
                e -> e instanceof InterceptTarget t && t.interceptEngageable() && filter.test(e))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double len = to.length();
            if (len < 1.0e-3 || len > range) {
                continue;
            }
            double c = to.dot(dir) / len;
            if (c > bestCos) {
                bestCos = c;
                best = e;
            }
        }
        return best;
    }

    /** Leave the world: listener, organic sensor. */
    public void release(ServerLevel sl) {
        MissileListenerRegistry.get(sl).deregister(this.listenerKey);
        this.dropSensor(sl);
        this.forget();
    }

    private void dropSensor(ServerLevel sl) {
        if (this.sensorAt != null) {
            ReconNet.unregisterSensor(sl, this.sensorAt, this.sensorNet);
            this.sensorAt = null;
        }
    }

    private final class Listener implements IMissileListener {

        @Override
        public Vec3 listenerCenter() {
            return InterceptBattery.this.mount.muzzle();
        }

        @Override
        public double listenerRange() {
            return InterceptBattery.this.listenRange;
        }

        @Override
        public boolean listenerValid() {
            return InterceptBattery.this.mount.valid();
        }
    }
}
