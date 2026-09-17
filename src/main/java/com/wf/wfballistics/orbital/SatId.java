package com.wf.wfballistics.orbital;

/**
 * One satellite's identity, for as long as it exists.
 *
 * @param value unique within a level. Printed as hex everywhere a player sees it.
 */
public record SatId(long value) {

    public static SatId of(long value) {
        return new SatId(value);
    }

    @Override
    public String toString() {
        return Long.toHexString(value);
    }
}
