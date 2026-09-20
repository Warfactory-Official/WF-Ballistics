package com.wf.wflib.orbital;

import com.wf.wflib.orbital.payload.InertPayload;
import com.wf.wflib.orbital.payload.ReconPayload;
import com.wf.wflib.orbital.payload.RelayPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OrbitalSelfTest {

    private static final double NIGHT_TICKS = 12000.0;

    private OrbitalSelfTest() {
    }

    public static List<String> run(ServerLevel level) {
        List<String> failures = new ArrayList<>();
        elements(failures);
        economy(failures);
        state(failures);
        registry(failures);
        payloads(failures);
        bus(level, failures);
        catalogue(failures);
        cargo(level, failures);
        weather(failures);
        defence(failures);
        return failures;
    }

    private static void elements(List<String> failures) {
        OrbitElements leo = OrbitElements.leo(1000L, 500.0, -250.0, 0.0);

        Vec3 first = leo.at(1000L);
        if (Math.abs(first.x - 500.0) > 1.0E-6 || Math.abs(first.z + 250.0) > 1.0E-6) {
            failures.add(String.format(Locale.ROOT,
                    "at(epoch) is %.2f,%.2f - phase 0.5 should put the bird exactly over its launch point",
                    first.x, first.z));
        }
        if (Math.abs(first.y - OrbitalConfig.LEO_ALTITUDE) > 1.0E-6) {
            failures.add("at() ignored altitude: " + first.y);
        }

        for (int i = 0; i < 8; i++) {
            Vec3 again = leo.at(1000L);
            if (again.distanceToSqr(first) > 1.0E-12) {
                failures.add("at() is not pure - eight calls with one tick gave two answers");
                break;
            }
        }

        long period = (long) leo.periodTicks();
        Vec3 next = leo.at(1000L + period);
        double dx = next.x - first.x;
        double dz = next.z - first.z;
        double drift = Math.sqrt(dx * dx + dz * dz);
        if (Math.abs(drift - OrbitalConfig.LEO_DRIFT) > 1.0) {
            failures.add(String.format(Locale.ROOT,
                    "one revolution displaced the track by %.1f blocks, expected the drift of %.0f",
                    drift, OrbitalConfig.LEO_DRIFT));
        }
        double along = dx * Math.cos(leo.heading()) + dz * Math.sin(leo.heading());
        if (Math.abs(along) > 1.0E-6) {
            failures.add(String.format(Locale.ROOT,
                    "drift has a %.3f-block component along the heading - it must be sideways only", along));
        }

        Vec3 previous = leo.at(1000L - period);
        if (Math.abs((previous.z - first.z) + OrbitalConfig.LEO_DRIFT) > 1.0) {
            failures.add("at(epoch - period) did not read the previous pass - the past is not readable, "
                    + "so stale imagery and intercept solutions have nothing to stand on");
        }
        if (leo.passAt(1000L) != 0 || leo.passAt(1000L + period) != 1 || leo.passAt(1000L - period) != -1) {
            failures.add("pass numbering is wrong: " + leo.passAt(1000L - period) + "/"
                    + leo.passAt(1000L) + "/" + leo.passAt(1000L + period) + ", expected -1/0/1");
        }

        Vec3 a = leo.at(1100L);
        Vec3 b = leo.at(1101L);
        double step = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
        if (Math.abs(step - leo.groundSpeed()) > 1.0E-3) {
            failures.add(String.format(Locale.ROOT,
                    "one tick moved the bird %.4f blocks but groundSpeed() says %.4f", step, leo.groundSpeed()));
        }

        OrbitElements moved = leo.manoeuvre(5000L, -9000.0, 400.0, Math.PI / 2.0);
        if (moved.epoch() != 5000L) {
            failures.add("a manoeuvre did not stamp a new epoch, so nobody's catalogue can ever go stale");
        }
        if (leo.at(6000L).distanceToSqr(moved.at(6000L)) < 1.0) {
            failures.add("elements before and after a manoeuvre put the bird in the same place");
        }

        OrbitElements restamped = leo.restamped(4321L);
        if (restamped.at(4321L).distanceToSqr(leo.at(4321L)) > 1.0E-6) {
            failures.add("restamping moved the bird - it must only change the epoch");
        }

        if (!(OrbitalConfig.swathFor(OrbitalConfig.LEO_ALTITUDE)
                < OrbitalConfig.swathFor(OrbitalConfig.MEO_ALTITUDE)
                && OrbitalConfig.swathFor(OrbitalConfig.MEO_ALTITUDE)
                < OrbitalConfig.swathFor(OrbitalConfig.HEO_ALTITUDE))) {
            failures.add("footprint does not grow with altitude - the orbit classes buy nothing");
        }
        if (!(OrbitalConfig.resolutionFor(OrbitalConfig.LEO_ALTITUDE)
                < OrbitalConfig.resolutionFor(OrbitalConfig.HEO_ALTITUDE))) {
            failures.add("a high bird takes better pictures than a low one - no band is strictly better, "
                    + "and neither is an orbit");
        }
    }

    private static void economy(List<String> failures) {
        int evasions = (int) (OrbitalConfig.FUEL_CAPACITY / OrbitalConfig.MANOEUVRE_FUEL);
        if (evasions < 4 || evasions > 8) {
            failures.add("a full tank buys " + evasions + " manoeuvres; the attrition loop wants about six - "
                    + "a determined attacker needing a day's work and a casual one achieving nothing");
        }

        double parkTicks = OrbitalConfig.FUEL_CAPACITY / OrbitalConfig.PARK_FUEL_PER_TICK;
        double parkMinutes = parkTicks / 20.0 / 60.0;
        if (parkMinutes < 20.0 || parkMinutes > 60.0) {
            failures.add(String.format(Locale.ROOT,
                    "a full tank holds a park for %.0f minutes; wanted tens of minutes - long enough to be "
                            + "worth doing, short enough that you watch the gauge", parkMinutes));
        }

        double idleTicks = OrbitalConfig.POWER_CAPACITY / OrbitalConfig.IDLE_POWER_PER_TICK;
        if (idleTicks <= NIGHT_TICKS) {
            failures.add(String.format(Locale.ROOT,
                    "an idle bird runs flat in %.0f ticks, inside one night - being idle should never be "
                            + "what kills you", idleTicks));
        }
        double workingDraw = OrbitalConfig.IDLE_POWER_PER_TICK
                + OrbitalConfig.RECON_SWEEP_POWER / OrbitalConfig.RECON_SWEEP_TICKS;
        double workingTicks = OrbitalConfig.POWER_CAPACITY / workingDraw;
        if (workingTicks >= NIGHT_TICKS) {
            failures.add(String.format(Locale.ROOT,
                    "a bird imaging flat out lasts %.0f ticks, longer than a night - then spending the day "
                            + "looking costs nothing and night is not a vulnerable window", workingTicks));
        }
        if (OrbitalConfig.SOLAR_PER_TICK * NIGHT_TICKS < OrbitalConfig.POWER_CAPACITY) {
            failures.add("a full day of sun cannot fill the battery - a bird can never recover");
        }
        if (OrbitalConfig.SOLAR_PER_TICK <= workingDraw) {
            failures.add(String.format(Locale.ROOT,
                    "panels give %.2f/tick and a working bird draws %.2f, so an instrument cannot run "
                            + "through a day even in full sun - the battery becomes a cooldown and the "
                            + "day/night swing stops being the mechanic",
                    OrbitalConfig.SOLAR_PER_TICK, workingDraw));
        }
        if (OrbitalConfig.RELAY_HOP_COST <= com.wf.wflib.recon.grid.ReconGrid.MAX_HOPS) {
            failures.add("a relayed contact is not distinguishable from a locally measured one by hop count, "
                    + "so a relay carries its own output back and each round makes the origin's own track "
                    + "one hop worse");
        }
        if (OrbitalConfig.RECON_SWEEP_TICKS % OrbitalConfig.SLOW_TICK != 0) {
            failures.add("the sweep interval is not a multiple of the slow tick, so a sweep that comes due "
                    + "between housekeeping ticks is silently skipped");
        }
    }

    private static void state(List<String> failures) {
        Satellite sat = bird(SatPayloads.RECON_RADAR);

        double before = sat.fuel();
        if (sat.spendFuel(before + 1.0)) {
            failures.add("spendFuel granted more than the tank held");
        }
        if (sat.fuel() != before) {
            failures.add("a refused spendFuel still took fuel - partial burns turn 'out of fuel' into a "
                    + "gradient nobody can read off a gauge");
        }
        double power = sat.power();
        if (sat.spendPower(power + 1.0) || sat.power() != power) {
            failures.add("spendPower is not all-or-nothing");
        }

        if (!sat.manoeuvre(100L, 10.0, 20.0, 0.0)) {
            failures.add("a full tank could not pay for one manoeuvre");
        }
        if (Math.abs(before - sat.fuel() - OrbitalConfig.MANOEUVRE_FUEL) > 1.0E-9) {
            failures.add(String.format(Locale.ROOT, "a manoeuvre cost %.2f, expected %.2f",
                    before - sat.fuel(), OrbitalConfig.MANOEUVRE_FUEL));
        }

        int burns = 1;
        while (sat.manoeuvre(200L, 0.0, 0.0, 0.0)) {
            burns++;
            if (burns > 100) {
                break;
            }
        }
        if (burns != (int) (OrbitalConfig.FUEL_CAPACITY / OrbitalConfig.MANOEUVRE_FUEL)) {
            failures.add("a full tank ran to " + burns + " manoeuvres, not "
                    + (int) (OrbitalConfig.FUEL_CAPACITY / OrbitalConfig.MANOEUVRE_FUEL));
        }
        if (!sat.view(0L).stranded()) {
            failures.add("a bird with no fuel left does not read as stranded");
        }

        Satellite parker = bird(SatPayloads.RECON_RADAR);
        if (!parker.park(1234.0, -4321.0) || !parker.parked()) {
            failures.add("park did not take");
        }
        Vec3 held = parker.position(0L);
        if (parker.position(999999L).distanceToSqr(held) > 1.0E-9) {
            failures.add("a parked bird moved - holding station must be a constant, which is also why it is "
                    + "less work than orbiting rather than more");
        }
        if (Math.abs(held.x - 1234.0) > 1.0E-9 || Math.abs(held.z + 4321.0) > 1.0E-9) {
            failures.add("a parked bird is not over its park point");
        }
        double fuelBefore = parker.fuel();
        parker.unpark(500L);
        if (parker.parked()) {
            failures.add("unpark did not break station");
        }
        if (parker.fuel() != fuelBefore) {
            failures.add("un-parking cost fuel - the evasion move must be free, or a defender who sees a "
                    + "threat coming is reading a fail state rather than making a decision");
        }
        if (parker.position(500L).distanceToSqr(held) > 1.0E-6) {
            failures.add("un-parking teleported the bird instead of resuming from where it stood");
        }

        Satellite flat = bird(SatPayloads.RECON_RADAR);
        flat.drain(flat.powerCapacity() * 2.0);
        if (flat.power() != 0.0 || !flat.view(0L).asleep()) {
            failures.add("draining past empty did not clamp at zero");
        }
        flat.charge(flat.powerCapacity() * 2.0);
        if (flat.power() != flat.powerCapacity()) {
            failures.add("charging past full did not clamp at capacity");
        }
    }

    private static void registry(List<String> failures) {
        OrbitalRegistry registry = new OrbitalRegistry();
        SatSpec spec = SatSpec.of(SatPayloads.RECON_RADAR).netId(0x1234L).callsign("KH-1");
        Satellite one = registry.add(spec, OrbitElements.leo(0L, 0.0, 0.0, 0.0));
        Satellite two = registry.add(spec.callsign("KH-2"), OrbitElements.meo(0L, 100.0, 0.0, 1.0));
        if (one.rawId() == two.rawId()) {
            failures.add("two launches got the same id");
        }
        if (registry.byCallsign("kh-2") != two) {
            failures.add("callsign lookup is case sensitive, or missing");
        }
        if (registry.onNet(0x1234L).size() != 2 || !registry.onNet(0x9999L).isEmpty()) {
            failures.add("onNet does not filter by network");
        }

        one.park(77.0, -88.0);
        one.spendPower(123.0);
        one.setCallsign("RENAMED");
        CompoundTag saved = one.save();
        Satellite back = Satellite.load(saved);
        if (back.rawId() != one.rawId() || !back.callsign().equals("RENAMED")
                || back.parked() != one.parked()
                || Math.abs(back.fuel() - one.fuel()) > 1.0E-9
                || Math.abs(back.power() - one.power()) > 1.0E-9
                || back.position(0L).distanceToSqr(one.position(0L)) > 1.0E-9
                || back.netId() != one.netId()
                || !back.payloadId().equals(one.payloadId())) {
            failures.add("a satellite did not survive a save/load round trip");
        }

        CompoundTag orphan = one.save();
        orphan.putString("Payload", "somemod:deleted_instrument");
        CompoundTag instrument = new CompoundTag();
        instrument.putInt("Serial", 42);
        orphan.put("PayloadData", instrument);
        Satellite ghost;
        try {
            ghost = Satellite.load(orphan);
        } catch (RuntimeException e) {
            failures.add("an unknown payload id threw on load: " + e);
            return;
        }
        if (!(ghost.payload() instanceof InertPayload)) {
            failures.add("an unknown payload did not fall back to inert mass");
        }
        if (ghost.save().getCompound("PayloadData").getInt("Serial") != 42) {
            failures.add("an unknown payload's own state was lost on the round trip - removing a mod should "
                    + "leave the instrument's tag untouched in case it comes back");
        }
    }

    private static void payloads(List<String> failures) {
        if (!SatPayloads.contains(SatPayloads.RECON_RADAR) || !SatPayloads.contains(SatPayloads.RELAY)
                || !SatPayloads.contains(SatPayloads.INERT)) {
            failures.add("bootstrap did not register the shipped payloads");
        }
        ResourceLocation missing = ResourceLocation.fromNamespaceAndPath("wflib", "not_a_payload");
        if (SatPayloads.contains(missing)) {
            failures.add("contains() said yes to an id nothing registered - this is the "
                    + "BuiltInRegistries.get trap, where an unknown id hands back a default rather than null");
        }
        SatPayload fallback = SatPayloads.create(missing);
        if (fallback == null) {
            failures.add("create() returned null for an unknown id");
        } else if (!(fallback instanceof InertPayload)) {
            failures.add("an unknown id created a " + fallback.getClass().getSimpleName()
                    + " rather than inert mass");
        }
        if (!(SatPayloads.create(SatPayloads.RECON_RADAR) instanceof ReconPayload)
                || !(SatPayloads.create(SatPayloads.RELAY) instanceof RelayPayload)) {
            failures.add("a registered id created the wrong payload");
        }
        if (SatPayloads.create(SatPayloads.RECON_RADAR) == SatPayloads.create(SatPayloads.RECON_RADAR)) {
            failures.add("create() handed the same instance to two satellites");
        }
        if (!new ReconPayload().emitting()) {
            failures.add("a fresh radar payload is not emitting - an active sensor that nothing can home on "
                    + "is a free lunch");
        }
    }

    private static void bus(ServerLevel level, List<String> failures) {
        SatHandle handle = OrbitalNet.orbit(level,
                SatSpec.of(SatPayloads.RECON_RADAR).callsign("SELFTEST"),
                OrbitElements.leo(level.getGameTime(), 0.0, 0.0, 0.0));
        SatId id = handle.id();
        try {
            if (OrbitalNet.view(level, id).isEmpty()) {
                failures.add("a launched satellite could not be read back");
                return;
            }
            Satellite sat = OrbitalRegistry.get(level).byId(id);

            boolean hasCore = false;
            boolean hasPayload = false;
            boolean parkIsImpure = false;
            boolean posIsPure = false;
            for (SatVerb verb : OrbitalNet.verbs(level, id)) {
                hasCore |= verb.name().equals("park");
                hasPayload |= verb.name().equals("standby");
                parkIsImpure |= verb.name().equals("park") && !verb.pure();
                posIsPure |= verb.name().equals("pos") && verb.pure();
            }
            if (!hasCore || !hasPayload) {
                failures.add("verbs() does not list core and payload verbs together, so a GUI cannot "
                        + "build itself from the satellite");
            }
            if (!parkIsImpure || !posIsPure) {
                failures.add("the read/write split is not declared: park must be impure and pos pure");
            }

            String unknown = OrbitalNet.commandBy(level, id, "selftest", "nonsense");
            if (!unknown.startsWith("err:")) {
                failures.add("an unknown verb was accepted: " + unknown);
            }
            String wrongArity = OrbitalNet.commandBy(level, id, "selftest", "park", "100");
            if (!wrongArity.startsWith("err:")) {
                failures.add("park was accepted with one argument - arity is not checked at parse");
            }

            double fuel = sat.fuel();
            String queued = OrbitalNet.commandBy(level, id, "selftest", "park", "100", "200");
            if (queued.startsWith("err:")) {
                failures.add("a well-formed park was rejected: " + queued);
            }
            if (sat.parked() || sat.fuel() != fuel) {
                failures.add("an action verb took effect on the tick it was issued - the one-tick delay is "
                        + "what makes this a bus rather than a method call");
            }

            String pos = OrbitalNet.value(level, id, "pos");
            if (pos.startsWith("err:") || pos.isEmpty()) {
                failures.add("a pure verb did not answer immediately: " + pos);
            }
            if (sat.parked()) {
                failures.add("reading a value parked the satellite");
            }
            String reject = OrbitalNet.value(level, id, "park");
            if (!reject.startsWith("err:")) {
                failures.add("value() ran an impure verb - a panel polling every tick must not be able to "
                        + "change anything");
            }
        } finally {
            OrbitalCommandBus.cancel(level, id.value());
            OrbitalNet.deorbit(level, id, DeorbitReason.REMOVED);
        }
        if (OrbitalNet.view(level, id).isPresent()) {
            failures.add("a deorbited satellite is still readable");
        }
    }

    private static void catalogue(List<String> failures) {
        OrbitalRegistry registry = new OrbitalRegistry();
        Satellite target = registry.add(SatSpec.of(SatPayloads.RECON_RADAR).netId(0xAAAAL).callsign("THEIRS"),
                OrbitElements.leo(0L, 0.0, 0.0, 0.0));
        registry.observe(0xBBBBL, target, 100L);

        List<TrackedObject> book = registry.catalogue(0xBBBBL);
        if (book.size() != 1) {
            failures.add("one observation produced " + book.size() + " catalogue entries");
            return;
        }
        TrackedObject seen = book.get(0);
        if (seen.observations() != 1 || seen.lastSeen() != 100L) {
            failures.add("catalogue entry has the wrong provenance");
        }
        if (!registry.catalogue(0xAAAAL).isEmpty()) {
            failures.add("a network catalogued its own bird");
        }

        registry.observe(0xBBBBL, target, 200L);
        if (registry.catalogue(0xBBBBL).size() != 1
                || registry.catalogue(0xBBBBL).get(0).observations() != 2) {
            failures.add("a second observation added an entry instead of refining the one already held");
        }

        target.manoeuvre(300L, 8000.0, -8000.0, 1.0);
        TrackedObject stale = registry.catalogue(0xBBBBL).get(0);
        if (stale.positionAt(400L).distanceToSqr(target.position(400L)) < 100.0) {
            failures.add("a manoeuvre did not invalidate the catalogued elements - stale elements are the "
                    + "whole reason tracking is an ongoing commitment rather than a one-time discovery");
        }
        if (stale.age(400L) != 200L) {
            failures.add("catalogue age is wrong: " + stale.age(400L));
        }
        if (seen.designator() == target.rawId()) {
            failures.add("a catalogue entry carries the target's own id - you do not learn a thing's name "
                    + "by watching it fly over");
        }
    }

    private static void cargo(ServerLevel level, List<String> failures) {
        if (CargoTable.isEmpty()) {
            failures.add("no cargo table registered - a miner has nothing to mine");
            return;
        }
        List<net.minecraft.world.item.ItemStack> manifest = CargoTable.roll(level.getRandom(), 200);
        if (manifest.isEmpty()) {
            failures.add("a 200-unit roll produced nothing");
            return;
        }
        if (manifest.size() > CargoTable.SLOTS) {
            failures.add("a roll produced " + manifest.size() + " stacks, more than the "
                    + CargoTable.SLOTS + " a shuttle can carry");
        }
        int total = 0;
        for (net.minecraft.world.item.ItemStack stack : manifest) {
            total += stack.getCount();
            if (stack.getCount() > stack.getMaxStackSize()) {
                failures.add("merging overflowed a stack: " + stack.getCount() + " of "
                        + stack.getMaxStackSize());
            }
        }
        if (total < 100) {
            failures.add("200 units rolled only " + total + " items - the pool is dropping most of them");
        }
        if (CargoTable.roll(level.getRandom(), 0).size() != 0) {
            failures.add("an empty hold produced cargo");
        }

        if (OrbitalConfig.RELEASE_DISTANCE <= com.wf.wflib.sim.MissileSimConfig.DESTINATION_RANGE) {
            failures.add("things released from orbit start inside DESTINATION_RANGE, so they become real "
                    + "entities on the tick they are fired and have no flight to be detected during");
        }
        double rodSeconds = OrbitalConfig.RELEASE_DISTANCE / OrbitalConfig.ROD_SPEED / 20.0;
        if (rodSeconds < 10.0 || rodSeconds > 90.0) {
            failures.add(String.format(Locale.ROOT,
                    "a rod is in the air for %.0fs; wanted tens of seconds - long enough to warn about and "
                            + "short enough to be a weapon", rodSeconds));
        }
        if (OrbitalConfig.SHUTTLE_SPEED >= OrbitalConfig.ROD_SPEED) {
            failures.add("a cargo shuttle is not slower than a rod, so it is no easier to shoot down");
        }
    }

    private static void weather(List<String> failures) {
        com.wf.wflib.recon.env.Atmosphere clear =
                new com.wf.wflib.recon.env.Atmosphere(0.0, 0.0, 0.0, 0.0);
        com.wf.wflib.recon.env.Atmosphere storm =
                new com.wf.wflib.recon.env.Atmosphere(0.0, 1.0, 1.0, 0.0);
        double dry = com.wf.wflib.orbital.payload.LaserPayload.transmission(clear);
        double wet = com.wf.wflib.orbital.payload.LaserPayload.transmission(storm);
        if (dry < 0.99) {
            failures.add(String.format(Locale.ROOT,
                    "clear air only passes %.2f of the beam", dry));
        }
        if (wet >= OrbitalConfig.LASER_MIN_TRANSMISSION) {
            failures.add(String.format(Locale.ROOT,
                    "a thunderstorm passes %.2f of the beam, above the %.2f cutoff - then weather never "
                            + "refuses a shot and the met payload buys nothing",
                    wet, OrbitalConfig.LASER_MIN_TRANSMISSION));
        }
        if (!(dry > wet)) {
            failures.add("weather does not attenuate the beam at all");
        }
    }

    private static void defence(List<String> failures) {
        Satellite fitted = new Satellite(2L, SatSpec.of(SatPayloads.RECON_RADAR).pointDefence(2),
                OrbitElements.leo(0L, 0.0, 0.0, 0.0));
        if (fitted.pointDefence() != 2) {
            failures.add("point defence was not fitted from the spec");
        }
        double power = fitted.power();
        if (!fitted.engage()) {
            failures.add("a fitted bird could not make its first engagement");
        }
        if (Math.abs(power - fitted.power() - OrbitalConfig.POINT_DEFENCE_POWER) > 1.0E-9) {
            failures.add("an engagement did not cost what it should");
        }
        fitted.engage();
        if (fitted.pointDefence() != 0 || fitted.engage()) {
            failures.add("point defence fired more often than it had rounds");
        }

        Satellite bare = bird(SatPayloads.RELAY);
        if (bare.engage()) {
            failures.add("a bird with no point defence shot something down");
        }
        bare.drain(bare.powerCapacity());
        Satellite flat = new Satellite(3L, SatSpec.of(SatPayloads.RECON_RADAR).pointDefence(4),
                OrbitElements.leo(0L, 0.0, 0.0, 0.0));
        flat.drain(flat.powerCapacity());
        if (flat.engage()) {
            failures.add("a flat battery still fired the close-in gun - night has to be a real window");
        }
        if (flat.pointDefence() != 4) {
            failures.add("a refused engagement still spent a round");
        }

        Satellite warned = bird(SatPayloads.RECON_RADAR);
        if (warned.threatTicks() != -1) {
            failures.add("a bird starts life believing something is inbound");
        }
        warned.warn(240);
        if (warned.view(0L).threatTicks() != 240) {
            failures.add("a warning does not reach the view, so the threat is not legible and the whole "
                    + "attrition loop is a dice roll rather than a decision");
        }
        warned.clearWarning();
        if (warned.threatTicks() != -1) {
            failures.add("a warning could not be cleared");
        }

        if (OrbitalConfig.requireWarForOrbitalKills) {
            failures.add("the war gate defaults on; the design has it off, so anything overhead is fair game");
        }
        if (OrbitalConfig.HUNTER_KILL_RANGE >= OrbitalConfig.POINT_DEFENCE_RANGE) {
            failures.add("point defence cannot reach further than a hunter's kill range, so it never gets "
                    + "a shot");
        }
    }

    private static Satellite bird(ResourceLocation payload) {
        return new Satellite(1L, SatSpec.of(payload), OrbitElements.leo(0L, 0.0, 0.0, 0.0));
    }
}
