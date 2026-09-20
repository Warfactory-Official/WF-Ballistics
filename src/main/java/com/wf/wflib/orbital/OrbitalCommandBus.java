package com.wf.wflib.orbital;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OrbitalCommandBus {

    static final int EXPIRY_TICKS = 6000;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<Level>, List<Pending>> QUEUES = new HashMap<>();

    private static final List<SatVerb> CORE = List.of(
            SatVerb.action("park", 2, "ok", "hold station over x z, burning fuel for as long as it holds"),
            SatVerb.action("unpark", 0, "ok", "break station and resume the orbit"),
            SatVerb.action("move", 3, "ok", "manoeuvre onto a new ground track through x z, heading degrees"),
            SatVerb.action("callsign", 1, "ok", "rename this bird"),
            SatVerb.action("deorbit", 0, "ok", "retire it"),
            SatVerb.value("pos", "coords", "sub-satellite point and altitude"),
            SatVerb.value("fuel", "number", "fuel remaining, and the tank"),
            SatVerb.value("power", "number", "charge remaining, and the battery"),
            SatVerb.value("contact", "ok", "whether a ground station can reach it right now"),
            SatVerb.value("pass", "number", "which pass it is on, and how far through"),
            SatVerb.value("status", "text", "one line covering all of the above"));

    private OrbitalCommandBus() {
    }

    static List<SatVerb> verbs(Satellite sat) {
        List<SatVerb> out = new ArrayList<>(CORE);
        out.addAll(sat.payload().verbs());
        return out;
    }

    @Nullable
    private static SatVerb find(Satellite sat, String name) {
        String want = name.toLowerCase(Locale.ROOT);
        for (SatVerb verb : CORE) {
            if (verb.name().equals(want)) {
                return verb;
            }
        }
        for (SatVerb verb : sat.payload().verbs()) {
            if (verb.name().toLowerCase(Locale.ROOT).equals(want)) {
                return verb;
            }
        }
        return null;
    }

    static String submit(ServerLevel level, OrbitalRegistry registry, Satellite sat,
                         String verb, String[] args, String issuer) {
        SatVerb spec = find(sat, verb);
        if (spec == null) {
            return "err: unknown verb '" + verb + "'";
        }
        if (spec.arity() != args.length) {
            return "err: " + spec.name() + " takes " + spec.arity() + " argument(s), got " + args.length;
        }
        long now = level.getGameTime();
        if (spec.pure()) {
            String reply = execute(level, registry, sat, spec.name(), args, now);
            sat.setReply(reply);
            return reply;
        }
        QUEUES.computeIfAbsent(level.dimension(), k -> new ArrayList<>())
                .add(new Pending(sat.rawId(), spec.name(), args, issuer, now + 1, now + EXPIRY_TICKS));
        return sat.inContact() ? "queued" : "queued (out of contact - held for the next pass)";
    }

    static void deliver(ServerLevel level, OrbitalRegistry registry, long now) {
        List<Pending> queue = QUEUES.get(level.dimension());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        for (Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (pending.due > now) {
                continue;
            }
            Satellite sat = registry.byId(new SatId(pending.satId));
            if (sat == null || !sat.alive()) {
                it.remove();
                continue;
            }
            if (!sat.inContact()) {
                if (now >= pending.expires) {
                    sat.setReply("err: " + pending.verb + " expired out of contact");
                    LOGGER.info("[wflib] orbital {} {} by {} -> expired out of contact",
                            sat.callsign(), pending.verb, pending.issuer);
                    it.remove();
                } else {
                    pending.due = now + OrbitalConfig.SLOW_TICK;
                }
                continue;
            }
            String reply = execute(level, registry, sat, pending.verb, pending.args, now);
            sat.setReply(reply);
            registry.setDirty();
            LOGGER.info("[wflib] orbital {} {}{} by {} -> {}", sat.callsign(), pending.verb,
                    pending.args.length == 0 ? "" : " " + String.join(":", pending.args),
                    pending.issuer, reply);
            it.remove();
        }
    }

    static void cancel(ServerLevel level, long satId) {
        List<Pending> queue = QUEUES.get(level.dimension());
        if (queue != null) {
            queue.removeIf(pending -> pending.satId == satId);
        }
    }

    static int pendingCount(ServerLevel level) {
        List<Pending> queue = QUEUES.get(level.dimension());
        return queue == null ? 0 : queue.size();
    }

    static void shutdown() {
        QUEUES.clear();
    }

    private static String execute(ServerLevel level, OrbitalRegistry registry, Satellite sat,
                                  String verb, String[] args, long now) {
        switch (verb) {
            case "park" -> {
                Double x = number(args[0]);
                Double z = number(args[1]);
                if (x == null || z == null) {
                    return "err: park takes two coordinates";
                }
                if (!sat.park(x, z)) {
                    return "err: not enough fuel to reach station";
                }
                registry.setDirty();
                return String.format(Locale.ROOT, "parked over %.0f %.0f, fuel %.0f", x, z, sat.fuel());
            }
            case "unpark" -> {
                if (!sat.parked()) {
                    return "err: not on station";
                }
                sat.unpark(now);
                registry.setDirty();
                return "station broken, orbit resumed";
            }
            case "move" -> {
                Double x = number(args[0]);
                Double z = number(args[1]);
                Double heading = number(args[2]);
                if (x == null || z == null || heading == null) {
                    return "err: move takes x z heading";
                }
                if (!sat.manoeuvre(now, x, z, Math.toRadians(heading))) {
                    return "err: not enough fuel to manoeuvre";
                }
                registry.setDirty();
                return String.format(Locale.ROOT, "new track through %.0f %.0f, fuel %.0f", x, z, sat.fuel());
            }
            case "callsign" -> {
                sat.setCallsign(args[0]);
                registry.setDirty();
                return "callsign " + args[0];
            }
            case "deorbit" -> {
                OrbitalNet.deorbit(level, sat.satId(), DeorbitReason.COMMAND);
                return "deorbiting";
            }
            case "pos" -> {
                Vec3 at = sat.position(now);
                return String.format(Locale.ROOT, "%.0f %.0f alt %.0f", at.x, at.z, at.y);
            }
            case "fuel" -> {
                return String.format(Locale.ROOT, "%.0f/%.0f", sat.fuel(), sat.fuelCapacity());
            }
            case "power" -> {
                return String.format(Locale.ROOT, "%.0f/%.0f", sat.power(), sat.powerCapacity());
            }
            case "contact" -> {
                return sat.inContact() ? (sat.contactHops() == 0 ? "direct" : "via relay") : "none";
            }
            case "pass" -> {
                double revs = sat.elements().revolutions(now);
                return String.format(Locale.ROOT, "%d %.2f", sat.elements().passAt(now),
                        revs - Math.floor(revs));
            }
            case "status" -> {
                Vec3 at = sat.position(now);
                return String.format(Locale.ROOT,
                        "%s %s at %.0f %.0f alt %.0f, fuel %.0f/%.0f, power %.0f/%.0f, %s%s",
                        sat.callsign(), sat.payloadId().getPath(), at.x, at.z, at.y,
                        sat.fuel(), sat.fuelCapacity(), sat.power(), sat.powerCapacity(),
                        sat.inContact() ? "in contact" : "out of contact",
                        sat.parked() ? ", parked" : "");
            }
            default -> {
                String reply = sat.payload().command(level, sat, verb, args);
                registry.setDirty();
                return reply == null ? "err: unhandled verb '" + verb + "'" : reply;
            }
        }
    }

    @Nullable
    private static Double number(String raw) {
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Pending {
        private final long satId;
        private final String verb;
        private final String[] args;
        private final String issuer;
        private final long expires;
        private long due;

        private Pending(long satId, String verb, String[] args, String issuer, long due, long expires) {
            this.satId = satId;
            this.verb = verb;
            this.args = args;
            this.issuer = issuer;
            this.due = due;
            this.expires = expires;
        }
    }
}
