package com.wf.wfballistics.colony;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * One thing a squad of glyphids has been sent to deal with.
 *
 * <p>Plain data, and deliberately a position rather than a reference to whatever it was derived from: a
 * machine cluster is a centre of mass with no entity behind it, and a player who logs out should leave the
 * squad walking to where they were rather than losing its orders. {@link #player} is the exception, carried
 * so a squad sent after somebody keeps preferring them over whatever else wanders past.
 */
public record GlyphidObjective(Kind kind, int x, int y, int z, @Nullable UUID player) {

    public enum Kind {
        /**
         * Somebody in particular. Two squads never take the same one, which is what splits a group of
         * defenders up instead of landing the whole swarm on whoever is nearest.
         */
        PLAYER,
        /**
         * A base, off the industry pressure field. What makes an attack read as aimed at what you built
         * rather than at you.
         */
        MACHINES,
        /**
         * A hole to make. Assigned when the way to everything else is walled off, so one squad concentrates
         * on opening it instead of the whole swarm piling into the same dead end.
         */
        BREACH,
        /**
         * Where the warband was going in the first place. Always available, so a squad is never left without
         * orders.
         */
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
