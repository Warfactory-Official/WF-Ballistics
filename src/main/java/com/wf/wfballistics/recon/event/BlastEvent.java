package com.wf.wfballistics.recon.event;

/**
 * One explosion, as the ground felt it.
 *
 * @param id monotonic per level, so a fix can name the event it is a fix <em>of</em> and a log can
 *      replace an entry when a later sweep ranges the same bang better.
 * @param energy seismic energy actually coupled into the ground, after depth and altitude have had their
 *      say. Directly comparable with {@code Signature.seismicEnergy}: a value of 1.0 is heard at
 *      exactly a geophone's {@code baseRange} through neutral ground.
 * @param power the blast's own size, in the units the explosion framework uses (vanilla TNT is 4). Kept
 *      beside {@link #energy} because it is what a player recognises, and because the coupling
 *      between the two is the mechanic: the same charge is a different event buried or in the air.
 * @param waterEnergy energy coupled into the water rather than into rock, zero for a charge that went off in
 *      the air or in the ground. On the same scale as {@code energy}, so a hydrophone reads it the
 *      way a geophone reads the other: water is incompressible, so a charge standing in it is far
 *      louder there than the same charge is in rock.
 * @param subsurface true if this went off under the surface. A camouflet couples nearly everything it has
 *      into rock, which is both the loudest thing on this band and the one a defender cannot see
 *      coming any other way.
 */
public record BlastEvent(long id, double x, double y, double z, float energy, float waterEnergy,
                         float power, boolean subsurface, long gameTime) {

    public double distanceSqTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * @return whether this event is still on the stations' traces.
     */
    public boolean readable(long now) {
        long age = now - gameTime;
        return age >= 0 && age < SeismicEvents.LINGER_TICKS;
    }
}
