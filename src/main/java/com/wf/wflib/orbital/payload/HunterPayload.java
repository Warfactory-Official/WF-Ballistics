package com.wf.wflib.orbital.payload;

import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.orbital.DeorbitReason;
import com.wf.wflib.orbital.OrbitElements;
import com.wf.wflib.orbital.OrbitalConfig;
import com.wf.wflib.orbital.OrbitalNet;
import com.wf.wflib.orbital.OrbitalRegistry;
import com.wf.wflib.orbital.SatPayload;
import com.wf.wflib.orbital.SatVerb;
import com.wf.wflib.orbital.Satellite;
import com.wf.wflib.orbital.TrackedObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HunterPayload implements SatPayload {

    private static final double ACQUIRE_TOLERANCE = 1500.0;
    private static final double CLOSE_POWER = 4.0;

    private long designator = -1L;
    private OrbitElements solution;
    private boolean parked;
    private double parkX;
    private double parkZ;
    private double separation;
    private String lastReason = "idle";

    @Override
    public List<SatVerb> verbs() {
        return List.of(
                SatVerb.action("hunt", 1, "ok", "take a catalogue designator and close on it"),
                SatVerb.action("abort", 0, "ok", "break off"),
                SatVerb.value("solution", "text", "what it is chasing, and how far out"));
    }

    @Override
    public String command(ServerLevel level, Satellite self, String verb, String[] args) {
        switch (verb) {
            case "abort" -> {
                designator = -1L;
                solution = null;
                lastReason = "broken off";
                return "broken off";
            }
            case "solution" -> {
                if (solution == null) {
                    return "no solution: " + lastReason;
                }
                return String.format(Locale.ROOT, "%06X at %.0f blocks, %ds to intercept (%s)",
                        designator, separation, (int) (separation / OrbitalConfig.HUNTER_CLOSE_RATE
                                * OrbitalConfig.SLOW_TICK / 20.0), lastReason);
            }
            case "hunt" -> {
                return hunt(level, self, args[0]);
            }
            default -> {
                return null;
            }
        }
    }

    private String hunt(ServerLevel level, Satellite self, String raw) {
        long want;
        try {
            want = Long.parseLong(raw.replaceFirst("^0[xX]", ""), 16);
        } catch (NumberFormatException e) {
            return "err: '" + raw + "' is not a catalogue designator";
        }
        for (TrackedObject object : OrbitalNet.catalogue(level, self.netId())) {
            if (object.designator() == want) {
                designator = want;
                solution = object.estimate();
                parked = object.parked();
                parkX = object.parkX();
                parkZ = object.parkZ();
                Vec3 guess = predict(level.getGameTime());
                separation = Math.max(OrbitalConfig.HUNTER_KILL_RANGE,
                        guess.distanceTo(self.position(level.getGameTime())));
                lastReason = "closing on " + object.observations() + "-pass elements";
                return String.format(Locale.ROOT, "hunting %06X, %.0f blocks out", want, separation);
            }
        }
        return "err: nothing catalogued as " + raw + " - your stations have to see it first";
    }

    @Override
    public void tick(ServerLevel level, Satellite self) {
        if (solution == null) {
            self.clearWarning();
            return;
        }
        if (!self.spendPower(CLOSE_POWER)) {
            lastReason = "battery flat, drifting";
            return;
        }
        long now = level.getGameTime();
        Vec3 predicted = predict(now);
        Satellite target = find(level, self, predicted);
        if (target == null) {
            solution = null;
            designator = -1L;
            lastReason = "solution stale - the target manoeuvred";
            self.report("intercept lost: the elements are stale");
            return;
        }

        separation -= OrbitalConfig.HUNTER_CLOSE_RATE;
        int ticksOut = (int) Math.max(0.0, separation / OrbitalConfig.HUNTER_CLOSE_RATE
                * OrbitalConfig.SLOW_TICK);
        target.warn(ticksOut);
        if (separation > OrbitalConfig.HUNTER_KILL_RANGE) {
            lastReason = "closing";
            return;
        }
        resolve(level, self, target);
    }

    private void resolve(ServerLevel level, Satellite self, Satellite target) {
        target.clearWarning();
        if (target.engage()) {
            lastReason = "shot down by point defence";
            target.report("point defence engaged an inbound hunter");
            OrbitalNet.deorbit(level, self.satId(), DeorbitReason.KILLED);
            return;
        }
        if (!target.jammed() && target.spendPower(OrbitalConfig.EVADE_POWER)
                && target.manoeuvre(level.getGameTime(), target.position(level.getGameTime()).x,
                target.position(level.getGameTime()).z, target.elements().heading() + 0.7)) {
            solution = null;
            designator = -1L;
            separation = 0.0;
            lastReason = "target evaded";
            target.report(String.format(Locale.ROOT, "evaded a hunter, %.0f fuel left", target.fuel()));
            return;
        }
        if (!permitted(self, target)) {
            lastReason = "kill refused: no war between these factions";
            target.report("a hunter closed and broke off - no war declared");
            return;
        }
        lastReason = "target destroyed";
        OrbitalNet.deorbit(level, target.satId(), DeorbitReason.KILLED);
        OrbitalNet.deorbit(level, self.satId(), DeorbitReason.KILLED);
    }

    /** The war gate. */
    private static boolean permitted(Satellite hunter, Satellite target) {
        if (!OrbitalConfig.requireWarForOrbitalKills) {
            return true;
        }
        if (hunter.owner() == null || target.owner() == null) {
            return true;
        }
        if (Objects.equals(hunter.owner(), target.owner())) {
            return false;
        }
        return !WarforgeCompat.areFactionsFriendly(hunter.owner(), target.owner());
    }

    private Vec3 predict(long gameTime) {
        return parked ? new Vec3(parkX, solution.altitude(), parkZ) : solution.at(gameTime);
    }

    private static Satellite find(ServerLevel level, Satellite self, Vec3 predicted) {
        Satellite best = null;
        double bestSq = ACQUIRE_TOLERANCE * ACQUIRE_TOLERANCE;
        long now = level.getGameTime();
        for (Satellite other : OrbitalRegistry.get(level).all()) {
            if (other == self || other.netId() == self.netId()) {
                continue;
            }
            double distSq = other.position(now).distanceToSqr(predicted);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = other;
            }
        }
        return best;
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Designator", designator);
        tag.putDouble("Separation", separation);
        tag.putBoolean("Parked", parked);
        tag.putDouble("ParkX", parkX);
        tag.putDouble("ParkZ", parkZ);
        tag.putString("Reason", lastReason);
        if (solution != null) {
            tag.put("Solution", solution.save());
        }
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        designator = tag.getLong("Designator");
        separation = tag.getDouble("Separation");
        parked = tag.getBoolean("Parked");
        parkX = tag.getDouble("ParkX");
        parkZ = tag.getDouble("ParkZ");
        lastReason = tag.getString("Reason");
        solution = tag.contains("Solution") ? OrbitElements.load(tag.getCompound("Solution")) : null;
    }
}
