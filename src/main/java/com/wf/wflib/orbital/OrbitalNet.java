package com.wf.wflib.orbital;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The entry point for everything in orbit. */
public final class OrbitalNet {

    private OrbitalNet() {
    }

    /**
     * Put a bird on orbit.
     *
     * @return the launcher's receipt, which a block entity can persist.
     */
    public static SatHandle orbit(ServerLevel level, SatSpec spec, OrbitElements elements) {
        WorldThread.assertOn("orbital launch");
        OrbitalRegistry registry = OrbitalRegistry.get(level);
        Satellite sat = registry.add(spec, elements);
        sat.payload().onOrbit(level, sat);
        return new SatHandle(sat.satId(), sat.netId());
    }

    /**
     * Hold station over a point, burning fuel for as long as it holds.
     *
     * @return false if the tank could not pay to reach station, in which case nothing was spent.
     */
    public static boolean park(ServerLevel level, SatId id, double x, double z) {
        WorldThread.assertOn("orbital park");
        Satellite sat = OrbitalRegistry.get(level).byId(id);
        if (sat == null || !sat.alive()) {
            return false;
        }
        boolean ok = sat.park(x, z);
        if (ok) {
            OrbitalRegistry.get(level).setDirty();
        }
        return ok;
    }

    /** Break station and resume orbiting. */
    public static boolean unpark(ServerLevel level, SatId id) {
        WorldThread.assertOn("orbital unpark");
        Satellite sat = OrbitalRegistry.get(level).byId(id);
        if (sat == null || !sat.parked()) {
            return false;
        }
        sat.unpark(level.getGameTime());
        OrbitalRegistry.get(level).setDirty();
        return true;
    }

    /** Bring it down: a command, a kill, a decayed orbit, or an unclean removal. */
    public static void deorbit(ServerLevel level, SatId id, DeorbitReason why) {
        WorldThread.assertOn("orbital deorbit");
        OrbitalRegistry registry = OrbitalRegistry.get(level);
        Satellite sat = registry.byId(id);
        if (sat == null) {
            return;
        }
        sat.kill();
        registry.remove(id);
    }

    /** A snapshot of one bird as it is this tick. */
    public static Optional<SatView> view(ServerLevel level, SatId id) {
        Satellite sat = OrbitalRegistry.get(level).byId(id);
        return sat == null ? Optional.empty() : Optional.of(sat.view(level.getGameTime()));
    }

    /**
     * @return the bird answering to this callsign, whatever network it is on. Callsigns are not enforced
     *      unique; the first match wins, which is a nuisance a player fixes by renaming one of them.
     */
    public static Optional<SatView> byCallsign(ServerLevel level, String callsign) {
        Satellite sat = OrbitalRegistry.get(level).byCallsign(callsign);
        return sat == null ? Optional.empty() : Optional.of(sat.view(level.getGameTime()));
    }

    /** Everything of this network whose footprint currently covers {@code (x, z)}. */
    public static List<SatView> overhead(ServerLevel level, long netId, double x, double z) {
        long now = level.getGameTime();
        List<SatView> out = new ArrayList<>();
        for (Satellite sat : OrbitalRegistry.get(level).onNet(netId)) {
            Vec3 at = sat.position(now);
            double swath = sat.swath();
            double dx = at.x - x;
            double dz = at.z - z;
            if (dx * dx + dz * dz <= swath * swath) {
                out.add(sat.view(now));
            }
        }
        return out;
    }

    /** @return every bird on a network, in launch order. */
    public static List<SatView> onNet(ServerLevel level, long netId) {
        long now = level.getGameTime();
        List<SatView> out = new ArrayList<>();
        for (Satellite sat : OrbitalRegistry.get(level).onNet(netId)) {
            out.add(sat.view(now));
        }
        return out;
    }

    /** @return every bird in this dimension, for a readout or an admin tool. */
    public static List<SatView> all(ServerLevel level) {
        long now = level.getGameTime();
        List<SatView> out = new ArrayList<>();
        for (Satellite sat : OrbitalRegistry.get(level).all()) {
            out.add(sat.view(now));
        }
        return out;
    }

