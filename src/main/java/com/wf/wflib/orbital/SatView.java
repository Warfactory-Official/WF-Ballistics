package com.wf.wflib.orbital;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * What a satellite looked like at one instant, for anybody outside the orbital package.
 *
 * @param parked true if the bird is holding station rather than orbiting. A parked bird is at a fixed,
 *      public coordinate and burning fuel to stay there, which makes it the easiest target in the
 *      game and the most useful thing you own, at the same time.
 * @param threatTicks ticks until something closing on this bird arrives, or {@code -1} for a quiet sky. The
 *      threat has to be legible or none of the attrition game is a decision; see
 *      {@link Satellite#threatTicks()}.
 * @param inContact whether a ground station on its network could talk to it at {@code gameTime}. The link is
 *      the resource: a command reaches a bird out of contact only after it comes back round.
 */
public record SatView(SatId id, ResourceLocation payload, long netId, @Nullable UUID owner, String callsign,
                      OrbitElements elements, double x, double y, double z,
                      boolean parked, double parkX, double parkZ,
                      double fuel, double fuelCapacity, double power, double powerCapacity,
                      double swath, double resolution, int magazine, int pointDefence, int threatTicks,
                      boolean inContact, long gameTime) {

    /** @return 0 to 1. What a readout should show, and what a decision to spend is weighed against. */
    public double fuelFraction() {
        return fuelCapacity <= 0.0 ? 0.0 : Math.min(1.0, fuel / fuelCapacity);
    }

    public double powerFraction() {
        return powerCapacity <= 0.0 ? 0.0 : Math.min(1.0, power / powerCapacity);
    }

    /**
     * @return true if this bird can no longer move. Not dead (it still sees, still talks, still shoots), but
     *      it is on the orbit it is on permanently, which is the state the whole attrition loop is aiming for.
     */
    public boolean stranded() {
        return fuel < OrbitalConfig.MANOEUVRE_FUEL;
    }

    /** @return true if the battery is flat: asleep until sunrise, and defenceless until then. */
    public boolean asleep() {
        return power <= 0.0;
    }
}
