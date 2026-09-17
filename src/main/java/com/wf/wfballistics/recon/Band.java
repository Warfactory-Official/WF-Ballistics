package com.wf.wfballistics.recon;

/** The physical channels a sensor can work in. */
public enum Band {
    /**
     * Air and surface, long range. Blind underground and to anything sitting in the doppler notch.
     */
    RADAR(true, 'R'),
    /**
     * Underground movement, digging and blasts. Blind to anything not touching rock.
     */
    SEISMIC(true, 'S'),
    /**
     * Surface events and gunfire. Blind to quiet things, and stopped by walls.
     */
    ACOUSTIC(false, 'A'),
    /**
     * Hot things at short range under strict line of sight. Immune to RF jamming.
     */
    THERMAL(true, 'T'),
    /**
     * Emitters only: radios, radars, running machines. Yields a bearing with no range.
     */
    EM(false, 'E'),
    /**
     * The same noise as {@link #ACOUSTIC}, carried by water instead of air. Reaches much further and stops
     * dead at the shoreline. Appended last on purpose: the ordinal is a wire value in {@code ReconMapPacket}
     * and a bit position in {@code Plot.bandMask}, so nothing above it may move.
     */
    SONAR(true, 'O');

    public static final Band[] VALUES = values();

    private final boolean propagation;
    private final char tag;

    Band(boolean propagation, char tag) {
        this.propagation = propagation;
        this.tag = tag;
    }

    /**
     * @return true if this band has a propagation model, so a sensor in it can actually detect something.
     */
    public boolean hasPropagation() {
        return propagation;
    }

    /**
     * @return one letter naming this band in a compact readout such as {@code "RO"}. Given explicitly rather
     *      than taken from the name, which collides: seismic and sonar both start with S.
     */
    public char tag() {
        return tag;
    }
}
