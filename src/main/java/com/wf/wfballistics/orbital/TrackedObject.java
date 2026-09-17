package com.wf.wfballistics.orbital;

import net.minecraft.nbt.CompoundTag;

/**
 * Somebody else's satellite, as far as your ground stations can tell.
 *
 * @param designator a catalogue number for the object. Deliberately not the target's own {@link SatId}: you
 *      do not learn a thing's name by watching it fly over.
 * @param estimate the elements as last observed. Propagate with {@link OrbitElements#at(long)}.
 * @param lastSeen the tick this was last refreshed by an observation. Everything after it is extrapolation.
 * @param observations how many separate passes contributed. One pass is a guess; several are a solution.
 * @param emitting true if the object was radiating when seen. An emitting bird is louder than a quiet one,
 *      and can be found without ever being caught by radar.
 * @param parked true if the object was holding station when seen, in which case its "orbit" is the fixed
 *      point below and propagating the elements would be wrong. Not a leak of the invariant: a
 *      parked bird sits at a public coordinate on purpose, and that exposure is precisely what
 *      parking trades for persistence.
 */
public record TrackedObject(long designator, OrbitElements estimate, long lastSeen,
                            int observations, boolean emitting,
                            boolean parked, double parkX, double parkZ) {

    /**
     * @return where this object is believed to be at a tick: propagated from the elements, or the station it
     *      was last seen holding. Wrong from the moment the target burns or breaks station, and that is the point.
     */
    public net.minecraft.world.phys.Vec3 positionAt(long gameTime) {
        return parked
                ? new net.minecraft.world.phys.Vec3(parkX, estimate.altitude(), parkZ)
                : estimate.at(gameTime);
    }

    /**
     * @return ticks since anybody actually saw this. The number a decision to shoot should be weighed against,
     *      because element error grows with it and collapses to nothing the moment the target burns.
     */
    public long age(long now) {
        return Math.max(0L, now - lastSeen);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Designator", designator);
        tag.put("Elements", estimate.save());
        tag.putLong("LastSeen", lastSeen);
        tag.putInt("Observations", observations);
        tag.putBoolean("Emitting", emitting);
        tag.putBoolean("Parked", parked);
        tag.putDouble("ParkX", parkX);
        tag.putDouble("ParkZ", parkZ);
        return tag;
    }

    public static TrackedObject load(CompoundTag tag) {
        return new TrackedObject(tag.getLong("Designator"), OrbitElements.load(tag.getCompound("Elements")),
                tag.getLong("LastSeen"), tag.getInt("Observations"), tag.getBoolean("Emitting"),
                tag.getBoolean("Parked"), tag.getDouble("ParkX"), tag.getDouble("ParkZ"));
    }
}
