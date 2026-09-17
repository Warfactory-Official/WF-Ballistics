package com.wf.wfballistics.recon.snapshot;

/**
 * An immutable, deliberately coarse picture of the ground around one sensor: the surface height and surface
 * material of every {@link #CELL}-block column in a square, captured on the world thread and then read freely by
 * the detection pass on a worker.
 */
public final class ReconTerrain {

    /** Blocks per column. */
    public static final int CELL = 8;
    /**
     * Marker for a column that was never sampled.
     */
    public static final short UNKNOWN = Short.MIN_VALUE;

    public static final byte MAT_UNKNOWN = 0;
    public static final byte MAT_ROCK = 1;
    public static final byte MAT_SOIL = 2;
    public static final byte MAT_SAND = 3;
    public static final byte MAT_WATER = 4;
    public static final byte MAT_WOOD = 5;
    public static final byte MAT_METAL = 6;

    /** Marker for a column whose climate was never sampled. */
    public static final byte CLIMATE_UNKNOWN = 0;
    /** Biome temperature the climate scale runs between, in vanilla units: snowy peaks to desert. */
    private static final double TEMP_MIN = -0.5;
    private static final double TEMP_MAX = 2.0;
    /** What unknown ground is assumed to be. */
    public static final double DEFAULT_TEMP = 0.8;
    public static final double DEFAULT_DOWNFALL = 0.4;

    /** An empty field, used where terrain has not been built yet. */
    public static final ReconTerrain EMPTY = new ReconTerrain(0, 0, 1, 1,
            new short[]{UNKNOWN}, new short[]{UNKNOWN}, new byte[]{MAT_UNKNOWN}, new byte[]{CLIMATE_UNKNOWN},
            Short.MIN_VALUE / 2);

    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    private final short[] height;
    private final short[] seabed;
    private final byte[] material;
    private final byte[] climate;
    private final int fallbackHeight;

