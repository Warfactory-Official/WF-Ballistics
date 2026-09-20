package com.wf.wflib.colony;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/** One thing a squad of glyphids has been sent to deal with. */
public record GlyphidObjective(Kind kind, int x, int y, int z, @Nullable UUID player) {

    public enum Kind {
        /** Somebody in particular. */
        PLAYER,
        /** A base, off the industry pressure field. */
        MACHINES,
        /** A hole to make. */
        BREACH,
        /** Where the warband was going in the first place. */
        RALLY
    }

    public static GlyphidObjective player(UUID id, double x, double y, double z) {
        return new GlyphidObjective(Kind.PLAYER, (int) x, (int) y, (int) z, id);
    }

    public static GlyphidObjective machines(int x, int y, int z) {
        return new GlyphidObjective(Kind.MACHINES, x, y, z, null);
    }

    public static GlyphidObjective breach(int x, int y, int z) {
        return new GlyphidObjective(Kind.BREACH, x, y, z, null);
    }

    public static GlyphidObjective rally(int x, int y, int z) {
        return new GlyphidObjective(Kind.RALLY, x, y, z, null);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s (%d, %d, %d)", kind.name().toLowerCase(Locale.ROOT), x, y, z);
    }
}
