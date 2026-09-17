package com.wf.wfballistics.drone.cam;

import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.item.ModDataComponents;
import com.wf.wfballistics.network.CameraFeedPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every live camera feed on the server, and the audience for each one. */
public final class CameraNet {

    /** How often a feed is published, in ticks. Five a second is smooth for a gimbal and cheap on the wire. */
    public static final int PUBLISH_INTERVAL = 4;
    /** How close a player must be to a bound monitor to be counted as watching it. */
    public static final double MONITOR_AUDIENCE = 24.0;
    /** Ticks an explicit (screen-open) subscription survives without being renewed by the client. */
    private static final int SUBSCRIPTION_TTL = 60;
    /** How far back {@link #quietFor} can see. */
    private static final int QUIET_HORIZON = 6000;

    private static final Map<ResourceKey<Level>, Cameras> BY_LEVEL = new HashMap<>();
    private static long encodes;
    private static long deliveries;

    private CameraNet() {
    }

    private static Cameras of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new Cameras());
    }

    // --- monitors -------------------------------------------------------------------------------------

    /** A monitor block claims a feed. */
    public static void registerMonitor(ServerLevel level, BlockPos pos, int feedId, int[] feedIds) {
        of(level).monitors.put(pos.asLong(), new Monitor(feedId, feedIds, level.getGameTime()));
    }

    public static void unregisterMonitor(ServerLevel level, BlockPos pos) {
        of(level).monitors.remove(pos.asLong());
    }

    // --- subscriptions --------------------------------------------------------------------------------

    /** A player with a feed screen open. */
    public static void subscribe(ServerPlayer player, int feedId) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (feedId == 0 || !entitled(level, player, feedId)) {
            return;
        }
        of(level).watching.put(player.getUUID(), new Watch(feedId, level.getGameTime()));
    }

    /** Is this player allowed to watch this feed at all? */
    private static boolean entitled(ServerLevel level, ServerPlayer player, int feedId) {
        Cameras cameras = of(level);
        double reach = MONITOR_AUDIENCE * MONITOR_AUDIENCE;
        for (Map.Entry<Long, Monitor> entry : cameras.monitors.entrySet()) {
            if (!entry.getValue().shows(feedId)) {
                continue;
            }
            if (player.distanceToSqr(Vec3.atCenterOf(BlockPos.of(entry.getKey()))) <= reach) {
                return true;
            }
        }
        return holdsPanelWith(level, player, feedId);
    }

    private static boolean holdsPanelWith(ServerLevel level, ServerPlayer player, int feedId) {
        for (InteractionHand hand : InteractionHand.values()) {
            CameraChannels channels = player.getItemInHand(hand)
                    .getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
            for (FeedTarget target : channels.targets()) {
                if (target.resolve(level) == feedId) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How long since anybody last had this feed on screen.
     *
     * @return ticks of quiet, or {@link Long#MAX_VALUE} if nobody has watched this feed within
     *      {@link #QUIET_HORIZON}.
     */
    public static long quietFor(ServerLevel level, int feedId) {
        Cameras cameras = BY_LEVEL.get(level.dimension());
        if (cameras == null) {
            return Long.MAX_VALUE;
        }
        Long at = cameras.lastWatched.get(feedId);
        return at == null ? Long.MAX_VALUE : level.getGameTime() - at;
    }

    /** Count a feed as watched now, before any audience has formed around it. */
    public static void markWatched(ServerLevel level, int feedId) {
        if (feedId != 0) {
            of(level).lastWatched.put(feedId, level.getGameTime());
        }
    }

    public static void unsubscribe(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            of(level).watching.remove(player.getUUID());
        }
    }

    /** A pointing order. */
    public static void aim(ServerPlayer player, int feedId, float yaw, float pitch, float zoom, int mode) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Cameras cameras = of(level);
        Cam cam = cameras.cams.get(feedId);
        if (cam == null || !cameras.watches(level, feedId, player)) {
            return;
        }
        cam.desiredYaw = cam.clampYaw(yaw);
        cam.desiredPitch = Math.max(cam.spec.pitchMin(), Math.min(cam.spec.pitchMax(), pitch));
        cam.zoom = Math.max(1.0f, Math.min(cam.spec.maxZoom(), zoom));
        CameraMode wanted = CameraMode.byOrdinal(mode);
        if (cam.spec.fitted(wanted)) {
            cam.mode = wanted;
        }
    }

    // --- tick -----------------------------------------------------------------------------------------

    public static void tick(ServerLevel level) {
        Cameras cameras = BY_LEVEL.get(level.dimension());
        if (cameras == null) {
            return;
        }
        long now = level.getGameTime();
        cameras.expire(now);
        if (now % PUBLISH_INTERVAL != 0) {
            return;
        }

        Map<Integer, List<Anchor>> audiences = cameras.audiences(level);
        if (audiences.isEmpty()) {
            cameras.cams.clear();
            CameraChunkStream.update(level, Map.of());
            return;
        }

        cameras.cams.keySet().removeIf(id -> !audiences.containsKey(id));

        for (Map.Entry<Integer, List<Anchor>> entry : audiences.entrySet()) {
            publish(level, cameras, entry.getKey(), entry.getValue(), now);
        }

        CameraChunkStream.update(level, watchers(audiences));
    }

    /**
     * @return the audience map with the anchor positions dropped. The streamer cares who is watching, not
     *      where they are standing: a chunk is either on their connection or it is not.
     */
    private static Map<Integer, List<ServerPlayer>> watchers(Map<Integer, List<Anchor>> audiences) {
        Map<Integer, List<ServerPlayer>> out = new HashMap<>(audiences.size());
        for (Map.Entry<Integer, List<Anchor>> entry : audiences.entrySet()) {
            List<ServerPlayer> players = new ArrayList<>(entry.getValue().size());
            for (Anchor anchor : entry.getValue()) {
                players.add(anchor.player());
            }
            out.put(entry.getKey(), players);
        }
        return out;
    }

    private static void publish(ServerLevel level, Cameras cameras, int feedId, List<Anchor> audience,
                                long now) {
        CameraSource source = CameraSource.resolve(level, feedId);
        if (source == null) {
            cameras.cams.remove(feedId);
            return;
        }
        CameraSpec spec = source.spec();

        Cam cam = cameras.cams.computeIfAbsent(feedId,
                id -> new Cam(spec, source.restYaw(), source.restPitch()));
        cam.spec = spec;
        cam.restYaw = wrap(source.restYaw());
        if (!spec.fitted(cam.mode)) {
            cam.mode = CameraMode.OPTICAL;
        }
        cam.slew(PUBLISH_INTERVAL);

        Vec3 eye = source.eye();
        double nearest = Double.MAX_VALUE;
        for (Anchor anchor : audience) {
            nearest = Math.min(nearest, anchor.pos().distanceTo(eye));
        }
        float link = spec.linkAt(nearest);

        source.bill(spec.drawFor(cam.mode), PUBLISH_INTERVAL);

        CameraFeed feed = new CameraFeed(feedId, eye.x, eye.y, eye.z,
                cam.yaw, cam.pitch, spec.fovAt(cam.zoom), spec.fovDeg(), spec.maxZoom(), spec.slewRate(),
                (byte) cam.mode.ordinal(), (byte) spec.modeMask(),
                source.battery(), link,
                source.speed(),
                (float) (eye.y - level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        (int) Math.floor(eye.x), (int) Math.floor(eye.z))),
                now);

        // Encoded once, handed to everybody. This is the line the class exists for.
        CameraFeedPacket packet = CameraFeedPacket.of(feed);
        encodes++;
        for (Anchor anchor : audience) {
            PacketDistributor.sendToPlayer(anchor.player(), packet);
            deliveries++;
        }
    }

    // --- diagnostics ----------------------------------------------------------------------------------

    public static long encodeCount() {
        return encodes;
    }

    /**
     * @return how many observers have been handed a payload. Against {@link #encodeCount()} this is the
     *      whole multi-observer claim: the gap between the two is the number of serialisations that did not
     *      happen.
     */
    public static long deliveryCount() {
        return deliveries;
    }

    public static int feedCount(ServerLevel level) {
        Cameras cameras = BY_LEVEL.get(level.dimension());
        return cameras == null ? 0 : cameras.cams.size();
    }

    public static int monitorCount(ServerLevel level) {
        Cameras cameras = BY_LEVEL.get(level.dimension());
        return cameras == null ? 0 : cameras.monitors.size();
    }

    public static void resetCounters() {
        encodes = 0;
        deliveries = 0;
        CameraChunkStream.resetCounters();
    }

    public static void shutdown() {
        BY_LEVEL.clear();
        CameraChunkStream.shutdown();
        StaticCameraFeeds.shutdown();
        resetCounters();
    }

    private static float wrap(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    // --- state ----------------------------------------------------------------------------------------

    /** One observer, and where they are watching from. */
    private record Anchor(ServerPlayer player, Vec3 pos) {
    }

    /**
     * @param feedId the channel this monitor is showing, which is what passers-by see
     * @param feedIds every channel bound to it, which is what its operator may switch to
     */
    private record Monitor(int feedId, int[] feedIds, long renewed) {

        boolean shows(int id) {
            if (id == this.feedId) {
                return true;
            }
            for (int candidate : this.feedIds) {
                if (candidate == id) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Watch(int feedId, long renewed) {
    }

    private static final class Cam {
        private CameraSpec spec;
        private CameraMode mode = CameraMode.OPTICAL;
        private float yaw;
        private float pitch;
        private float desiredYaw;
        private float desiredPitch;
        private float zoom = 1.0f;
        /** The heading of the mount. The centre of the arc, for a gimbal that has one. */
        private float restYaw;

        Cam(CameraSpec spec, float restYaw, float restPitch) {
            this.spec = spec;
            this.restYaw = wrap(restYaw);
            this.yaw = this.restYaw;
            this.desiredYaw = this.yaw;
            this.pitch = restPitch;
            this.desiredPitch = restPitch;
        }

        /**
         * @return the closest heading to {@code wanted} this gimbal can actually reach. A free gimbal reaches
         *      all of them; one on a bracket is held to {@link CameraSpec#yawRange()} either side of its mount.
         */
        float clampYaw(float wanted) {
            if (this.spec.freeYaw()) {
                return wrap(wanted);
            }
            float range = this.spec.yawRange();
            float offset = wrap(wanted - this.restYaw);
            return wrap(this.restYaw + Math.max(-range, Math.min(range, offset)));
        }

        /** Move toward the commanded angle at the gimbal's rate. */
        void slew(int ticks) {
            float rate = this.spec.slewRate();
            if (this.spec.freeYaw()) {
                this.yaw = wrap(this.yaw
                        + GimbalMath.approach(wrap(this.desiredYaw - this.yaw), ticks, rate));
            } else {
                float from = wrap(this.yaw - this.restYaw);
                float to = wrap(this.desiredYaw - this.restYaw);
                this.yaw = wrap(this.restYaw + from + GimbalMath.approach(to - from, ticks, rate));
            }
            this.pitch += GimbalMath.approach(this.desiredPitch - this.pitch, ticks, rate);
        }
    }

    private static final class Cameras {
        private final Map<Integer, Cam> cams = new HashMap<>();
        private final Map<Long, Monitor> monitors = new HashMap<>();
        private final Map<UUID, Watch> watching = new HashMap<>();
        /** Feed id to the last game time it had anybody on it. Read by the off-world sim, never by publish. */
        private final Map<Integer, Long> lastWatched = new HashMap<>();
        /** The audience map, memoised for the tick it was built on. */
        private Map<Integer, List<Anchor>> cachedAudiences;
        private long cachedAt = Long.MIN_VALUE;

        void expire(long now) {
            this.cachedAudiences = null;
            for (Iterator<Map.Entry<Long, Monitor>> it = monitors.entrySet().iterator(); it.hasNext(); ) {
                if (now - it.next().getValue().renewed() > SUBSCRIPTION_TTL) {
                    it.remove();
                }
            }
            watching.entrySet().removeIf(e -> now - e.getValue().renewed() > SUBSCRIPTION_TTL);
            lastWatched.values().removeIf(at -> now - at > QUIET_HORIZON);
        }

        boolean watches(ServerLevel level, int feedId, ServerPlayer player) {
            List<Anchor> anchors = audiences(level).get(feedId);
            if (anchors == null) {
                return false;
            }
            for (Anchor anchor : anchors) {
                if (anchor.player() == player) {
                    return true;
                }
            }
            return false;
        }

        /** Feed id to the observers of it. */
        Map<Integer, List<Anchor>> audiences(ServerLevel level) {
            long now = level.getGameTime();
            if (this.cachedAudiences != null && this.cachedAt == now) {
                return this.cachedAudiences;
            }
            Map<Integer, List<Anchor>> out = build(level);
            for (Integer feedId : out.keySet()) {
                this.lastWatched.put(feedId, now);
            }
            this.cachedAudiences = out;
            this.cachedAt = now;
            return out;
        }

        private Map<Integer, List<Anchor>> build(ServerLevel level) {
            Map<Integer, List<Anchor>> out = new HashMap<>();
            for (Map.Entry<UUID, Watch> entry : watching.entrySet()) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
                if (player != null && player.level() == level) {
                    out.computeIfAbsent(entry.getValue().feedId(), id -> new ArrayList<>())
                            .add(new Anchor(player, player.getEyePosition()));
                }
            }
            if (monitors.isEmpty()) {
                return out;
            }
            for (Map.Entry<Long, Monitor> entry : monitors.entrySet()) {
                int feedId = entry.getValue().feedId();
                if (feedId == 0) {
                    continue;
                }
                Vec3 at = Vec3.atCenterOf(BlockPos.of(entry.getKey()));
                for (ServerPlayer player : level.players()) {
                    if (player.distanceToSqr(at) > MONITOR_AUDIENCE * MONITOR_AUDIENCE) {
                        continue;
                    }
                    List<Anchor> anchors = out.computeIfAbsent(feedId, id -> new ArrayList<>());
                    if (anchors.stream().noneMatch(a -> a.player() == player)) {
                        anchors.add(new Anchor(player, at));
                    }
                }
            }
            return out;
        }
    }

    /**
     * @return the camera fitted to this drone, or null if it carries none. Kept here so callers outside the
     *      drone package do not need to know how the fit is stored.
     */
    @Nullable
    public static CameraSpec specOf(Entity entity) {
        return entity instanceof DroneEntity drone ? drone.cameraSpec() : null;
    }
}
