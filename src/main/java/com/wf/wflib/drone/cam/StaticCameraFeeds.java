package com.wf.wflib.drone.cam;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Feed ids for cameras that are not entities. */
public final class StaticCameraFeeds {

    private static final Map<GlobalPos, Integer> IDS = new HashMap<>();
    private static final Map<Integer, GlobalPos> BY_ID = new HashMap<>();

    /** Positions known to have no camera on them any more. */
    private static final Set<GlobalPos> DEAD = new HashSet<>();

    /** Counts down, so every id is negative and none is ever {@code 0}. */
    private static int next = -1;

    private StaticCameraFeeds() {
    }

    public static void invalidate(ServerLevel level, BlockPos pos) {
        DEAD.add(GlobalPos.of(level.dimension(), pos.immutable()));
    }

    /** A camera placed where one used to be is a working camera again. */
    public static void revive(ServerLevel level, BlockPos pos) {
        DEAD.remove(GlobalPos.of(level.dimension(), pos.immutable()));
    }

    public static boolean dead(GlobalPos at) {
        return DEAD.contains(at);
    }

    /** @return this camera's feed id, allocating one the first time it is asked for. */
    public static int idFor(ServerLevel level, BlockPos pos) {
        return idFor(GlobalPos.of(level.dimension(), pos.immutable()));
    }

    public static int idFor(GlobalPos at) {
        Integer known = IDS.get(at);
        if (known != null) {
            return known;
        }
        int id = next--;
        IDS.put(at, id);
        BY_ID.put(id, at);
        return id;
    }

    /** @return where the camera behind this feed id is, or null if the id was never allocated here. */
    @Nullable
    public static GlobalPos posFor(int feedId) {
        return BY_ID.get(feedId);
    }

    /** @return true if this id belongs to a fixed camera rather than a drone. */
    public static boolean isFixed(int feedId) {
        return feedId < 0;
    }

    public static int allocated() {
        return IDS.size();
    }

    public static void shutdown() {
        IDS.clear();
        BY_ID.clear();
        DEAD.clear();
        next = -1;
    }
}
