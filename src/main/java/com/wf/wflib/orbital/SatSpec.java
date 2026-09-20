package com.wf.wflib.orbital;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Everything a satellite is, as data.
 *
 * @param payload which payload runs aboard. A {@link ResourceLocation} into {@link SatPayloads}, so a
 *      pack or wfcore can add one and nothing here has to know about it.
 * @param netId the recon network this bird feeds and takes orders from. The <em>same</em> identity the
 *      ground radars use, so an orbital contact lands in the same fused picture rather than a
 *      second one beside it.
 * @param owner the WarForge faction that owns it, or null in a pack with no faction mod, in which
 *      case there are no factions and everything is permitted.
 * @param station the ground station that commands it, if any. <b>Whoever holds this chunk holds the
 *      bird</b>: ownership is re-read from the claim every slow tick, so a successful siege
 *      does not merely darken a constellation, it inherits it mid-orbit with whatever is
 *      still aboard. With no faction mod present the station's own network id is followed
 *      instead, which is the same rule with the only identity that exists in that world,
 *      and it means a bird cannot be launched onto the wrong net by asking a ground station
 *      what its network was before the station had one.
 * @param callsign what a command is addressed to. A name, not a frequency and not a chip.
 * @param fuel starting fuel. A stock: manoeuvres and parking spend it and nothing puts it back.
 * @param fuelCapacity the tank. Endurance as a build choice: a bigger tank is more dodges before bankruptcy.
 * @param power starting charge.
 * @param powerCapacity the battery. Bought against instrument mass, and what decides whether a bird can still
 *      see and shoot at three in the morning.
 * @param solarRate charge per tick while it is day. Zero for a bird with no panels, which is a real and
 *      very short-lived design.
 * @param swath footprint radius in blocks, or 0 to derive it from altitude.
 * @param resolution fix error in blocks, or 0 to derive it from altitude.
 * @param magazine rounds aboard for payloads that carry ammunition. Spent is spent.
 * @param pointDefence engagements a close-in gun or laser can make against an approaching hunter. Bought
 *      against instrument mass and drawing on the same battery as everything else, which is
 *      what stops it being a free answer, and without it, attrition is a stat check rather
 *      than a fight.
 */
public record SatSpec(ResourceLocation payload, long netId, @Nullable UUID owner, @Nullable BlockPos station,
                      String callsign, double fuel, double fuelCapacity,
                      double power, double powerCapacity, double solarRate,
                      double swath, double resolution, int magazine, int pointDefence) {

    /**
     * A bird with a full tank, a full battery, panels, and everything else derived from the orbit it ends up in.
     */
    public static SatSpec of(ResourceLocation payload) {
        return new SatSpec(payload, com.wf.wflib.recon.ReconNet.UNAFFILIATED, null, null, "SAT",
                OrbitalConfig.FUEL_CAPACITY, OrbitalConfig.FUEL_CAPACITY,
                OrbitalConfig.POWER_CAPACITY, OrbitalConfig.POWER_CAPACITY, OrbitalConfig.SOLAR_PER_TICK,
                0.0, 0.0, 0, 0);
    }

    public SatSpec netId(long id) {
        return new SatSpec(payload, id, owner, station, callsign, fuel, fuelCapacity,
                power, powerCapacity, solarRate, swath, resolution, magazine, pointDefence);
    }

    public SatSpec owner(@Nullable UUID faction) {
        return new SatSpec(payload, netId, faction, station, callsign, fuel, fuelCapacity,
                power, powerCapacity, solarRate, swath, resolution, magazine, pointDefence);
    }

    /**
     * Tie this bird to a ground station. See {@link #station()}: this is the capture seam, not a decoration.
     */
    public SatSpec station(@Nullable BlockPos pos) {
        return new SatSpec(payload, netId, owner, pos == null ? null : pos.immutable(), callsign,
                fuel, fuelCapacity, power, powerCapacity, solarRate, swath, resolution, magazine, pointDefence);
    }

    public SatSpec callsign(String name) {
        return new SatSpec(payload, netId, owner, station, name, fuel, fuelCapacity,
                power, powerCapacity, solarRate, swath, resolution, magazine, pointDefence);
    }

    /** Fills the tank as well as setting its size: a bird launched half-empty is a deliberate act. */
    public SatSpec fuel(double capacity) {
        return new SatSpec(payload, netId, owner, station, callsign, capacity, capacity,
                power, powerCapacity, solarRate, swath, resolution, magazine, pointDefence);
    }

    public SatSpec battery(double capacity, double rate) {
        return new SatSpec(payload, netId, owner, station, callsign, fuel, fuelCapacity,
                capacity, capacity, rate, swath, resolution, magazine, pointDefence);
    }

    /** Override the derived optics. Both in blocks; either at 0 goes back to being read off the altitude. */
    public SatSpec optics(double footprintRadius, double fixError) {
        return new SatSpec(payload, netId, owner, station, callsign, fuel, fuelCapacity,
                power, powerCapacity, solarRate, footprintRadius, fixError, magazine, pointDefence);
    }

    /** Fit close-in defence. Costs power to fire, and the mass is mass the instrument does not get. */
    public SatSpec pointDefence(int engagements) {
        return new SatSpec(payload, netId, owner, station, callsign, fuel, fuelCapacity,
                power, powerCapacity, solarRate, swath, resolution, magazine, engagements);
    }

    public SatSpec magazine(int rounds) {
        return new SatSpec(payload, netId, owner, station, callsign, fuel, fuelCapacity,
                power, powerCapacity, solarRate, swath, resolution, rounds, pointDefence);
    }
}
