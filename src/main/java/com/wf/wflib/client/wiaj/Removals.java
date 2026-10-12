package com.wf.wflib.client.wiaj;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Blocks the client saw turn to air (server updates, render thread). Blast debris is cut from these only. */
public final class Removals {
    /** Kept this many ticks; a blast claims removals up to this old. */
    static final int KEEP_TICKS = 40;
    static final int CAP = 16384;

    public record Removed(int x, int y, int z, BlockState state, long tick) {
    }

    private static final ArrayDeque<Removed> RECENT = new ArrayDeque<>();

    private Removals() {
    }

    public static void changed(BlockPos pos, BlockState from, BlockState to) {
        if (!to.isAir() || from.isAir() || from.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        prune(now);
        if (RECENT.size() >= CAP) {
            RECENT.pollFirst();
        }
        RECENT.addLast(new Removed(pos.getX(), pos.getY(), pos.getZ(), from, now));
    }

    /** Removes and returns removals within {@code reach} of the point since {@code since}. */
    static List<Removed> claim(double x, double y, double z, double reach, long since) {
        List<Removed> out = new ArrayList<>();
        double r2 = reach * reach;
        for (Iterator<Removed> it = RECENT.iterator(); it.hasNext(); ) {
            Removed r = it.next();
            double dx = r.x + 0.5 - x, dy = r.y + 0.5 - y, dz = r.z + 0.5 - z;
            if (r.tick >= since && dx * dx + dy * dy + dz * dz <= r2) {
                out.add(r);
                it.remove();
            }
        }
        return out;
    }

    private static void prune(long now) {
        while (!RECENT.isEmpty() && now - RECENT.peekFirst().tick > KEEP_TICKS) {
            RECENT.pollFirst();
        }
    }

    public static void clear() {
        RECENT.clear();
    }
}
