package com.wf.wfballistics.recon.env;

import com.wf.wfballistics.drone.WorldThread;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Where an {@link Atmosphere} comes from. */
public final class ReconWeather {

    /** Ticks in a Minecraft day, and the period of {@link Atmosphere#solar()}. */
    private static final double DAY_TICKS = 24000.0;

    private static final Map<ResourceKey<Level>, Sample> BY_LEVEL = new HashMap<>();

    private ReconWeather() {
    }

    /**
     * @return the conditions in this dimension right now, with no correction applied. Callers that know which
     *      network is asking finish the job with {@link Atmosphere#withCoverage(double)}.
     */
    public static Atmosphere sample(ServerLevel level) {
        WorldThread.assertOn("recon weather sampling");
        long now = level.getGameTime();
        Sample cached = BY_LEVEL.get(level.dimension());
        if (cached != null && cached.stamp == now) {
            return cached.atmos;
        }
        Atmosphere built = build(level);
        BY_LEVEL.put(level.dimension(), new Sample(built, now));
        return built;
    }

    private static Atmosphere build(ServerLevel level) {
        double solar = 0.0;
        if (level.dimensionType().hasSkyLight()) {
            long day = Math.floorMod(level.getDayTime(), (long) DAY_TICKS);
            solar = Math.sin(2.0 * Math.PI * day / DAY_TICKS);
        }
        return new Atmosphere(solar, level.getRainLevel(1.0f), level.getThunderLevel(1.0f), 0.0);
    }

    /** How long the current weather has left, in ticks, or {@code -1} if this dimension does not track it. */
    public static int weatherTime(ServerLevel level) {
        if (!(level.getLevelData() instanceof ServerLevelData data)) {
            return -1;
        }
        if (data.getClearWeatherTime() > 0) {
            return data.getClearWeatherTime();
        }
        int rain = data.getRainTime();
        int thunder = data.getThunderTime();
        if (level.isThundering()) {
            return Math.min(rain, thunder);
        }
        return level.isRaining() ? rain : Math.min(rain, thunder);
    }

    /**
     * @return the forecast as one phrase, e.g. {@code "raining, clearing in ~3m 20s"}.
     */
    public static String forecast(ServerLevel level, Atmosphere atmos) {
        int ticks = weatherTime(level);
        String now = atmos.conditions();
        if (ticks < 0) {
            return now + ", no forecast in this dimension";
        }
        String next = level.isRaining() || level.isThundering() ? "clearing" : "weather";
        return String.format(Locale.ROOT, "%s, %s in ~%s", now, next, duration(ticks));
    }

    /**
     * @return ticks as {@code 4m 12s}, because a forecast in ticks is not a forecast.
     */
    public static String duration(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    /**
     * @return where the clock is, for a readout.
     */
    public static String daypart(ServerLevel level, Atmosphere atmos) {
        return level.dimensionType().hasSkyLight() ? atmos.daypart() : "no sky";
    }

    public static void clear() {
        BY_LEVEL.clear();
    }

    private record Sample(Atmosphere atmos, long stamp) {
    }
}
