package com.wf.wflib.grenade;

import com.wf.wflib.entity.MistEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Live smoke cells per server level; mob line of sight fails through {@link #OPAQUE} blocks of smoke. */
public final class SmokeScreens {

    /** Blocks of smoke along a sight line that hide what is behind it. */
    public static final double OPAQUE = 2.0;

    private static final Map<Level, List<MistEntity>> CELLS = new IdentityHashMap<>();

    private SmokeScreens() {
    }

    public static void add(MistEntity cell) {
        CELLS.computeIfAbsent(cell.level(), l -> new ArrayList<>()).add(cell);
    }

    public static void remove(MistEntity cell) {
        List<MistEntity> cells = CELLS.get(cell.level());
        if (cells != null) {
            cells.remove(cell);
            if (cells.isEmpty()) {
                CELLS.remove(cell.level());
            }
        }
    }

    /** Server thread. */
    public static boolean blocked(Level level, Vec3 from, Vec3 to) {
        List<MistEntity> cells = CELLS.get(level);
        if (cells == null) {
            return false;
        }
        double length = from.distanceTo(to);
        if (length < 1.0E-6) {
            return false;
        }
        AABB span = new AABB(from, to);
        double inside = 0;
        for (MistEntity cell : cells) {
            AABB box = cell.getBoundingBox();
            if (!box.intersects(span)) {
                continue;
            }
            inside += length * chord(box, from, to);
            if (inside >= OPAQUE) {
                return true;
            }
        }
        return false;
    }

    /** Fraction of {@code from -> to} inside {@code box}. */
    static double chord(AABB box, Vec3 from, Vec3 to) {
        double enter = 0;
        double exit = 1;
        double[] o = {from.x, from.y, from.z};
        double[] d = {to.x - from.x, to.y - from.y, to.z - from.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-12) {
                if (o[i] < min[i] || o[i] > max[i]) {
                    return 0;
                }
                continue;
            }
            double a = (min[i] - o[i]) / d[i];
            double b = (max[i] - o[i]) / d[i];
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
            if (enter >= exit) {
                return 0;
            }
        }
        return exit - enter;
    }
}
