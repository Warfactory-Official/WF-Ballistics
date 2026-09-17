package com.wf.wfballistics.recon.event;

import com.wf.wfballistics.recon.Band;
import net.minecraft.nbt.CompoundTag;

/**
 * What a network worked out about one explosion.
 *
 * @param eventId which blast this is a fix of. Stable across sweeps, so a log replaces its entry when a
 *      second look ranges the same bang better instead of listing it twice.
 * @param positionError blocks. The larger semi-axis of the fused error ellipse, so it is the honest radius
 *      inside which the event happened and never an average that flatters a bad fix.
 * @param energy estimated seismic energy at the source, recovered from what was heard and how far away
 *      the net thinks it was. See {@link #power()} for the number to show a player.
 * @param energyError blocks of range uncertainty, carried through the propagation law. This is why a
 *      one-station fix cannot state a yield: the energy estimate is only as good as the range
 *      estimate it is divided by, and one station barely has one.
 * @param stations how many geophones contributed. The single most useful field for reading the rest.
 * @param band which band heard it. A blast in the water reaches a hydrophone and a blast in rock reaches a
 *      geophone, and the two arrive through different couplings, so this is what {@link #power()} reads
 *      to turn an energy back into a yield.
 * @param subsurface whether the network believes this went off underground. Not measured (it is the
 *      event's own coupling, which a station cannot separate from a bigger charge at the
 *      surface), so it is carried for the log and deliberately not used to sharpen anything.
 */
public record SeismicFix(long eventId, double x, double y, double z, double positionError,
                         double energy, double energyError, int stations, Band band, boolean subsurface,
                         long gameTime) {

    /**
     * @return the blast power this energy implies, in the units the explosion framework uses: vanilla TNT is
     *      4. What a player recognises, where energy is what the physics carries.
     */
    public double power() {
        return yieldOf(energy);
    }

    /**
     * @return the low and high ends of the power estimate. Stated as a pair rather than a plus-or-minus
     *      because the yield curve is not linear: the same range error is worth much more at the top of the scale.
     */
    public double powerLow() {
        return yieldOf(Math.max(0.0, energy - energyError));
    }

    public double powerHigh() {
        return yieldOf(energy + energyError);
    }

    private double yieldOf(double e) {
        return band == Band.SONAR ? SeismicEvents.powerOfWater(e) : SeismicEvents.powerOf(e);
    }

    public double rangeFrom(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * @return compass bearing to the event in degrees, zero north and rising clockwise, as a player reads it
     *      off an F3 screen or a lodestone.
     */
    public double bearingDegFrom(double px, double pz) {
        double bearing = Math.toDegrees(Math.atan2(x - px, -(z - pz)));
        return bearing < 0.0 ? bearing + 360.0 : bearing;
    }

    /**
     * @return half-angle of the direction estimate in degrees, or 180 when the fix is vaguer than it is far
     *      away, which is exactly what one geophone produces, and the readout should say so rather than printing
     *      a heading nobody measured.
     */
    public double bearingErrorDegFrom(double px, double py, double pz) {
        double range = rangeFrom(px, py, pz);
        if (positionError >= range) {
            return 180.0;
        }
        return Math.toDegrees(Math.asin(positionError / range));
    }

    /**
     * @return true if this fix pins a direction at all. One station never does.
     */
    public boolean hasBearing(double px, double py, double pz) {
        return positionError < rangeFrom(px, py, pz);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", eventId);
        tag.putDouble("X", x);
        tag.putDouble("Y", y);
        tag.putDouble("Z", z);
        tag.putDouble("Err", positionError);
        tag.putDouble("E", energy);
        tag.putDouble("EErr", energyError);
        tag.putInt("N", stations);
        if (band != Band.SEISMIC) {
            tag.putString("Band", band.name());
        }
        if (subsurface) {
            tag.putBoolean("Sub", true);
        }
        tag.putLong("T", gameTime);
        return tag;
    }

    /**
     * @return the band named, or {@link Band#SEISMIC} for a log written before there was a second one and for
     *      any name this build does not have. Both read the same way: something heard it and the band is the
     *      one field a reader can do without.
     */
    private static Band band(String name) {
        for (Band band : Band.VALUES) {
            if (band.name().equals(name)) {
                return band;
            }
        }
        return Band.SEISMIC;
    }

    public static SeismicFix load(CompoundTag tag) {
        return new SeismicFix(tag.getLong("Id"), tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"),
                tag.getDouble("Err"), tag.getDouble("E"), tag.getDouble("EErr"),
                tag.getInt("N"), band(tag.getString("Band")), tag.getBoolean("Sub"), tag.getLong("T"));
    }
}
