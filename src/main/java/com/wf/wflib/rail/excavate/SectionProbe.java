package com.wf.wflib.rail.excavate;

import com.wf.wflib.rail.TunnelBuilder;
import com.wf.wflib.rail.align.Centreline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a finished tunnel's cross-section actually is, beside what it was supposed to be.
 *
 * <p>A tunnel is drawn as a section and swept along a route, and every part of this build agrees about
 * the section right up until blocks are written: from inside, a bore that came out a block narrow, or
 * with one course of its wall missing, looks like a tunnel. This reads the world at the section's own
 * cells and prints the two side by side, so "it looks thinner and it is not walled properly" becomes a
 * picture with a block in every square rather than an impression.</p>
 */
public final class SectionProbe {

    /** Extra columns shown either side of the section, so a missing wall shows what is behind it. */
    private static final int MARGIN = 2;

    private SectionProbe() {
    }

    /** @param drawn the section as designed, {@code built} what is there, in the same order */
    public record Section(List<String> drawn, List<String> built, Map<String, Integer> strangers,
                          double chainage, double x, double z, double heading) {
    }

    /**
     * Read the section at a chainage.
     *
     * <p>Sampled on the corridor's own grid, which is the point: each square is the block the sweep
     * would have decided about, so a square that came out wrong is a block this build got wrong rather
     * than a rounding difference between two ways of measuring.</p>
     */
    public static Section at(ServerLevel level, Centreline line, TunnelProfile profile, int floorY,
                             double chainage) {
        CarveVolume.Corridor path = TunnelBuilder.corridor(line, 0.0, line.length(), profile, floorY);
        double at = Math.max(0.0, Math.min(chainage, path.length()));
        double[] point = path.pointAt(at);
        // Perpendicular to the way the corridor runs, pointing the way the section is written.
        double px = -point[3];
        double pz = point[2];
        double halfWidth = profile.width() / 2.0;

        List<String> drawn = new ArrayList<>();
        List<String> built = new ArrayList<>();
        Map<String, Integer> strangers = new HashMap<>();
        for (int up = profile.highestUp(); up >= profile.lowestUp(); up--) {
            StringBuilder wanted = new StringBuilder();
            StringBuilder there = new StringBuilder();
            for (int across = -MARGIN; across < profile.width() + MARGIN; across++) {
                double offset = across - halfWidth + 0.5;
                int x = (int) Math.floor(point[0] + px * offset);
                int z = (int) Math.floor(point[1] + pz * offset);
                wanted.append(drawnAt(profile, across, up));
                there.append(builtAt(level, new BlockPos(x, floorY + up, z), strangers));
            }
            drawn.add(wanted.toString());
            built.add(there.toString());
        }
        return new Section(drawn, built, strangers, at, point[0], point[1],
                Math.toDegrees(Math.atan2(point[3], point[2])));
    }

    private static char drawnAt(TunnelProfile profile, int across, int up) {
        if (across < 0 || across >= profile.width()) {
            return ' ';
        }
        return switch (profile.at(across, up)) {
            case BORE -> TunnelProfile.CH_BORE;
            case LINING -> TunnelProfile.CH_LINING;
            case TORCH -> TunnelProfile.CH_TORCH;
            default -> ' ';
        };
    }

    /**
     * One block, as a character.
     *
     * <p>Deliberately not "is this what we wanted": the question is what is there, and naming the
     * strangers is how a wall built out of the wrong thing, or a bore that filled back in with gravel,
     * tells on itself.</p>
     */
    private static char builtAt(ServerLevel level, BlockPos pos, Map<String, Integer> strangers) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return TunnelProfile.CH_BORE;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getBlock() == net.minecraft.world.level.block.Blocks.TORCH
                || state.getBlock() == net.minecraft.world.level.block.Blocks.WALL_TORCH) {
            return TunnelProfile.CH_TORCH;
        }
        if (state.is(BlockTags.RAILS) || id.startsWith("immersiverailroading:")) {
            return 'R';
        }
        if (!state.getFluidState().isEmpty()) {
            return '~';
        }
        strangers.merge(id, 1, Integer::sum);
        return id.contains("brick") || id.contains("stone_bricks") ? TunnelProfile.CH_LINING : 'x';
    }

    /** The two sections side by side, with a line per row, for printing into chat. */
    public static List<String> describe(Section section, String routeName) {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "%s at %.0f blocks: %.0f, %.0f, heading %.0f degrees",
                routeName, section.chainage(), section.x(), section.z(), section.heading()));
        out.add("  drawn             built");
        for (int i = 0; i < section.drawn().size(); i++) {
            out.add(String.format(Locale.ROOT, "  %-16s  %s",
                    section.drawn().get(i), section.built().get(i)));
        }
        if (!section.strangers().isEmpty()) {
            List<String> named = new ArrayList<>();
            section.strangers().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(4)
                    .forEach(e -> named.add(e.getKey() + " x" + e.getValue()));
            out.add("  in the section: " + String.join(", ", named));
        }
        return out;
    }
}
