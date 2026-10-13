package com.wf.wflib.orbital;

import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.drone.cam.CameraSource;
import com.wf.wflib.drone.cam.CameraSpec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * O8 spot feed: a live straight-down orthographic camera over {@code (x, z)}, from an own-net IMAGERY bird, while its
 * footprint covers the point and it is in contact; else the source is gone and the feed ends. Feed ids
 * {@code <= BASE} (fixed cameras count down from -1). One spot per player; watchable only by them.
 */
public final class SpotFeeds {
    public static final int BASE = -1_000_000_000;
    /** Lens height over the ground at the point; capped under the build limit (section graph seeds from the camera). */
    static final double LENS_HEIGHT = 300;
    /** Ortho half-height at zoom 1, blocks; x 16:9 => half-width 128 = streamed bubble (8 chunks). 96 => blank edges. */
    public static final CameraSpec SPEC = new CameraSpec(72.0f, 4.0f, 90.0f, 90.0f, 0.0f, 30.0f, 1.0e9,
            1, 0);

    private record Spot(ResourceKey<Level> dimension, SatId sat, double x, double z, UUID viewer) {
    }

    private static final Map<Integer, Spot> SPOTS = new HashMap<>();
    private static final Map<UUID, Integer> BY_VIEWER = new HashMap<>();
    private static int next = BASE;

    private SpotFeeds() {
    }

    public static boolean isSpot(int feedId) {
        return feedId <= BASE;
    }

    /** Replaces the viewer's previous spot. @return the feed id */
    public static int open(ServerPlayer viewer, SatId sat, double x, double z) {
        close(viewer.getUUID());
        int id = next--;
        SPOTS.put(id, new Spot(viewer.level().dimension(), sat, x, z, viewer.getUUID()));
        BY_VIEWER.put(viewer.getUUID(), id);
        return id;
    }

    public static void close(UUID viewer) {
        Integer old = BY_VIEWER.remove(viewer);
        if (old != null) {
            SPOTS.remove(old);
        }
    }

    public static boolean entitled(ServerPlayer player, int feedId) {
        Spot spot = SPOTS.get(feedId);
        return spot != null && spot.viewer.equals(player.getUUID());
    }

    /** Streamed-chunk window centre: the ground point. */
    @Nullable
    public static Vec3 target(ServerLevel level, int feedId) {
        Spot spot = SPOTS.get(feedId);
        return spot == null || spot.dimension != level.dimension() ? null : new Vec3(spot.x, 0, spot.z);
    }

    /** Null once the pass is over, contact lost or the bird gone. */
    @Nullable
    public static CameraSource source(ServerLevel level, int feedId) {
        Spot spot = SPOTS.get(feedId);
        if (spot == null || spot.dimension != level.dimension()) {
            return null;
        }
        SatView view = OrbitalNet.view(level, spot.sat).orElse(null);
        if (view == null || !view.inContact() || OrbitalNet.nextPass(level, spot.sat, spot.x, spot.z, 0, 1) != 0) {
            return null;
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(spot.x),
                (int) Math.floor(spot.z));
        double y = Math.min(ground + LENS_HEIGHT, level.getMaxBuildHeight() - 2);
        return new Source(new Vec3(spot.x, y, spot.z), (float) view.powerFraction());
    }

    /** North up: yaw 180, straight down. */
    private record Source(Vec3 eye, float battery) implements CameraSource {
        @Override
        public CameraSpec spec() {
            return SPEC;
        }

        @Override
        public float restYaw() {
            return 180.0f;
        }

        @Override
        public float restPitch() {
            return 90.0f;
        }

        @Override
        public float speed() {
            return 0.0f;
        }

        @Override
        public void bill(int fe, int ticks) {
        }

        @Override
        public byte kind() {
            return CameraFeed.ORBITAL;
        }
    }
}
