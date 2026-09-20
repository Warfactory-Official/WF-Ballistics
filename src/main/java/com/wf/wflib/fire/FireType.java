package com.wf.wflib.fire;

/** A kind of burning. */
public enum FireType {

    /** Ordinary burning fuel. Diesel, napalm, a ruptured tank. Goes out in water. */
    NORMAL(2.0F, 60, false),
    /** White phosphorus. Hotter, sticks far longer, and keeps burning underwater. */
    PHOSPHORUS(5.0F, 300, true);

    /** Damage per burn interval (see {@code FireHandler}). */
    public final float damage;
    /** How long an entity caught by this keeps burning, in ticks. */
    public final int burnTicks;
    /** Whether a lingering fire of this kind survives being in water. */
    public final boolean survivesWater;

    FireType(float damage, int burnTicks, boolean survivesWater) {
        this.damage = damage;
        this.burnTicks = burnTicks;
        this.survivesWater = survivesWater;
    }

    /**
     * @return the kind with this ordinal, or {@link #NORMAL} if the id is out of range. Used where the kind
     *      arrives as a raw int: synched entity data and saved NBT, neither of which can be trusted to
     *      match the current set of constants.
     */
    public static FireType byId(int id) {
        FireType[] values = values();
        return id >= 0 && id < values.length ? values[id] : NORMAL;
    }

    /** @return the ordinal this is persisted and synched as. */
    public int id() {
        return this.ordinal();
    }
}
