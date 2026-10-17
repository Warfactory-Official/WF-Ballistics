package com.wf.wflib.radio.client;

import com.mojang.logging.LogUtils;
import com.wf.wflib.radio.OggOpus;
import com.wf.wflib.radio.RadioCarrier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stations from {@code assets/<ns>/radio/<station>/**.opus}: id {@code <ns>:<station>}, tracks in path order, looped.
 * Name: lang {@code radio.<ns>.<station>}, else the folder. Unreadable / non-Opus track or folder failing
 * {@link RadioCarrier#validStation} => logged, skipped.
 */
public final class RadioCatalog extends SimplePreparableReloadListener<Map<ResourceLocation, RadioCatalog.Station>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "radio";
    /** Game ticks -> samples at 48 kHz. */
    static final int SAMPLES_PER_TICK = OggOpus.RATE / 20;

    private static volatile Map<ResourceLocation, Station> stations = Map.of();
    private static volatile List<ResourceLocation> order = List.of();
    private static int generation;

    public record Track(ResourceLocation file, long length) {
    }

    /** @param length samples, all tracks */
    public record Station(ResourceLocation id, List<Track> tracks, long length) {
        /** Track index + offset at {@code sample} into the loop. */
        long[] locate(long sample) {
            long at = Math.floorMod(sample, length);
            for (int i = 0; i < tracks.size(); i++) {
                long n = tracks.get(i).length();
                if (at < n) {
                    return new long[]{i, at};
                }
                at -= n;
            }
            return new long[]{0, 0};
        }
    }

    @Override
    protected Map<ResourceLocation, Station> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, List<Track>> found = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        Map<ResourceLocation, Resource> files = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        files.putAll(manager.listResources(ROOT, rl -> rl.getPath().endsWith(".opus")));
        for (Map.Entry<ResourceLocation, Resource> e : files.entrySet()) {
            ResourceLocation file = e.getKey();
            String[] parts = file.getPath().split("/");
            if (parts.length < 3 || !RadioCarrier.validStation(file.getNamespace() + ":" + parts[1])) {
                LOGGER.warn("Radio track {}: not in a station folder radio/<[a-z0-9_-]+>/: skipped", file);
                continue;
            }
            try (InputStream in = e.getValue().open()) {
                long length = OggOpus.parse(in.readAllBytes()).length();
                found.computeIfAbsent(ResourceLocation.fromNamespaceAndPath(file.getNamespace(), parts[1]),
                        k -> new ArrayList<>()).add(new Track(file, length));
            } catch (IOException | RuntimeException ex) {
                LOGGER.error("Radio track {} unreadable: {}", file, ex.toString());
            }
        }
        Map<ResourceLocation, Station> out = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        found.forEach((id, tracks) -> out.put(id, new Station(id, List.copyOf(tracks),
                tracks.stream().mapToLong(Track::length).sum())));
        return out;
    }

    @Override
    protected void apply(Map<ResourceLocation, Station> prepared, ResourceManager manager, ProfilerFiller profiler) {
        stations = Collections.unmodifiableMap(prepared);
        order = List.copyOf(prepared.keySet());
        generation++;
        LOGGER.info("Radio: {} stations", prepared.size());
    }

    @Nullable
    public static Station get(String id) {
        ResourceLocation rl = id.isEmpty() || !RadioCarrier.validStation(id) ? null : ResourceLocation.tryParse(id);
        return rl == null ? null : stations.get(rl);
    }

    /** Station ids, sorted. */
    public static List<ResourceLocation> stations() {
        return order;
    }

    /** Bumped per reload: playing streams hold old track lists. */
    static int generation() {
        return generation;
    }

    /** "" => off. Off-catalogue id => its path (another client's pack). */
    public static Component name(String id) {
        if (id.isEmpty()) {
            return Component.translatable("wflib.radio.off");
        }
        ResourceLocation rl = RadioCarrier.validStation(id) ? ResourceLocation.tryParse(id) : null;
        return rl == null ? Component.literal("?")
                : Component.translatableWithFallback("radio." + rl.getNamespace() + "." + rl.getPath(), rl.getPath());
    }

    /** Next station after {@code current} in catalogue order, off between the last and the first. */
    public static String next(String current, int step) {
        List<ResourceLocation> ids = order;
        int n = ids.size() + 1;
        ResourceLocation rl = current.isEmpty() ? null : ResourceLocation.tryParse(current);
        int at = rl == null ? 0 : ids.indexOf(rl) + 1;
        int next = Math.floorMod(at + step, n);
        return next == 0 ? "" : ids.get(next - 1).toString();
    }
}
