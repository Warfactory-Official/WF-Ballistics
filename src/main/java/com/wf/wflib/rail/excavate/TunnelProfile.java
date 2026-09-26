package com.wf.wflib.rail.excavate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The cross-section of a tunnel, drawn.
 *
 * <p>A width and a height only ever describe a box, and a railway tunnel is not always a box: an arched
 * roof, a service walkway to one side, a wider bore for double track with a refuge between the roads.
 * A profile is that section written out as characters, swept along the route, so designing a new one is
 * drawing it rather than writing code.</p>
 *
 * <pre>
 *   &#64;name arched
 *   &#64;torch 8
 *     ######
 *    #......#
 *   #........#
 *   #........#
 *   #L......L#
 *   ##########
 * </pre>
 *
 * <p>{@code .} is tunnel, {@code #} is lining, {@code L} is tunnel with a torch in it, and anything else
 * is ground the carve does not touch. Rows read top to bottom, and the lowest row containing tunnel is
 * the floor: that is the row the {@code floor} argument names, so standing in the tunnel puts you at
 * that y.</p>
 *
 * <p>The seal is checked when a profile is loaded rather than trusted. A section with one missing wall
 * cell is a tunnel that floods, and the author would find out from the water rather than from the
 * mistake.</p>
 */
public record TunnelProfile(String name, List<String> rows, int floorRow, String lining, int torchSpacing) {

    /** @return the block this section is lined with, or {@code fallback} when it does not say. */
    public String liningOr(String fallback) {
        return this.lining == null || this.lining.isBlank() ? fallback : this.lining;
    }

    /** What one cell of the section is. */
    public enum Kind {
        /** Not touched at all. */
        NONE,
        /** Becomes air. */
        BORE,
        /** Becomes the lining block. */
        LINING,
        /** Becomes air, and carries a torch at the lighting interval. */
        TORCH;

        /** Whether this cell is inside the tunnel, which is what has to be sealed. */
        public boolean inside() {
            return this == BORE || this == TORCH;
        }
    }

    public static final char CH_BORE = '.';
    public static final char CH_LINING = '#';
    public static final char CH_TORCH = 'L';

    /** What a profile is lined with when its own header does not say. */
    public static final String DEFAULT_LINING = "minecraft:deepslate_bricks";

    /** Widest and tallest a section may be. Beyond this it is a hall, not a tunnel. */
    public static final int MAX_SIZE = 48;

    public TunnelProfile {
        rows = List.copyOf(rows);
    }

    /** @return cells across, including the lining. */
    public int width() {
        int width = 0;
        for (String row : this.rows) {
            width = Math.max(width, row.length());
        }
        return width;
    }

    /** @return cells across the tunnel itself, which is what decides where the centreline snaps. */
    public int boreWidth() {
        int min = Integer.MAX_VALUE;
        int max = -1;
        for (int row = 0; row < this.rows.size(); row++) {
            for (int col = 0; col < this.rows.get(row).length(); col++) {
                if (kindOf(this.rows.get(row).charAt(col)).inside()) {
                    min = Math.min(min, col);
                    max = Math.max(max, col);
                }
            }
        }
        return max < 0 ? 0 : max - min + 1;
    }

    /** @return cells of tunnel from the floor to the highest point of the roof. */
    public int boreHeight() {
        int highest = -1;
        for (int row = 0; row < this.rows.size(); row++) {
            for (int col = 0; col < this.rows.get(row).length(); col++) {
                if (kindOf(this.rows.get(row).charAt(col)).inside()) {
                    highest = Math.max(highest, this.floorRow - row);
                }
            }
        }
        return highest + 1;
    }

    /** The lowest and highest offsets from the floor row that this profile touches at all. */
    public int lowestUp() {
        return this.floorRow - (this.rows.size() - 1);
    }

    public int highestUp() {
        return this.floorRow;
    }

    /**
     * @param across column from the left edge of the section, looking along the direction of travel
     * @param up rows above the floor; 0 is the floor of the tunnel, -1 the course below it
     */
    public Kind at(int across, int up) {
        int row = this.floorRow - up;
        if (row < 0 || row >= this.rows.size()) {
            return Kind.NONE;
        }
        String line = this.rows.get(row);
        if (across < 0 || across >= line.length()) {
            return Kind.NONE;
        }
        return kindOf(line.charAt(across));
    }

    private static Kind kindOf(char c) {
        return switch (c) {
            case CH_BORE -> Kind.BORE;
            case CH_LINING -> Kind.LINING;
            case CH_TORCH -> Kind.TORCH;
            default -> Kind.NONE;
        };
    }

    /** Every torch position in the section, as (across, up) pairs. */
    public List<int[]> torchCells() {
        List<int[]> out = new ArrayList<>();
        for (int row = 0; row < this.rows.size(); row++) {
            String line = this.rows.get(row);
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == CH_TORCH) {
                    out.add(new int[]{col, this.floorRow - row});
                }
            }
        }
        return out;
    }

    // -- building one ------------------------------------------------------------------------------

    /** A plain box, which is what {@code /wfrail build size} asks for. */
    public static TunnelProfile box(int width, int height, String lining, int torchSpacing) {
        List<String> rows = new ArrayList<>();
        rows.add(String.valueOf(CH_LINING).repeat(width + 2));
        for (int up = height - 1; up >= 0; up--) {
            String inside = up == 0 && torchSpacing > 0 && width >= 2
                    ? CH_TORCH + String.valueOf(CH_BORE).repeat(width - 2) + CH_TORCH
                    : String.valueOf(CH_BORE).repeat(width);
            rows.add(CH_LINING + inside + CH_LINING);
        }
        rows.add(String.valueOf(CH_LINING).repeat(width + 2));
        return new TunnelProfile(width + "x" + height, rows, rows.size() - 2, lining, torchSpacing);
    }

    /**
     * Read a profile from its text form.
     *
     * @return the profile
     * @throws IllegalArgumentException with a message meant for whoever wrote the file
     */
    public static TunnelProfile parse(String name, List<String> lines) {
        String profileName = name;
        String lining = null;
        int torch = 0;
        List<String> art = new ArrayList<>();

        for (String raw : lines) {
            String line = stripTrailingNewline(raw);
            if (line.startsWith("@")) {
                String[] parts = line.substring(1).split("\\s+", 2);
                String key = parts[0].toLowerCase(Locale.ROOT);
                String value = parts.length > 1 ? parts[1].trim() : "";
                switch (key) {
                    case "name" -> profileName = value.isEmpty() ? profileName : value;
                    case "lining" -> lining = value.isEmpty() ? null : value;
                    case "torch" -> torch = parseTorch(value);
                    default -> { }
                }
                continue;
            }
            if (line.isBlank() && art.isEmpty()) {
                continue;
            }
            art.add(line);
        }
        while (!art.isEmpty() && art.get(art.size() - 1).isBlank()) {
            art.remove(art.size() - 1);
        }
        return validated(profileName, art, lining, torch);
    }

    private static int parseTorch(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("@torch wants a number of blocks, not '" + value + "'");
        }
    }

    private static String stripTrailingNewline(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * Check a drawn section is a tunnel before anyone digs it.
     *
     * <p>The rule that matters is the same one the shell exists for: every cell of the tunnel must have
     * tunnel or lining on all four sides of the section. A gap is a hole in the wall, and the author
     * would otherwise find out about it from the water rather than from the drawing.</p>
     */
    public static TunnelProfile validated(String name, List<String> art, String lining, int torchSpacing) {
        if (art.isEmpty()) {
            throw new IllegalArgumentException("profile '" + name + "' has no section drawn in it");
        }
        int floorRow = -1;
        for (int row = art.size() - 1; row >= 0; row--) {
            for (int col = 0; col < art.get(row).length(); col++) {
                if (kindOf(art.get(row).charAt(col)).inside()) {
                    floorRow = row;
                    break;
                }
            }
            if (floorRow >= 0) {
                break;
            }
        }
        if (floorRow < 0) {
            throw new IllegalArgumentException("profile '" + name + "' has no tunnel in it: draw the"
                    + " inside with '" + CH_BORE + "' and the lining with '" + CH_LINING + "'");
        }
        TunnelProfile profile = new TunnelProfile(name, art, floorRow, lining, torchSpacing);
        if (profile.width() > MAX_SIZE || art.size() > MAX_SIZE) {
            throw new IllegalArgumentException("profile '" + name + "' is larger than "
                    + MAX_SIZE + " blocks; that is a hall, not a tunnel");
        }
        String leak = profile.findLeak();
        if (leak != null) {
            throw new IllegalArgumentException("profile '" + name + "' is not sealed: " + leak);
        }
        String unsupported = profile.findUnsupportedTorch();
        if (unsupported != null) {
            throw new IllegalArgumentException("profile '" + name + "': " + unsupported);
        }
        return profile;
    }

    /** @return a description of the first unsealed cell, or null when the section holds water. */
    public String findLeak() {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int row = 0; row < this.rows.size(); row++) {
            String line = this.rows.get(row);
            for (int col = 0; col < line.length(); col++) {
                if (!kindOf(line.charAt(col)).inside()) {
                    continue;
                }
                int up = this.floorRow - row;
                for (int[] side : sides) {
                    Kind neighbour = at(col + side[0], up + side[1]);
                    if (neighbour == Kind.NONE) {
                        return "the tunnel cell " + col + " across, " + up + " up has nothing beside it"
                                + " at " + (col + side[0]) + ", " + (up + side[1])
                                + "; every side of the tunnel needs '" + CH_LINING + "'";
                    }
                }
            }
        }
        return null;
    }

    /**
     * @return a description of the first torch with nothing under it, or null.
     *
     * <p>A torch needs something to stand on. One drawn in mid-air pops off the moment the block under
     * it is touched, leaving a dark tunnel and an item on the floor.</p>
     */
    public String findUnsupportedTorch() {
        for (int[] cell : torchCells()) {
            if (at(cell[0], cell[1] - 1) != Kind.LINING) {
                return "the torch " + cell[0] + " across, " + cell[1] + " up has nothing to stand on;"
                        + " put a '" + CH_LINING + "' directly below it";
            }
        }
        return null;
    }

    /** The section as it would be drawn, for a command to print back. */
    public List<String> render() {
        return this.rows;
    }
}
