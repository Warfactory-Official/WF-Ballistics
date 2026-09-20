package com.wf.wflib.flight;

import java.util.Locale;

/**
 * How the terminal attack trades speed, steepness and path length when the preferred dive angle does not fit the
 * airframe's turn radius at full speed.
 */
public enum AttackProfile {
    SPEED,
    BALANCED,
    LOFT;

    public static AttackProfile byName(String name) {
        if (name == null || name.isEmpty()) {
            return SPEED;
        }
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SPEED;
        }
    }
}
