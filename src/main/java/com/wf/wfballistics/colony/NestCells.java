package com.wf.wfballistics.colony;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the mounds of one colony sit relative to each other: a hex lattice of cells, rotated by an angle
 * drawn from the colony's id, spaced so neighbouring domes just overlap.
 *
 * <p>Every position is a pure function of {@code (colony, index)}, so a cluster is stored as a count rather
 * than as a list of positions and is laid out identically on every load.
 */
public final class NestCells {

    /** The six axial directions of a hex lattice, in walk order around a ring. */
    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}
    };

    /**
     * One mound's centre column. No y: height is sampled from the terrain when the blocks are stamped.
     *
     * @param index which cell this is, 0 being the colony's original mound
     */
    public record Cell(int index, int x, int z) {
    }

    private NestCells() {
    }

    /**
     * @return how many cells a colony of this tier may bud, beyond the one it starts with
     */
    public static int cap(int tier) {
        return Math.max(0, ColonyConfig.budCapBase() + ColonyConfig.budCapPerTier() * tier);
    }

    /**
     * @return how far apart neighbouring mounds of this colony sit. One block closer than two domes need to
     * stand clear, so they intersect at the rim and the cluster reads as one organism with lobes.
     */
    public static int spacing(Colony colony) {
        return Math.max(2, 2 * colony.nestRadius() - 1);
    }

    /** Every mound this colony has, original first and buds in the order they were grown. */
    public static List<Cell> cells(Colony colony) {
        return cells(colony, 0, colony.buds);
    }

    /** The same, restricted to a run of indices, so growing the eighth cell stamps only the eighth cell. */
    public static List<Cell> cells(Colony colony, int from, int to) {
        List<Cell> out = new ArrayList<>(Math.max(0, to - from + 1));
        for (int index = Math.max(0, from); index <= to; index++) {
            out.add(cell(colony, index));
        }
        return out;
    }

    /**
     * @param index 0 for the colony's original mound, 1 and up for buds in the order they were grown
     */
    public static Cell cell(Colony colony, int index) {
        if (index <= 0) {
            return new Cell(0, colony.x, colony.z);
        }
        int[] axial = axial(index);
        int spacing = spacing(colony);

        // Axial to world, for a lattice whose neighbours are `spacing` apart.
        double x = spacing * (axial[0] + axial[1] * 0.5);
        double z = spacing * axial[1] * Math.sqrt(3.0) / 2.0;

        // A sixth of a turn covers every distinct orientation of a hex lattice; beyond that it repeats.
        double angle = ((colony.id.hashCode() & 0xFFFF) / 65536.0) * (Math.PI / 3.0);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Cell(index,
                colony.x + (int) Math.round(x * cos - z * sin),
                colony.z + (int) Math.round(x * sin + z * cos));
    }

    /**
     * @return how far the outermost cell centre is from the colony's own. Walked rather than read off
     * {@code ring * spacing}, which rounding understates by up to a block — enough to orphan a far chamber.
     */
    public static int extent(Colony colony) {
        int furthest = 0;
        for (int index = 1; index <= colony.buds; index++) {
            Cell cell = cell(colony, index);
            int dx = cell.x() - colony.x;
            int dz = cell.z() - colony.z;
            furthest = Math.max(furthest, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz)));
        }
        return furthest;
    }

    /** Walk the spiral to the {@code n}th cell. Bud counts are single digits, so iterate rather than solve. */
    private static int[] axial(int n) {
        int ring = 1;
        int index = n - 1;
        while (index >= 6 * ring) {
            index -= 6 * ring;
            ring++;
        }

        // Start at the ring's corner along direction 4 and walk the six sides, recording before each step.
        int q = DIRECTIONS[4][0] * ring;
        int r = DIRECTIONS[4][1] * ring;
        int count = 0;
        for (int side = 0; side < 6; side++) {
            for (int step = 0; step < ring; step++) {
                if (count == index) {
                    return new int[]{q, r};
                }
                q += DIRECTIONS[side][0];
                r += DIRECTIONS[side][1];
                count++;
            }
        }
        return new int[]{q, r};
    }
}
