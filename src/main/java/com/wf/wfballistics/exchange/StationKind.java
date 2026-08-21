package com.wf.wfballistics.exchange;

import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Named combinations of {@link StationRole}, for the common cases.
 *
 * <p>Presets over a set, rather than a type in their own right. Nothing in the mod branches on a kind (every
 * decision is "does this station do X", asked of the role set) so a kind is only ever a convenient way to
 * set several roles at once, and a station whose roles do not match any preset is not a broken station, it is
 * a {@link #describe custom} one. That is what lets the list of presets grow later without anything already
 * saved becoming invalid.
 */
public enum StationKind {
    /**
     * Supplies building materials and nothing else. A dump next to a quarry, or a warehouse that feeds a
     * construction site without being part of it.
     */
    PROVIDER("provider", EnumSet.of(StationRole.MATERIALS)),
    /**
     * The full thing: launches, receives, supplies and recharges. What every pad was before roles existed,
     * and therefore what every pad that predates them still is.
     */
    STATION("station", EnumSet.allOf(StationRole.class));

    private final String id;
    private final Set<StationRole> roles;

    StationKind(String id, Set<StationRole> roles) {
        this.id = id;
        this.roles = java.util.Collections.unmodifiableSet(roles);
    }

    public String id() {
        return this.id;
    }

    public Set<StationRole> roles() {
        return this.roles;
    }

    @Nullable
    public static StationKind byId(String id) {
        if (id != null) {
            String wanted = id.trim().toLowerCase(Locale.ROOT);
            for (StationKind kind : values()) {
                if (kind.id.equals(wanted)) {
                    return kind;
                }
            }
        }
        return null;
    }

    /**
     * @return the preset matching this exact set of roles, or null if it is a combination nobody named.
     */
    @Nullable
    public static StationKind of(Set<StationRole> roles) {
        for (StationKind kind : values()) {
            if (kind.roles.equals(roles)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * @return a short label for a role set: the preset's name where there is one, otherwise the roles
     * themselves. Never "invalid": an unnamed combination is a legitimate station
     */
    public static String describe(Set<StationRole> roles) {
        StationKind named = of(roles);
        if (named != null) {
            return named.id;
        }
        if (roles.isEmpty()) {
            return "inert";
        }
        StringBuilder out = new StringBuilder("custom[");
        boolean first = true;
        for (StationRole role : roles) {
            if (!first) {
                out.append(' ');
            }
            out.append(role.id());
            first = false;
        }
        return out.append(']').toString();
    }
}
