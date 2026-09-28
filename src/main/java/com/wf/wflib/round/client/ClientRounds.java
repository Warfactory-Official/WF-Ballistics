package com.wf.wflib.round.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.RoundNetwork;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.round.pen.BlockPen;
import com.wf.wflib.round.pen.PenTable;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Client copies of the server's rounds: same recurrence, no collision; removed by the server's end batch. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ClientRounds {

    private static final Long2ObjectMap<Round> LIVE = new Long2ObjectOpenHashMap<>();
    /** Shooter-side predictions awaiting their server round, alive or already ended on a block. */
    private static final List<Round> PREDICTED = new ArrayList<>();
    /** Server keys whose adopted prediction already ended locally: their later spawn/end syncs are ignored. */
    private static final LongOpenHashSet ENDED_LOCALLY = new LongOpenHashSet();
    private static long nextLocalKey = -1;
    private static final List<RoundObserver> OBSERVERS = new CopyOnWriteArrayList<>();
    private static ClientLevel effectLevel;

    private ClientRounds() {
    }

    public static final class Round {
        long key;
        final KineticPreset preset;
        final int shooter;
        final int seq;
        double x, y, z, px, py, pz, vx, vy, vz;
        /** First known position (spawn / prediction): a tracer never streaks behind it. */
        final double ox, oy, oz;
        int life;
        boolean resting;
        /** Stepped by a client tick at least once. */
        boolean ticked;
        /** Ended before its first client tick: that tick flies straight here, then ends. */
        RoundNetwork.End pendingEnd;
        /** Shooter-side prediction (or one adopted by its server round): client block clip, own end. */
        boolean local;
        /** Unadopted prediction: ticks left to meet its server round. */
        int adoptTicks = -1;
        boolean ended;
        /** Ticks stepped. */
        int age;
        /** Launch velocity (a prediction's direction vs its server round's). */
        final double ivx, ivy, ivz;
        /** Position correction per tick for {@link #corrTicks} more ticks (prediction re-aimed onto its server round). */
        double cx, cy, cz;
        int corrTicks;

        Round(long key, KineticPreset preset, int shooter, int seq, Vec3 pos, Vec3 vel, int life) {
            this.key = key;
            this.preset = preset;
            this.shooter = shooter;
            this.seq = seq;
            this.x = this.px = this.ox = pos.x;
            this.y = this.py = this.oy = pos.y;
            this.z = this.pz = this.oz = pos.z;
            this.vx = this.ivx = vel.x;
            this.vy = this.ivy = vel.y;
            this.vz = this.ivz = vel.z;
            this.life = life;
        }

        Round(long key, KineticPreset preset, RoundNetwork.Spawn s) {
            this.shooter = s.shooter();
            this.seq = s.seq();
            this.key = key;
            this.preset = preset;
            this.ox = s.x();
            this.oy = s.y();
            this.oz = s.z();
            this.ivx = s.vx();
            this.ivy = s.vy();
            this.ivz = s.vz();
            this.sync(s);
        }

        /** Server state replaces the prediction (spawn, landing, burrowing); snaps, no interpolation. */
        void sync(RoundNetwork.Spawn s) {
            this.x = this.px = s.x();
            this.y = this.py = s.y();
            this.z = this.pz = s.z();
            this.vx = s.vx();
            this.vy = s.vy();
            this.vz = s.vz();
            this.life = s.life();
            this.resting = s.resting();
        }

        public long key() {
            return this.key;
        }

        public KineticPreset preset() {
            return this.preset;
        }

        /** Shooter entity id; -1 none. */
        public int shooterId() {
            return this.shooter;
        }

        /** Shot sequence; {@code Rounds.NO_SEQ} none. */
        public int seq() {
            return this.seq;
        }

        public Vec3 position() {
            return new Vec3(this.x, this.y, this.z);
        }

        /** Position before the last step. */
        public Vec3 previous() {
            return new Vec3(this.px, this.py, this.pz);
        }

        public Vec3 velocity() {
            return new Vec3(this.vx, this.vy, this.vz);
        }

        public boolean resting() {
            return this.resting;
        }

        double ix(float pt) {
            return this.px + (this.x - this.px) * pt;
        }

        double iy(float pt) {
            return this.py + (this.y - this.py) * pt;
        }

        double iz(float pt) {
            return this.pz + (this.z - this.pz) * pt;
        }
    }

    /** Observers see every round, own shots included (filter on {@link Round#shooterId}). */
    public static void addObserver(RoundObserver observer) {
        OBSERVERS.add(observer);
    }

    /**
     * Shooter-side prediction of a shot the server has yet to launch: flies the same recurrence at once against
     * loaded blocks (ends there, or pierces them as the server would). The first server spawn with this {@code shooterId} + {@code seq} adopts it (keeps
     * this flight, takes the server key and end); none within {@code adoptTicks} => removed. Client thread.
     */
    public static void predict(ResourceLocation preset, int shooterId, int seq, Vec3 pos, Vec3 velocity,
                               int adoptTicks) {
        KineticPreset p = KineticPresetRegistry.get(preset);
        if (p == null) {
            throw new IllegalArgumentException("unknown preset " + preset);
        }
        Round r = new Round(nextLocalKey--, p, shooterId, seq, pos, velocity, p.lifeTicks());
        r.local = true;
        r.adoptTicks = adoptTicks;
        LIVE.put(r.key, r);
        PREDICTED.add(r);
    }

    /**
     * Server spawn of a predicted shot: rekeys the prediction; true => spawn consumed. Launched along another line
     * (server-side spread): re-aimed onto the server round's flight at its own age (velocity at once, position over
     * {@link #CORRECT_TICKS}); already ended locally => the server round flies on its own.
     */
    private static boolean adopt(RoundNetwork.Spawn s) {
        for (int i = 0; i < PREDICTED.size(); i++) {
            Round r = PREDICTED.get(i);
            if (r.shooter != s.shooter() || r.seq != s.seq()) {
                continue;
            }
            PREDICTED.remove(i);
            boolean same = sameLaunch(r, s);
            if (r.ended) {
                if (same) {
                    ENDED_LOCALLY.add(s.key());
                }
                return same;
            }
            LIVE.remove(r.key);
            RoundRenderer renderer = RoundRenderers.get(r.preset.id());
            if (renderer != null) {
                renderer.ended(r.key);
            }
            r.key = s.key();
            r.adoptTicks = -1;
            if (!same) {
                Vec3 before = r.position();
                reaim(r, s);
                resynced(r, before);
            }
            LIVE.put(r.key, r);
            return true;
        }
        return false;
    }

    static final int CORRECT_TICKS = 2;
    /** Launch directions closer than this (radians) are one line. */
    static final double SAME_LINE = 1.0E-3;

    private static boolean sameLaunch(Round r, RoundNetwork.Spawn s) {
        double a = Math.sqrt(r.ivx * r.ivx + r.ivy * r.ivy + r.ivz * r.ivz);
        double b = Math.sqrt(s.vx() * s.vx() + s.vy() * s.vy() + s.vz() * s.vz());
        if (a < 1.0E-9 || b < 1.0E-9) {
            return a < 1.0E-9 && b < 1.0E-9;
        }
        double cos = (r.ivx * s.vx() + r.ivy * s.vy() + r.ivz * s.vz()) / (a * b);
        return cos > Math.cos(SAME_LINE);
    }

    /** Server flight {@code r.age} steps from its spawn (no collision, dry), then eased onto. */
    private static void reaim(Round r, RoundNetwork.Spawn s) {
        double x = s.x(), y = s.y(), z = s.z(), vx = s.vx(), vy = s.vy(), vz = s.vz();
        for (int k = 0; k < r.age; k++) {
            x += vx;
            y += vy;
            z += vz;
            double decay = r.preset.decay(Math.sqrt(vx * vx + vy * vy + vz * vz));
            vx *= decay;
            vy = vy * decay - r.preset.gravity();
            vz *= decay;
        }
        r.vx = vx;
        r.vy = vy;
        r.vz = vz;
        r.cx = (x - r.x) / CORRECT_TICKS;
        r.cy = (y - r.y) / CORRECT_TICKS;
        r.cz = (z - r.z) / CORRECT_TICKS;
        r.corrTicks = CORRECT_TICKS;
    }

    /** Live rounds of one shot (pellets share a seq): predicted-tracer adoption. */
    public static List<Round> byShot(int shooterId, int seq) {
        List<Round> out = new ArrayList<>(1);
        for (Round r : LIVE.values()) {
            if (r.shooter == shooterId && r.seq == seq) {
                out.add(r);
            }
        }
        return out;
    }

    public static void accept(RoundNetwork.RoundPacket pkt) {
        apply(pkt);
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level != effectLevel && FlywheelEffectManager.isAvailable(level)) {
            effectLevel = level;
            FlywheelEffectManager.spawn(new RoundsEffect(level));
        }
    }

    static void apply(RoundNetwork.RoundPacket pkt) {
        for (RoundNetwork.Spawn s : pkt.spawns()) {
            if (ENDED_LOCALLY.contains(s.key())) {
                continue;
            }
            Round known = LIVE.get(s.key());
            if (known != null) {
                if (!known.local) {
                    Vec3 before = known.position();
                    known.sync(s);
                    resynced(known, before);
                }
                continue;
            }
            if (s.seq() != Rounds.NO_SEQ && !PREDICTED.isEmpty() && adopt(s)) {
                continue;
            }
            KineticPreset preset = KineticPresetRegistry.get(s.preset());
            if (preset != null) {
                LIVE.put(s.key(), new Round(s.key(), preset, s));
            }
        }
        for (RoundNetwork.Pierce p : pkt.pierces()) {
            if (p.exited() && ENDED_LOCALLY.contains(p.key())) {
                revive(pkt, p.key());
            }
            Round known = LIVE.get(p.key());
            if (!ENDED_LOCALLY.contains(p.key()) && (known == null || !known.local)) {
                for (RoundObserver o : OBSERVERS) {
                    o.pierced(p);
                }
            }
        }
        for (RoundNetwork.End e : pkt.ends()) {
            if (ENDED_LOCALLY.remove(e.key())) {
                continue;
            }
            Round r = LIVE.get(e.key());
            if (r == null) {
                continue;
            }
            if (!r.ticked) {
                r.pendingEnd = e;
            } else {
                LIVE.remove(e.key());
                ended(r, e.position(), e.reason());
            }
        }
    }

    /** Prediction stopped on a block the server round went through: its latest spawn becomes a plain round. */
    private static void revive(RoundNetwork.RoundPacket pkt, long key) {
        for (int k = pkt.spawns().size() - 1; k >= 0; k--) {
            RoundNetwork.Spawn s = pkt.spawns().get(k);
            KineticPreset preset = KineticPresetRegistry.get(s.preset());
            if (s.key() == key && preset != null) {
                ENDED_LOCALLY.remove(key);
                LIVE.put(key, new Round(key, preset, s));
                return;
            }
        }
    }

    private static void resynced(Round r, Vec3 before) {
        for (RoundObserver o : OBSERVERS) {
            o.resynced(r, before);
        }
    }

    @Nullable
    static Round get(long key) {
        return LIVE.get(key);
    }

    static Collection<Round> live() {
        return LIVE.values();
    }

    static boolean alive(ClientLevel level) {
        return level == effectLevel;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        tick(mc.level);
    }

    static void tick(ClientLevel level) {
        PREDICTED.removeIf(r -> {
            if (--r.adoptTicks > 0) {
                return false;
            }
            if (!r.ended && LIVE.remove(r.key) != null) {
                ended(r, r.position(), RoundEnd.CLEARED);
            }
            return true;
        });
        LIVE.values().removeIf(r -> {
            RoundNetwork.End end = r.pendingEnd;
            if (end != null) {
                r.px = r.x;
                r.py = r.y;
                r.pz = r.z;
                r.x = end.x();
                r.y = end.y();
                r.z = end.z();
                ticked(r);
                ended(r, end.position(), end.reason());
                return true;
            }
            Vec3 stop = step(level, r);
            if (stop == EXPIRED) {
                r.ended = true;
                ended(r, r.position(), RoundEnd.EXPIRED);
                return true;
            }
            if (stop != null) {
                r.x = stop.x;
                r.y = stop.y;
                r.z = stop.z;
                r.ended = true;
                if (r.adoptTicks < 0) {
                    ENDED_LOCALLY.add(r.key);
                }
                ticked(r);
                ended(r, r.position(), RoundEnd.BLOCK);
                return true;
            }
            ticked(r);
            return false;
        });
    }

    private static void ticked(Round r) {
        r.ticked = true;
        RoundRenderer renderer = RoundRenderers.get(r.preset.id());
        if (renderer != null) {
            renderer.tick(r.key, new Vec3(r.x, r.y, r.z), new Vec3(r.vx, r.vy, r.vz), r.resting);
        }
        for (RoundObserver o : OBSERVERS) {
            o.tick(r);
        }
    }

    private static void ended(Round r, Vec3 at, RoundEnd reason) {
        RoundRenderer renderer = RoundRenderers.get(r.preset.id());
        if (renderer != null) {
            renderer.ended(r.key);
        }
        for (RoundObserver o : OBSERVERS) {
            o.ended(r, at, reason);
        }
    }

    private static void clear() {
        for (Round r : LIVE.values()) {
            ended(r, r.position(), RoundEnd.CLEARED);
        }
        LIVE.clear();
        PREDICTED.clear();
        ENDED_LOCALLY.clear();
    }

    /** {@link #step} result: life spent. */
    private static final Vec3 EXPIRED = new Vec3(Double.NaN, Double.NaN, Double.NaN);

    /** One tick of the server recurrence. @return null flying, {@link #EXPIRED}, else a local round's block stop */
    @Nullable
    private static Vec3 step(ClientLevel level, Round r) {
        if (--r.life <= 0) {
            return EXPIRED;
        }
        r.px = r.x;
        r.py = r.y;
        r.pz = r.z;
        r.age++;
        if (r.corrTicks > 0) {
            r.corrTicks--;
            r.x += r.cx;
            r.y += r.cy;
            r.z += r.cz;
        }
        if (r.resting) {
            return null;
        }
        boolean wet = !level.getFluidState(BlockPos.containing(r.x, r.y, r.z)).isEmpty();
        if (r.local) {
            Vec3 stop = fly(level, r);
            if (stop != null) {
                return stop;
            }
        } else {
            r.x += r.vx;
            r.y += r.vy;
            r.z += r.vz;
        }
        double decay = r.preset.decay(Math.sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz));
        float gravity = r.preset.gravity();
        if (wet) {
            decay = (double) (1.0f - Math.max(r.preset.waterDrag(), 0.0f));
            gravity *= r.preset.waterGravityFactor();
        }
        r.vx *= decay;
        r.vy = r.vy * decay - gravity;
        r.vz *= decay;
        return null;
    }

    /**
     * Local round's segment against loaded blocks, pierced as the server does ({@code Rounds}: same
     * {@link BlockPen#pass}, shot seed). @return block stop point; null => moved (velocity replaced on a pierce)
     */
    @Nullable
    private static Vec3 fly(ClientLevel level, Round r) {
        Vec3 from = new Vec3(r.x, r.y, r.z);
        Vec3 v = new Vec3(r.vx, r.vy, r.vz);
        Vec3 to = from.add(v);
        int pierced = 0;
        while (true) {
            BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.BLOCK) {
                break;
            }
            if (r.preset.blockPen() <= 0.0f || pierced >= BlockPen.MAX_PER_STEP) {
                return hit.getLocation();
            }
            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            BlockPen.Pass pass = BlockPen.pass(state.getCollisionShape(level, pos), pos, hit.getLocation(), v,
                    PenTable.resistance(state), BlockPen.capacity(r.preset.blockPen(), r.preset.muzzleSpeed(),
                            v.length()), BlockPen.seed(BlockPen.shot(r.shooter, r.seq), pos));
            RoundNetwork.Pierce event = new RoundNetwork.Pierce(r.key, pos.asLong(), hit.getLocation().x,
                    hit.getLocation().y, hit.getLocation().z, hit.getDirection(), pass.point().x, pass.point().y,
                    pass.point().z, pass.exited());
            for (RoundObserver o : OBSERVERS) {
                o.pierced(event);
            }
            if (!pass.exited()) {
                return pass.point();
            }
            double left = Math.max(0.0, from.distanceTo(to) - from.distanceTo(pass.point())) / v.length();
            v = pass.velocity();
            from = BlockPen.resume(pass.point(), v);
            to = from.add(v.scale(left));
            pierced++;
        }
        if (pierced > 0) {
            r.x = to.x;
            r.y = to.y;
            r.z = to.z;
            r.vx = v.x;
            r.vy = v.y;
            r.vz = v.z;
        } else {
            r.x += r.vx;
            r.y += r.vy;
            r.z += r.vz;
        }
        return null;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        effectLevel = null;
    }
}