    /**
     * @param height the highest surface in each cell, water included. The maximum over the cell, so the grid
     *      over-reports on purpose and {@code margin} on the sight-line walks is where that bias goes.
     * @param seabed the lowest ground in each cell, water excluded: the deepest channel through it. The
     *      minimum for the opposite reason — sonar should not invent a wall across a strait narrower
     *      than one cell.
     */
    public ReconTerrain(int originX, int originZ, int width, int depth,
                        short[] height, short[] seabed, byte[] material, byte[] climate, int fallbackHeight) {
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.seabed = seabed;
        this.material = material;
        this.climate = climate;
        this.fallbackHeight = fallbackHeight;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public int fallbackHeight() {
        return fallbackHeight;
    }

    /**
     * @return bytes held, for the leak and cost reporting the design asks for. A 512-block radius is 96 KB:
     *      two bytes of surface height, two of seabed, one of material and one of climate per
     *      eight-block column.
     */
    public int byteSize() {
        return height.length * 2 + seabed.length * 2 + material.length + climate.length;
    }

    public int cellX(double worldX) {
        return Math.floorDiv((int) Math.floor(worldX) - originX, CELL);
    }

    public int cellZ(double worldZ) {
        return Math.floorDiv((int) Math.floor(worldZ) - originZ, CELL);
    }

    public boolean inBounds(int cx, int cz) {
        return cx >= 0 && cz >= 0 && cx < width && cz < depth;
    }

    public int heightAt(double worldX, double worldZ) {
        int cx = cellX(worldX);
        int cz = cellZ(worldZ);
        if (!inBounds(cx, cz)) {
            return fallbackHeight;
        }
        short h = height[cz * width + cx];
        return h == UNKNOWN ? fallbackHeight : h;
    }

    /**
     * @return the y of the deepest solid ground in this cell. Equal to {@link #heightAt} on dry land, and the
     *      floor of the water column where there is one.
     */
    public int seabedAt(double worldX, double worldZ) {
        int cx = cellX(worldX);
        int cz = cellZ(worldZ);
        if (!inBounds(cx, cz)) {
            return fallbackHeight;
        }
        short h = seabed[cz * width + cx];
        return h == UNKNOWN ? fallbackHeight : h;
    }

    public byte materialAt(double worldX, double worldZ) {
        int cx = cellX(worldX);
        int cz = cellZ(worldZ);
        if (!inBounds(cx, cz)) {
            return MAT_UNKNOWN;
        }
        return material[cz * width + cx];
    }

    /** Pack a biome's climate into one byte: temperature in the high nibble, downfall in the low one. */
    public static byte packClimate(double baseTemp, double downfall) {
        double t = (baseTemp - TEMP_MIN) / (TEMP_MAX - TEMP_MIN);
        int tb = 1 + (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * 14.0);
        int db = (int) Math.round(Math.max(0.0, Math.min(1.0, downfall)) * 15.0);
        return (byte) ((tb << 4) | db);
    }

    /**
     * @return the biome temperature of the ground here, in vanilla units, or {@link #DEFAULT_TEMP} where
     *      nothing was sampled.
     */
    public double baseTempAt(double worldX, double worldZ) {
        byte packed = climateAt(worldX, worldZ);
        if (packed == CLIMATE_UNKNOWN) {
            return DEFAULT_TEMP;
        }
        int bucket = Math.max(0, ((packed >> 4) & 0xF) - 1);
        return TEMP_MIN + bucket / 14.0 * (TEMP_MAX - TEMP_MIN);
    }

    /**
     * @return the biome downfall here, 0 to 1, or {@link #DEFAULT_DOWNFALL} where nothing was sampled.
     */
    public double downfallAt(double worldX, double worldZ) {
        byte packed = climateAt(worldX, worldZ);
        return packed == CLIMATE_UNKNOWN ? DEFAULT_DOWNFALL : (packed & 0xF) / 15.0;
    }

    private byte climateAt(double worldX, double worldZ) {
        int cx = cellX(worldX);
        int cz = cellZ(worldZ);
        if (!inBounds(cx, cz)) {
            return CLIMATE_UNKNOWN;
        }
        return climate[cz * width + cx];
    }

    /**
     * Surface line of sight, as a height-profile walk rather than a voxel raycast.
     *
     * @param margin blocks the terrain must rise <em>above</em> the sight line before it masks. Each cell
     *      holds the highest ground in an 8x8 square, so the grid over-reports by nature; this is
     *      where that bias goes. Zero means grazing the skyline counts as masked.
     */
    public boolean lineOfSight(double x0, double y0, double z0, double x1, double y1, double z1, double margin) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int steps = (int) Math.ceil(horizontal / CELL);
        if (steps <= 1) {
            return true;
        }
        double dy = y1 - y0;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double ground = heightAt(x0 + dx * t, z0 + dz * t);
            if (ground - margin > y0 + dy * t) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return true if this position is close enough to the ground that a slow-moving return would be lost in
     *      the clutter. Half of the doppler notch mechanic; the other half is radial velocity.
     */
    public boolean nearClutter(double worldX, double worldY, double worldZ, double margin) {
        return worldY - heightAt(worldX, worldZ) <= margin;
    }

    /**
     * Sonar's line of sight: the same height-profile walk, run against both surfaces at once.
     *
     * <p>Two tests, and between them they cover a shoreline, an island, a seabed ridge and a target that has
     * left the water, with no special case for any of them. The path is blocked where the bottom rises into
     * it, and blocked where the local surface falls below it.
     *
     * @param margin blocks the seabed must rise <em>above</em> the path before it stops it. The seabed array
     *      is a per-cell minimum, so unlike {@link #lineOfSight} this grid under-reports blockage and
     *      the margin is small.
     */
    public WaterPath waterPath(double x0, double y0, double z0, double x1, double y1, double z1,
                               double margin) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double dy = y1 - y0;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(2, (int) Math.ceil(horizontal / CELL));
        double sum = 0.0;
        int samples = 0;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int cx = cellX(x0 + dx * t);
            int cz = cellZ(z0 + dz * t);
            if (!inBounds(cx, cz)) {
                continue;
            }
            short surface = height[cz * width + cx];
            short floor = seabed[cz * width + cx];
            // An unsampled cell is not evidence of land, and a field that has not been built yet must not
            // read as a continent: this is the one place the fallback height would be actively wrong.
            if (surface == UNKNOWN || floor == UNKNOWN) {
                continue;
            }
            double rayY = y0 + dy * t;
            if (floor - margin > rayY || surface < rayY) {
                return new WaterPath(true, 0.0, samples);
            }
            sum += Math.max(0.0, surface - floor);
            samples++;
        }
        return new WaterPath(false, samples == 0 ? 0.0 : sum / samples, samples);
    }

    /**
     * @param meanDepth mean blocks of water over the path. Zero on a blocked one, which never reaches a
     *      caller that would use it.
     * @param samples how many cells the walk actually had data for. Zero means nothing is known about the
     *      path rather than that it is dry, and a shallow-water penalty applied to it would be a
     *      number invented out of an unbuilt field.
     */
    public record WaterPath(boolean blocked, double meanDepth, int samples) {
    }
}
