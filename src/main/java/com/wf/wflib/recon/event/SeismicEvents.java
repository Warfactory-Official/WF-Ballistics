package com.wf.wflib.recon.event;

import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.recon.propagate.SeismicPropagator;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Where explosions are recorded so the seismic band can hear them. */
public final class SeismicEvents {

    /**
     * The reference charge: vanilla TNT, whose explosion power is 4.
     */
    public static final float TNT_POWER = 4.0f;
    /** Seismic energy one surface-laid TNT couples into the ground. */
    public static final float TNT_ENERGY = 0.5f;
    /** How energy scales with blast size. */
    public static final double YIELD_EXPONENT = 1.5;
    /** Ticks an arrival stays readable on a station's trace. */
    public static final int LINGER_TICKS = 80;
    /** Blasts that may be ringing at once in one dimension. */
    public static final int MAX_LIVE = 64;
    /** Blocks past which a station does not even do the arithmetic. */
    public static final double MAX_REACH = 4096.0;

    /** Depth at which burial stops helping, and what it is worth. */
    public static final double COUPLING_DEPTH = 16.0;
    /**
     * What a charge standing in water couples into the water. Water is incompressible, so an underwater
     * charge puts several times a buried one's energy into its own medium, and it is the loudest thing a
     * hydrophone will ever hear.
     */
    public static final double WATER_COUPLING = 4.0;
    public static final double BURIED_GAIN = 1.0;
    /** Blocks of air over which coupling fades to nothing. */
    public static final double AIR_FADE = 16.0;

    private static final Map<ResourceKey<Level>, List<BlastEvent>> BY_LEVEL = new HashMap<>();
    private static long nextId = 1L;

    private SeismicEvents() {
    }

    /**
     * Record an explosion.
     *
     * @param power the blast's size in the units {@code ExplosionAEF} and vanilla both use, where TNT is 4.
     */
    public static void report(ServerLevel level, double x, double y, double z, float power) {
        WorldThread.assertOn("seismic event report");
        if (!(power > 0.0f)) {
            return;
        }
        double coupling = couplingAt(level, x, y, z);
        double water = waterCouplingAt(level, x, y, z);
        if (coupling <= 0.0 && water <= 0.0) {
            return;
        }
        List<BlastEvent> live = BY_LEVEL.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
        long gameTime = level.getGameTime();
        prune(live, gameTime);

        BlockPos at = BlockPos.containing(x, y, z);
        for (int i = 0; i < live.size(); i++) {
            BlastEvent existing = live.get(i);
            if (existing.gameTime() == gameTime && BlockPos.containing(
                    existing.x(), existing.y(), existing.z()).equals(at)) {
                if (power > existing.power()) {
                    live.set(i, rebuild(existing, x, y, z, power, coupling, water));
                }
                return;
            }
        }

        float yield = (float) (TNT_ENERGY * Math.pow(power / TNT_POWER, YIELD_EXPONENT));
        live.add(new BlastEvent(nextId++, x, y, z, (float) (yield * coupling), (float) (yield * water),
                power, coupling > 1.0, gameTime));
        if (live.size() > MAX_LIVE) {
            dropWeakest(live);
        }
    }

    /**
     * @return every blast in this dimension still readable, newest last. Cheap: the list is pruned as it is
     *      read and is empty on a world where nothing has exploded in four seconds.
     */
    public static List<BlastEvent> live(ServerLevel level, long gameTime) {
        List<BlastEvent> live = BY_LEVEL.get(level.dimension());
        if (live == null || live.isEmpty()) {
            return List.of();
        }
        prune(live, gameTime);
        return live.isEmpty() ? List.of() : List.copyOf(live);
    }

    /**
     * @return the range at which a geophone of this {@code baseRange} would hear that energy through neutral
     *      ground and calm weather. The headline number for a diagnostic, and an upper bound: real ground absorbs
     *      and real weather raises the floor, so a station never hears further than this.
     */
    public static double reachOf(double energy, double baseRange) {
        return Math.min(MAX_REACH, energy * baseRange);
    }

    /**
     * @return the blast power that would couple this much energy into level ground. The inverse of the yield
     *      curve, and what turns a network's energy estimate back into a number a player recognises.
     */
    public static double powerOf(double energy) {
        return energy <= 0.0 ? 0.0
                : TNT_POWER * Math.pow(energy / TNT_ENERGY, 1.0 / YIELD_EXPONENT);
    }

    /** The same inverse for what a hydrophone heard, which arrived through a different coupling. */
    public static double powerOfWater(double energy) {
        return powerOf(energy / WATER_COUPLING);
    }

    public static void shutdown() {
        BY_LEVEL.clear();
    }

    /**
     * How much of a charge at this point reaches rock, as a multiplier on its energy.
     *
     * <p>Depth is measured against the lower of the two heightmaps, which is the one line here that matters:
     * {@code MOTION_BLOCKING_NO_LEAVES} counts water as surface, so on its own it reads a torpedo detonating
     * twenty blocks down as a camouflet, and {@code OCEAN_FLOOR} counts a leaf canopy as ground. The minimum
     * is the seabed under water and the real ground under a tree.
     */
    private static double couplingAt(ServerLevel level, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        double ground = Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz),
                level.getHeight(Heightmap.Types.OCEAN_FLOOR, bx, bz));
        double depth = ground - y;
        if (depth >= 0.0) {
            return 1.0 + BURIED_GAIN * Math.min(depth, COUPLING_DEPTH) / COUPLING_DEPTH;
        }
        return Math.max(0.0, 1.0 + depth / AIR_FADE);
    }

    /** How much of a charge at this point reaches the water. All of it, or none: it is in it or it is not. */
    private static double waterCouplingAt(ServerLevel level, double x, double y, double z) {
        return level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER) ? WATER_COUPLING : 0.0;
    }

    private static BlastEvent rebuild(BlastEvent existing, double x, double y, double z,
                                      float power, double coupling, double water) {
        float yield = (float) (TNT_ENERGY * Math.pow(power / TNT_POWER, YIELD_EXPONENT));
        return new BlastEvent(existing.id(), x, y, z, (float) (yield * coupling), (float) (yield * water),
                power, coupling > 1.0, existing.gameTime());
    }

    private static void prune(List<BlastEvent> live, long gameTime) {
        live.removeIf(blast -> !blast.readable(gameTime));
    }

    private static void dropWeakest(List<BlastEvent> live) {
        int weakest = 0;
        for (int i = 1; i < live.size(); i++) {
            if (live.get(i).energy() < live.get(weakest).energy()) {
                weakest = i;
            }
        }
        live.remove(weakest);
    }
}
