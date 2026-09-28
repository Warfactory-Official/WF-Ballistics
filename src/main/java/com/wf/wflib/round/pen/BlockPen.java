package com.wf.wflib.round.pen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Non-destructive block penetration. Capacity {@code C = blockPen * |v|^2 / muzzleSpeed^2} (mm steel-eq, KE-scaled);
 * cost {@code c = resistance * t}, {@code t} = ray length inside the shape's boxes (gaps free). {@code c < C} =>
 * exits at the last box exit, {@code |v'| = |v| sqrt(1 - c/C)}, heading turned by {@code u * MAX_DEFLECTION * c/C}
 * about a seeded azimuth; else stops at depth reaching {@code C}. Client prediction runs the same function.
 */
public final class BlockPen {

    /** Blocks pierced per step; the next one stops the round. */
    public static final int MAX_PER_STEP = 64;
    /** Radians at {@code c/C = 1}. */
    public static final double MAX_DEFLECTION = Math.toRadians(8.0);

    private BlockPen() {
    }

    /**
     * @param exited false => stopped at {@code point}
     * @param point  exit or stop point
     * @param velocity after the block; zero when stopped
     * @param spent  {@code c/C} (>= 1 when stopped)
     */
    public record Pass(boolean exited, Vec3 point, Vec3 velocity, double spent) {
    }

    /** Capacity at {@code speed}, blocks/tick. */
    public static double capacity(float blockPen, float muzzleSpeed, double speed) {
        return muzzleSpeed > 0.0f ? blockPen * speed * speed / ((double) muzzleSpeed * muzzleSpeed) : blockPen;
    }

    /**
     * @param shape collision shape at {@code pos}, local coordinates
     * @param entry where the ray meets the shape
     */
    public static Pass pass(VoxelShape shape, BlockPos pos, Vec3 entry, Vec3 velocity, float resistance,
                            double capacity, long seed) {
        double speed = velocity.length();
        Vec3 d = velocity.scale(1.0 / speed);
        double[] spans = spans(shape.toAabbs(), entry.x - pos.getX(), entry.y - pos.getY(), entry.z - pos.getZ(),
                d);
        double inside = 0.0;
        double exit = 0.0;
        for (int k = 0; k < spans.length; k += 2) {
            double len = spans[k + 1] - spans[k];
            if (resistance * (inside + len) >= capacity) {
                double depth = spans[k] + (capacity / resistance - inside);
                return new Pass(false, entry.add(d.scale(depth)), Vec3.ZERO, resistance * (inside + len) / capacity);
            }
            inside += len;
            exit = spans[k + 1];
        }
        double spent = resistance * inside / capacity;
        Vec3 heading = deflect(d, spent * MAX_DEFLECTION, seed);
        return new Pass(true, entry.add(d.scale(exit)), heading.scale(speed * Math.sqrt(1.0 - spent)), spent);
    }

    /**
     * Union of the ray's intervals in {@code boxes} from {@code t >= 0}, sorted, merged: {@code [t0, t1, ...]}.
     * Ray starts at the local point {@code (x, y, z)}.
     */
    static double[] spans(List<AABB> boxes, double x, double y, double z, Vec3 d) {
        double[] raw = new double[boxes.size() * 2];
        int n = 0;
        for (AABB b : boxes) {
            double t0 = Math.max(0.0, near(b.minX, b.maxX, x, d.x));
            double t1 = far(b.minX, b.maxX, x, d.x);
            t0 = Math.max(t0, near(b.minY, b.maxY, y, d.y));
            t1 = Math.min(t1, far(b.minY, b.maxY, y, d.y));
            t0 = Math.max(t0, near(b.minZ, b.maxZ, z, d.z));
            t1 = Math.min(t1, far(b.minZ, b.maxZ, z, d.z));
            if (t1 > t0) {
                raw[n++] = t0;
                raw[n++] = t1;
            }
        }
        sortPairs(raw, n);
        int m = 0;
        for (int k = 0; k < n; k += 2) {
            if (m > 0 && raw[k] <= raw[m - 1]) {
                raw[m - 1] = Math.max(raw[m - 1], raw[k + 1]);
            } else {
                raw[m++] = raw[k];
                raw[m++] = raw[k + 1];
            }
        }
        return java.util.Arrays.copyOf(raw, m);
    }

    /** Slab entry along one axis; parallel => -inf inside (+-1e-7), +inf outside. */
    private static double near(double lo, double hi, double o, double v) {
        if (v == 0.0) {
            return o < lo - 1.0e-7 || o > hi + 1.0e-7 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }
        return Math.min((lo - o) / v, (hi - o) / v);
    }

    private static double far(double lo, double hi, double o, double v) {
        if (v == 0.0) {
            return o < lo - 1.0e-7 || o > hi + 1.0e-7 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        return Math.max((lo - o) / v, (hi - o) / v);
    }

    private static void sortPairs(double[] a, int n) {
        for (int i = 2; i < n; i += 2) {
            double s = a[i];
            double e = a[i + 1];
            int j = i - 2;
            while (j >= 0 && a[j] > s) {
                a[j + 2] = a[j];
                a[j + 3] = a[j + 1];
                j -= 2;
            }
            a[j + 2] = s;
            a[j + 3] = e;
        }
    }

    /** {@code d} (unit) turned by {@code u * max}, {@code u} and the azimuth from {@code seed}. */
    static Vec3 deflect(Vec3 d, double max, long seed) {
        if (max <= 0.0) {
            return d;
        }
        long h1 = mix(seed);
        long h2 = mix(h1);
        double angle = max * unit(h1);
        double azimuth = 2.0 * Math.PI * unit(h2);
        Vec3 ref = Math.abs(d.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 p = d.cross(ref).normalize();
        Vec3 q = d.cross(p);
        Vec3 off = p.scale(Math.cos(azimuth)).add(q.scale(Math.sin(azimuth)));
        return d.scale(Math.cos(angle)).add(off.scale(Math.sin(angle)));
    }

    /** Per block and shot: {@code base} = round key, or shooter/seq for predicted shots (client agrees). */
    public static long seed(long base, BlockPos pos) {
        return mix(base ^ mix(pos.asLong()));
    }

    /** Seed base of a shot with a {@code shooterSeq}; others use the round key. */
    public static long shot(int shooter, int seq) {
        return (long) shooter << 32 ^ (seq & 0xFFFFFFFFL) ^ 0x5EEDL << 48;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 finaliser. */
    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /** Entry nudged past the exit so the next clip does not re-meet this block. */
    public static Vec3 resume(Vec3 exit, Vec3 velocity) {
        return exit.add(velocity.normalize().scale(1.0e-4));
    }
}
