package com.wf.wfballistics.debug;

import com.wf.wfballistics.colony.GlyphidObjective;
import com.wf.wfballistics.colony.GlyphidSquads;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class GlyphidSquadCensus {

    private GlyphidSquadCensus() {
    }

    /** One squad, as read back off its members. */
    public record Squad(int index, int members, double power, String objective) {
    }

    /**
     * One group of warbands that came from the same place, and the squads it divided into.
     *
     * @param cell the home cell, as a readable {@code x,z} in cell units
     * @param members how many glyphids are in it
     * @param split whether it is big enough for the splitter to divide at all
     * @param squads the squads found, lowest index first
     */
    public record Host(String cell, int members, boolean split, List<Squad> squads) {

        /** Heaviest squad's power over the lightest's. 1.0 is a perfect partition; below 1.5 is a good one. */
        public double imbalance() {
            if (squads.size() < 2) {
                return 1.0;
            }
            double most = 0.0;
            double least = Double.MAX_VALUE;
            for (Squad squad : squads) {
                most = Math.max(most, squad.power());
                least = Math.min(least, squad.power());
            }
            return least <= 0.0 ? Double.POSITIVE_INFINITY : most / least;
        }
    }

    /**
     * @param hosts the groups found, largest first
     * @param unsplit glyphids the splitter never considers: scouts, and anything yet to take a first tick
     * @param orderless glyphids in a splittable host that still carry no objective
     */
    public record Tally(List<Host> hosts, int unsplit, int orderless) {
    }

    public static Tally take(ServerLevel level) {
        Map<Long, List<EntityGlyphid>> hosts = new LinkedHashMap<>();
        int unsplit = 0;
        for (EntityGlyphid bug : GlyphidTracker.glyphids(level)) {
            if (!GlyphidSquads.splits(bug)) {
                unsplit++;
                continue;
            }
            hosts.computeIfAbsent(GlyphidSquads.homeCell(bug), k -> new ArrayList<>()).add(bug);
        }

        List<Host> out = new ArrayList<>();
        int orderless = 0;
        for (Map.Entry<Long, List<EntityGlyphid>> entry : hosts.entrySet()) {
            List<EntityGlyphid> members = entry.getValue();
            boolean split = GlyphidSquads.splittable(members.size());

            Map<Integer, List<EntityGlyphid>> bySquad = new TreeMap<>();
            for (EntityGlyphid bug : members) {
                if (bug.objective == null) {
                    if (split) {
                        orderless++;
                    }
                    continue;
                }
                bySquad.computeIfAbsent(bug.squad, k -> new ArrayList<>()).add(bug);
            }

            List<Squad> squads = new ArrayList<>();
            for (Map.Entry<Integer, List<EntityGlyphid>> squad : bySquad.entrySet()) {
                double power = 0.0;
                for (EntityGlyphid bug : squad.getValue()) {
                    power += bug.getStats().power();
                }
                squads.add(new Squad(squad.getKey(), squad.getValue().size(), power,
                        describe(squad.getValue().get(0).objective)));
            }
            out.add(new Host(cellName(entry.getKey()), members.size(), split, squads));
        }
        out.sort(Comparator.comparingInt(host -> -host.members()));
        return new Tally(out, unsplit, orderless);
    }

    /** The objective as the report wants it: kind and position. */
    private static String describe(GlyphidObjective objective) {
        return objective == null ? "none" : objective.toString();
    }

    private static String cellName(long cell) {
        return (int) (cell >> 32) + "," + (int) cell;
    }

    public static List<String> report(ServerLevel level) {
        Tally tally = take(level);
        List<String> lines = new ArrayList<>();
        if (tally.hosts().isEmpty()) {
            lines.add("No glyphid is eligible for a squad" + (tally.unsplit() > 0
                    ? " (" + tally.unsplit() + " scouts or unticked)." : "."));
            return lines;
        }
        for (Host host : tally.hosts()) {
            lines.add(String.format(Locale.ROOT, "host %s: %d glyphids, %s, %d squad(s)%s",
                    host.cell(), host.members(),
                    host.split() ? "splittable" : "too small to split", host.squads().size(),
                    host.squads().size() > 1
                            ? String.format(Locale.ROOT, ", %.2fx power imbalance", host.imbalance()) : ""));
            for (Squad squad : host.squads()) {
                lines.add(String.format(Locale.ROOT, "  squad %d: %d glyphids, %.0f power, on %s",
                        squad.index(), squad.members(), squad.power(), squad.objective()));
            }
        }
        if (tally.unsplit() > 0 || tally.orderless() > 0) {
            lines.add(String.format(Locale.ROOT, "  %d never considered, %d considered and still without orders",
                    tally.unsplit(), tally.orderless()));
        }
        return lines;
    }

    public static String line(ServerLevel level) {
        Tally tally = take(level);
        StringBuilder out = new StringBuilder("squads hosts=").append(tally.hosts().size());
        int total = 0;
        double worst = 1.0;
        for (Host host : tally.hosts()) {
            total += host.squads().size();
            if (host.squads().size() > 1) {
                worst = Math.max(worst, host.imbalance());
            }
        }
        out.append(" squads=").append(total)
                .append(" unsplit=").append(tally.unsplit())
                .append(" orderless=").append(tally.orderless())
                .append(String.format(Locale.ROOT, " imbalance=%.2f", worst));
        for (Host host : tally.hosts()) {
            for (Squad squad : host.squads()) {
                out.append(" squad=").append(squad.index()).append(':').append(squad.members())
                        .append(':').append((int) squad.power()).append(':')
                        .append(squad.objective().split(" ")[0]);
            }
        }
        return out.toString();
    }
}
