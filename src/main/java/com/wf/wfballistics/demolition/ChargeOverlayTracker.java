package com.wf.wfballistics.demolition;

import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side set of loaded mining-charge positions, kept by the block entity's load/unload hooks so the overlay
 * renderer can iterate just the charges in range instead of scanning the world each frame.
 */
public final class ChargeOverlayTracker {

    private static final Set<BlockPos> POSITIONS = ConcurrentHashMap.newKeySet();

    private ChargeOverlayTracker() {
    }

    public static void add(BlockPos pos) {
        POSITIONS.add(pos.immutable());
    }

    public static void remove(BlockPos pos) {
        POSITIONS.remove(pos);
    }

    public static Set<BlockPos> positions() {
        return POSITIONS;
    }
}
