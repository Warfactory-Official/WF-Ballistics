package com.wf.wflib.debug;

import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidTasks;
import com.wf.wflib.entity.glyphid.GlyphidTracker;
import com.wf.wflib.entity.glyphid.brain.GlyphidBrain;
import com.wf.wflib.entity.glyphid.brain.GlyphidMind;
import com.wf.wflib.entity.glyphid.nav.GlyphidFlowField;
import com.wf.wflib.entity.glyphid.nav.GlyphidFlowFields;
import com.wf.wflib.entity.glyphid.sim.SimGlyphid;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidRegistry;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class GlyphidCensus {

    /** Stuck for longer than this and a glyphid has given up walking; see {@link GlyphidBrain#STUCK_TICKS}. */
    private static final int STUCK_REPORTABLE = GlyphidBrain.STUCK_TICKS / 2;

    private GlyphidCensus() {
    }

    /** One tally of the swarm. */
    /**
     * @param highest the top of the swarm. In an arena with a vertical problem in it this is the result:
     *      "how many are chewing" cannot tell a swarm eating into the foot of a tower from one
     *      eating through the overhang at the top, and those are opposite outcomes
     * @param chewY mean height of the blocks being eaten, or {@code NaN} if nothing is being eaten
     */
    public record Tally(int entities, int records, int chewing, int climbing, int airborne, int anchored,
                        int hunting, int stuck, int flowing, int walking, int idle, double meanDistance,
                        double nearestDistance, int arrived, int destX, int destY, int destZ,
                        double highest, double chewY) {
    }

    /**
     * @param arrivalRadius how near the objective counts as arrived
     */
    public static Tally take(ServerLevel level, double arrivalRadius) {
        int entities = 0;
        int chewing = 0;
        int climbing = 0;
        int airborne = 0;
        int anchored = 0;
        int hunting = 0;
        int stuck = 0;
        int flowing = 0;
        int walking = 0;
        int idle = 0;
        int arrived = 0;
        double total = 0.0;
        double nearest = Double.MAX_VALUE;
        double highest = Double.NEGATIVE_INFINITY;
        double chewTotal = 0.0;
        int chewCounted = 0;
        int destX = 0;
        int destY = 0;
        int destZ = 0;
        boolean haveDestination = false;

        for (EntityGlyphid glyphid : GlyphidTracker.glyphids(level)) {
            entities++;
            GlyphidMind mind = glyphid.mind();
            if (!haveDestination) {
                destX = glyphid.taskX;
                destY = glyphid.taskY;
                destZ = glyphid.taskZ;
                haveDestination = true;
            }
            highest = Math.max(highest, glyphid.getY());
            if (mind.chewing) {
                chewing++;
                chewTotal += mind.chewY;
                chewCounted++;
            }
            if (glyphid.isBesideClimbableBlock()) {
                climbing++;
            }
            if (glyphid.isAirborne()) {
                airborne++;
            }
            if (glyphid.hasBridgeSlot()) {
                anchored++;
            }
            if (glyphid.getTarget() != null) {
                hunting++;
            }
            if (mind.stuckFor >= STUCK_REPORTABLE) {
                stuck++;
            }
            if (onField(level, glyphid)) {
                flowing++;
            } else if (!glyphid.getNavigation().isDone()) {
                walking++;
            } else {
                idle++;
            }

            double distance = distance(glyphid.getX(), glyphid.getY(), glyphid.getZ(),
                    glyphid.taskX, glyphid.taskY, glyphid.taskZ);
            total += distance;
            nearest = Math.min(nearest, distance);
            if (distance <= arrivalRadius) {
                arrived++;
            }
        }

        int records = 0;
        for (SimGlyphid sim : SimGlyphidRegistry.get(level).view()) {
            records++;
            highest = Math.max(highest, sim.y);
            if (sim.mind().chewing || sim.wantsChew) {
                chewing++;
            }
            if (!haveDestination) {
                destX = sim.taskX;
                destY = sim.taskY;
                destZ = sim.taskZ;
                haveDestination = true;
            }
            if (sim.mind().stuckFor >= STUCK_REPORTABLE) {
                stuck++;
            }
            double distance = distance(sim.x, sim.y, sim.z, sim.taskX, sim.taskY, sim.taskZ);
            total += distance;
            nearest = Math.min(nearest, distance);
            if (distance <= arrivalRadius) {
                arrived++;
            }
        }

        int population = entities + records;
        return new Tally(entities, records, chewing, climbing, airborne, anchored, hunting, stuck, flowing,
                walking, idle, population == 0 ? 0.0 : total / population,
                population == 0 ? 0.0 : nearest, arrived, destX, destY, destZ,
                population == 0 ? Double.NaN : highest,
                chewCounted == 0 ? Double.NaN : chewTotal / chewCounted);
    }

    /**
     * Whether this glyphid is being steered by the field <em>this tick</em>, gated exactly as {@code
     * GlyphidBody.flowStep} gates it.
     */
    private static boolean onField(ServerLevel level, EntityGlyphid glyphid) {
        if (glyphid.getTarget() != null || glyphid.getCurrentTask() != GlyphidTasks.TASK_FOLLOW
                || glyphid.isAirborne() || glyphid.mind().chewing) {
            return false;
        }
        GlyphidFlowField field = GlyphidFlowFields.fieldFor(level, glyphid.taskX, glyphid.taskY, glyphid.taskZ);
        return field != null && field.step(glyphid.getX(), glyphid.getY(), glyphid.getZ()) != null;
    }

    private static double distance(double x, double y, double z, int tx, int ty, int tz) {
        double dx = x - (tx + 0.5);
        double dy = y - ty;
        double dz = z - (tz + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * @return the tally as lines, in the order a run is read in: how many, what they are doing, where they got
     */
    public static List<String> report(ServerLevel level, double arrivalRadius) {
        Tally tally = take(level, arrivalRadius);
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%d glyphids: %d entities, %d records, objective (%d, %d, %d)",
                tally.entities() + tally.records(), tally.entities(), tally.records(),
                tally.destX(), tally.destY(), tally.destZ()));
        lines.add(String.format(Locale.ROOT,
                "  navigating: %d on the field, %d pathfinding, %d idle, %d stuck",
                tally.flowing(), tally.walking(), tally.idle(), tally.stuck()));
        lines.add(String.format(Locale.ROOT,
                "  acting: %d chewing%s, %d climbing, %d hunting, %d airborne, %d anchored",
                tally.chewing(),
                Double.isNaN(tally.chewY()) ? "" : String.format(Locale.ROOT, " at y=%.1f", tally.chewY()),
                tally.climbing(), tally.hunting(), tally.airborne(), tally.anchored()));
        lines.add(String.format(Locale.ROOT, "  highest glyphid at y=%.1f", tally.highest()));
        lines.add(String.format(Locale.ROOT,
                "  distance to objective: %.1f mean, %.1f nearest, %d within %.0f",
                tally.meanDistance(), tally.nearestDistance(), tally.arrived(), arrivalRadius));
        return lines;
    }

    /** The tally as one machine-readable line, so a probe does not have to parse four sentences. */
    public static String line(ServerLevel level, double arrivalRadius) {
        Tally tally = take(level, arrivalRadius);
        return String.join(" ", Arrays.asList(
                "census",
                "entities=" + tally.entities(),
                "records=" + tally.records(),
                "chewing=" + tally.chewing(),
                "climbing=" + tally.climbing(),
                "airborne=" + tally.airborne(),
                "anchored=" + tally.anchored(),
                "hunting=" + tally.hunting(),
                "stuck=" + tally.stuck(),
                "flowing=" + tally.flowing(),
                "walking=" + tally.walking(),
                "idle=" + tally.idle(),
                "arrived=" + tally.arrived(),
                String.format(Locale.ROOT, "mean=%.2f", tally.meanDistance()),
                String.format(Locale.ROOT, "nearest=%.2f", tally.nearestDistance()),
                String.format(Locale.ROOT, "highest=%.1f", tally.highest()),
                String.format(Locale.ROOT, "chewy=%.1f", tally.chewY())));
    }
}