    /**
     * String in, string out: the whole command surface, in the shape NTM's redstone-over-radio established and for
     * the same reason: a bus a redstone contraption and a GUI can both drive without either learning a Java type.
     *
     * @param issuer who to record in the audit log. "Who fired the death ray" is a question a server admin
     *      will ask, and the answer has to exist before they ask it.
     * @return the reply, or a line beginning {@code err:}. Never null.
     */
    public static String commandBy(ServerLevel level, SatId id, String issuer, String verb, String... args) {
        WorldThread.assertOn("orbital command");
        OrbitalRegistry registry = OrbitalRegistry.get(level);
        Satellite sat = registry.byId(id);
        if (sat == null || !sat.alive()) {
            return "err: no such satellite";
        }
        return OrbitalCommandBus.submit(level, registry, sat, verb, args, issuer);
    }

    /** As {@link #commandBy}, attributed to the machine rather than a named issuer. */
    public static String command(ServerLevel level, SatId id, String verb, String... args) {
        return commandBy(level, id, "machine", verb, args);
    }

    /**
     * Read a value without being able to change anything.
     *
     * @return the value, or {@code err:} if the name is not a pure verb.
     */
    public static String value(ServerLevel level, SatId id, String name) {
        Satellite sat = OrbitalRegistry.get(level).byId(id);
        if (sat == null) {
            return "err: no such satellite";
        }
        for (SatVerb verb : OrbitalCommandBus.verbs(sat)) {
            if (verb.name().equalsIgnoreCase(name) && !verb.pure()) {
                return "err: '" + name + "' changes something - use command()";
            }
        }
        return OrbitalCommandBus.submit(level, OrbitalRegistry.get(level), sat, name, new String[0], "read");
    }

    /**
     * @return every verb this bird answers, core and payload together, so a GUI can build itself from the
     *      satellite rather than from a hard-coded list that goes stale.
     */
    public static List<SatVerb> verbs(ServerLevel level, SatId id) {
        Satellite sat = OrbitalRegistry.get(level).byId(id);
        return sat == null ? List.of() : OrbitalCommandBus.verbs(sat);
    }

    /** What this network's ground stations know about <em>other people's</em> birds. */
    public static List<TrackedObject> catalogue(ServerLevel level, long netId) {
        return OrbitalRegistry.get(level).catalogue(netId);
    }

    /**
     * Send a manifest down on a shuttle.
     *
     * @param propelled true for a soft touchdown with the full manifest, false for a ballistic capsule that
     *      craters, damages the ground it lands on, and loses a quarter of what it was carrying.
     * @return the shuttle's id, which is also the key its manifest is held under until it lands.
     */
    public static java.util.UUID dropCargo(ServerLevel level, Satellite self, double x, double z,
                                           List<net.minecraft.world.item.ItemStack> manifest, boolean propelled) {
        WorldThread.assertOn("orbital cargo drop");
        java.util.UUID shuttle = OrbitalDrop.release(level, self, x, z,
                propelled ? OrbitalDrop.CARGO_LANDING_SOFT : OrbitalDrop.CARGO_LANDING,
                OrbitalConfig.SHUTTLE_SPEED, 2.0f);
        CargoStore.get(level).put(shuttle, manifest);
        return shuttle;
    }

    /**
     * Put a kinetic rod on a coordinate.
     *
     * @return the rod's id.
     */
    public static java.util.UUID dropRod(ServerLevel level, Satellite self, double x, double z) {
        WorldThread.assertOn("orbital rod release");
        return OrbitalDrop.release(level, self, x, z, OrbitalDrop.KINETIC_ROD, OrbitalConfig.ROD_SPEED, 0.6f);
    }

    /** The per-level tick. */
    public static void tick(ServerLevel level) {
        OrbitalManager.tick(level);
    }

    /** Drop everything keyed by dimension. */
    public static void shutdown() {
        OrbitalCommandBus.shutdown();
    }
}
