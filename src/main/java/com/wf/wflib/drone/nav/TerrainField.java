package com.wf.wflib.drone.nav;

/**
 * An immutable, coarse height map of a patch of world: the surface height of every {@link #cell}-block square in a
 * rectangle, captured on the world thread and then read freely by the path planner on a worker.
 */
public final class TerrainField {

    /**
     * Marker for a column that was not loaded and so was never sampled.
     */
    public static final short UNKNOWN = Short.MIN_VALUE;

    private final int originX;
    private final int originZ;
    private final int cell;
    private final int width;
    private final int depth;
    private final short[] tops;
    private final int fallbackTop;

    public TerrainField(int originX, int originZ, int cell, int width, int depth, short[] tops, int fallbackTop) {
        this.originX = originX;
        this.originZ = originZ;
        this.cell = cell;
        this.width = width;
        this.depth = depth;
        this.tops = tops;
        this.fallbackTop = fallbackTop;
    }

    public int cell() {
        return cell;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    /**
     * @return the height assumed for ground that was never sampled.
     */
    public int fallbackTop() {
        return fallbackTop;
    }

    public int cellX(double worldX) {
        return Math.floorDiv((int) Math.floor(worldX) - originX, cell);
    }

    public int cellZ(double worldZ) {
        return Math.floorDiv((int) Math.floor(worldZ) - originZ, cell);
    }

    /**
     * @return the world X of the centre of this cell column.
     */
    public double worldX(int cellX) {
        return originX + cellX * cell + cell * 0.5;
    }

    public double worldZ(int cellZ) {
        return originZ + cellZ * cell + cell * 0.5;
    }

    public boolean inBounds(int cellX, int cellZ) {
        return cellX >= 0 && cellZ >= 0 && cellX < width && cellZ < depth;
    }

    public int index(int cellX, int cellZ) {
        return cellZ * width + cellX;
    }

    /**
     * @return the surface height of a cell, or {@link #fallbackTop()} outside the field or where the column
     *      was never loaded.
     */
    public int top(int cellX, int cellZ) {
        if (!inBounds(cellX, cellZ)) {
            return fallbackTop;
        }
        short value = tops[index(cellX, cellZ)];
        return value == UNKNOWN ? fallbackTop : value;
    }

    /**
     * @return the surface height under a world position.
     */
    public int topAt(double worldX, double worldZ) {
        return top(cellX(worldX), cellZ(worldZ));
    }

    /**
     * @return the highest ground within {@code radius} blocks of a world position. What the planner actually
     *      cares about: a drone is three blocks wide and should not thread a gap its own body will not fit down.
     */
    public int topAround(double worldX, double worldZ, double radius) {
        int steps = Math.max(0, (int) Math.ceil(radius / cell));
        int cx = cellX(worldX);
        int cz = cellZ(worldZ);
        int highest = Integer.MIN_VALUE;
        for (int dz = -steps; dz <= steps; dz++) {
            for (int dx = -steps; dx <= steps; dx++) {
                highest = Math.max(highest, top(cx + dx, cz + dz));
            }
        }
        return highest;
    }

    /**
     * @return the highest ground under the straight line between two world points, sampled at cell
     *      resolution. Used both to test whether a path segment can be flown and to pick the height to fly it at.
     */
    public int topAlong(double fromX, double fromZ, double toX, double toZ, double radius) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(distance / cell));
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            highest = Math.max(highest, topAround(fromX + dx * t, fromZ + dz * t, radius));
        }
        return highest;
    }
}
