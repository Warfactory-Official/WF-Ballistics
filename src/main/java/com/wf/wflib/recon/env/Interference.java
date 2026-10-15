package com.wf.wflib.recon.env;

import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.Countermeasure;
import com.wf.wflib.recon.EmconState;
import com.wf.wflib.recon.Signature;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.TargetSink;
import com.wf.wflib.recon.TargetSource;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Area effects on the recon picture, main thread: RF jammer (RADAR; also cuts {@code Datalink}), fire glare
 * (THERMAL), blast noise (SEISMIC). Applied at collection as a countermeasure on what is in reach; {@code heat > 0}
 * also presents a thermal-only UNKNOWN contact at the centre (decoy, resolves to no entity).
 */
public final class Interference implements TargetSource {

    public enum Scope {
        /** Targets inside the radius present less. */
        TARGET,
        /** Sensors inside the radius hear less of everything. */
        SENSOR
    }

    /**
     * @param factor multiplier on the band's signature
     * @param until game time it lapses
     * @param heat thermal signature of the centre contact; 0 = none
     */
    public record Area(Band band, Scope scope, Vec3 at, double radius, float factor, long until, float heat) {

        boolean covers(double x, double y, double z) {
            return this.at.distanceToSqr(x, y, z) <= this.radius * this.radius;
        }
    }

    private record Effect(Area area) implements Countermeasure {

        @Override
        public Band band() {
            return this.area.band;
        }

        @Override
        public Signature modify(Signature in, TargetSnapshot self, SensorSnapshot sensor) {
            if (this.area.scope == Scope.SENSOR && !this.area.covers(sensor.x(), sensor.eyeY(), sensor.z())) {
                return in;
            }
            return in.scaled(this.area.band, this.area.factor);
        }
    }

    private static final Map<ResourceKey<Level>, Map<Object, Area>> BY_LEVEL = new HashMap<>();
    private static final Map<Object, Integer> HEAT_IDS = new HashMap<>();
    private static int nextHeatId = 1;

    /** Adds or replaces {@code key}'s area (a carried jammer re-puts each tick). */
    public static void put(ServerLevel level, Object key, Area area) {
        WorldThread.assertOn("interference");
        BY_LEVEL.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>()).put(key, area);
    }

    /** Live areas, lapsed ones pruned. */
    public static List<Area> live(ServerLevel level) {
        Map<Object, Area> areas = BY_LEVEL.get(level.dimension());
        if (areas == null || areas.isEmpty()) {
            return List.of();
        }
        long now = level.getGameTime();
        List<Area> out = new ArrayList<>(areas.size());
        for (Iterator<Map.Entry<Object, Area>> it = areas.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Area> e = it.next();
            if (e.getValue().until <= now) {
                it.remove();
                HEAT_IDS.remove(e.getKey());
            } else {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** Product of every {@code band} TARGET area covering {@code at}. */
    public static float factorAt(ServerLevel level, Band band, Vec3 at) {
        float f = 1.0f;
        for (Area a : live(level)) {
            if (a.band == band && a.scope == Scope.TARGET && a.covers(at.x, at.y, at.z)) {
                f *= a.factor;
            }
        }
        return f;
    }

    /** {@code snapshot} plus every area that reaches it; heat contacts are never their own glare. */
    public static TargetSnapshot apply(List<Area> areas, TargetSnapshot snapshot) {
        if (areas.isEmpty() || SourceIds.isHeat(snapshot.sourceId())) {
            return snapshot;
        }
        List<Countermeasure> extra = null;
        for (Area a : areas) {
            if (a.scope == Scope.SENSOR || a.covers(snapshot.x(), snapshot.y(), snapshot.z())) {
                if (extra == null) {
                    extra = new ArrayList<>(2);
                }
                extra.add(new Effect(a));
            }
        }
        if (extra == null) {
            return snapshot;
        }
        Countermeasure[] carried = snapshot.countermeasure();
        Countermeasure[] all = new Countermeasure[carried.length + extra.size()];
        System.arraycopy(carried, 0, all, 0, carried.length);
        for (int i = 0; i < extra.size(); i++) {
            all[carried.length + i] = extra.get(i);
        }
        return new TargetSnapshot(snapshot.sourceId(), snapshot.kind(), snapshot.x(), snapshot.y(), snapshot.z(),
                snapshot.vx(), snapshot.vy(), snapshot.vz(), snapshot.signature(), all, snapshot.emcon(),
                snapshot.iffCode(), snapshot.buried(), snapshot.inWater());
    }

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        Map<Object, Area> areas = BY_LEVEL.get(level.dimension());
        if (areas == null) {
            return;
        }
        long now = level.getGameTime();
        for (Map.Entry<Object, Area> e : areas.entrySet()) {
            Area a = e.getValue();
            if (a.heat <= 0.0f || a.until <= now || !volume.contains(a.at)) {
                continue;
            }
            int id = HEAT_IDS.computeIfAbsent(e.getKey(), k -> nextHeatId++);
            sink.accept(new TargetSnapshot(SourceIds.ofHeat(id), ContactClass.UNKNOWN, a.at.x, a.at.y, a.at.z,
                    0, 0, 0, new Signature(0, 0, a.heat, 0, 0), TargetSnapshot.NO_COUNTERMEASURES,
                    EmconState.ACTIVE, 0L, false));
        }
    }

    public static void shutdown() {
        BY_LEVEL.clear();
        HEAT_IDS.clear();
    }
}
