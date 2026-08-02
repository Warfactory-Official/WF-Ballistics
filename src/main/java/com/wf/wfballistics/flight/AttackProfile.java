package com.wf.wfballistics.flight;

import java.util.Locale;

/**
 * How the terminal attack trades speed, steepness and path length when the preferred dive angle does not fit
 * the airframe's turn radius at full speed. All three fly a vertical-plane trajectory (level, then a pitch-over
 * at the minimum turn radius, then the dive) and never circle laterally; they differ only in what they give up:
 *
 * <ul>
 *   <li>{@link #SPEED} - never sheds speed and never detours. Flies the steepest dive that is feasible at full
 *       speed (its preferred angle when there is room, steeper when close); a target inside the turn radius is
 *       simply overflown. Most survivable.</li>
 *   <li>{@link #BALANCED} - prefers full speed but will shed down to a bounded fraction of cruise speed (which
 *       shrinks the turn radius) just enough to make the preferred dive angle feasible.</li>
 *   <li>{@link #LOFT} - never sheds speed but may fly a bounded vertical over-the-top (a vertical-plane Dubins
 *       path) to set up the preferred steep dive, falling back to {@link #SPEED} if the loft would be extreme.</li>
 * </ul>
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
