package com.wf.wflib.telemetry.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.map.MapPoint;
import com.wf.wflib.client.map.MapShape;
import com.wf.wflib.client.map.MapStyle;
import com.wf.wflib.client.map.WFMaps;
import com.wf.wflib.network.TelemetryPacket;
import com.wf.wflib.telemetry.TelemetryKind;
import com.wf.wflib.telemetry.TelemetryLink;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tracks heard by this player's PDA, with the trail each has flown; drawn on every map host and the PDA. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class TelemetryClient {

    private static final String LAYER = "wflib:telemetry";
    private static final String GROUP = "WF Telemetry";

    /** Trail vertex spacing, blocks. */
    private static final double TRAIL_STEP = 4.0;
    private static final int TRAIL_POINTS = 512;
    /** Lost track (impact, out of link) stays drawn this long, client ticks. */
    private static final int LOST_TICKS = 600;

    private static final int COLOUR_OWN_MISSILE = 0xFF5A36;
    private static final int COLOUR_ALLY_MISSILE = 0xFFB347;
    private static final int COLOUR_OWN_DRONE = 0x4FC3F7;
    private static final int COLOUR_ALLY_DRONE = 0x9FA8DA;
    private static final int COLOUR_LOST = 0x9AA0A6;

    private static final ResourceLocation ICON_MISSILE =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/map/telemetry_missile.png");
    private static final ResourceLocation ICON_DRONE =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/map/telemetry_drone.png");

    public static final class Track {
        final long id;
        final TelemetryKind kind;
        final ArrayDeque<MapPoint> trail = new ArrayDeque<>();
        String label;
        boolean own;
        double x;
        double y;
        double z;
        float heading;
        long lastHeard;
        boolean lost;

        Track(long id, TelemetryKind kind) {
            this.id = id;
            this.kind = kind;
        }

        /** {@link TelemetryLink#trackId}. */
        public long id() {
            return id;
        }

        public TelemetryKind kind() {
            return kind;
        }

        public String label() {
            return label;
        }

        public boolean own() {
            return own;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        /** Degrees clockwise from north. */
        public float heading() {
            return heading;
        }

        public boolean lost() {
            return lost;
        }
    }

    private static final Map<Long, Track> TRACKS = new LinkedHashMap<>();
    private static final List<Track> LIST = new ArrayList<>();
    private static final List<Track> VIEW = java.util.Collections.unmodifiableList(LIST);
    @Nullable
    private static ResourceKey<Level> dimension;
    private static long clock;

    private TelemetryClient() {
    }

    /** Current tracks, insertion order; same list each call, changes on receipt / expiry. */
    public static List<Track> tracks() {
        return VIEW;
    }

    @Nullable
    public static Track track(long id) {
        return TRACKS.get(id);
    }

    public static void accept(TelemetryPacket pkt) {
        if (!pkt.dimension().equals(dimension)) {
            TRACKS.clear();
            LIST.clear();
            dimension = pkt.dimension();
        }
        Set<Long> heard = new HashSet<>();
        for (TelemetryPacket.Entry e : pkt.entries()) {
            heard.add(e.id());
            Track track = TRACKS.computeIfAbsent(e.id(), id -> new Track(id, e.kind()));
            MapPoint last = track.trail.peekLast();
            if (last == null || Math.hypot(e.x() - last.x(), e.z() - last.z()) >= TRAIL_STEP) {
                track.trail.addLast(new MapPoint(e.x(), e.z()));
                if (track.trail.size() > TRAIL_POINTS) {
                    track.trail.removeFirst();
                }
            }
            track.label = e.label();
            track.own = e.own();
            track.x = e.x();
            track.y = e.y();
            track.z = e.z();
            track.heading = e.heading();
            track.lastHeard = clock;
            track.lost = false;
        }
        for (Map.Entry<Long, Track> entry : TRACKS.entrySet()) {
            if (!heard.contains(entry.getKey())) {
                entry.getValue().lost = true;
            }
        }
        publish();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        clock++;
        if (clock % 20 != 0 || TRACKS.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Iterator<Track> it = TRACKS.values().iterator(); it.hasNext(); ) {
            Track track = it.next();
            if (clock - track.lastHeard > LOST_TICKS) {
                it.remove();
                changed = true;
            } else if (!track.lost && clock - track.lastHeard > 2L * TelemetryLink.PERIOD_TICKS + 20) {
                track.lost = true;
                changed = true;
            }
        }
        if (changed) {
            publish();
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TRACKS.clear();
        LIST.clear();
        dimension = null;
        WFMaps.clear(LAYER);
    }

    private static void publish() {
        LIST.clear();
        LIST.addAll(TRACKS.values());
        if (dimension == null) {
            return;
        }
        List<MapShape> shapes = new ArrayList<>();
        for (Track track : TRACKS.values()) {
            int colour = colour(track);
            String title = String.format(Locale.ROOT, "%s %s%n%.0f %.0f %.0f%s",
                    track.own ? "your" : "allied", track.label.isEmpty()
                            ? track.kind.name().toLowerCase(Locale.ROOT) : track.label,
                    track.x, track.y, track.z, track.lost ? "\nlink lost" : "");
            List<MapPoint> path = new ArrayList<>(track.trail);
            path.add(new MapPoint(track.x, track.z));
            if (path.size() >= 2) {
                shapes.add(new MapShape.Line(path, (int) track.y, 2.0f, MapStyle.fill(colour, 0.75f),
                        MapShape.Look.of(GROUP, 30)));
            }
            shapes.add(new MapShape.Icon(new MapPoint(track.x, track.z), (int) track.y,
                    track.kind == TelemetryKind.MISSILE ? ICON_MISSILE : ICON_DRONE, 32, 16.0f, track.heading,
                    colour, track.lost ? 0.5f : 1.0f,
                    MapShape.Look.of(GROUP, 31).title(title).label(track.label, colour)));
        }
        WFMaps.publish(LAYER, dimension, shapes);
    }

    private static int colour(Track track) {
        if (track.lost) {
            return COLOUR_LOST;
        }
        return switch (track.kind) {
            case MISSILE -> track.own ? COLOUR_OWN_MISSILE : COLOUR_ALLY_MISSILE;
            case DRONE -> track.own ? COLOUR_OWN_DRONE : COLOUR_ALLY_DRONE;
        };
    }
}
