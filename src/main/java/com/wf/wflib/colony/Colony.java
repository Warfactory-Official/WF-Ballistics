package com.wf.wflib.colony;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/** A nest, as data. */
public final class Colony {

    public static final int Y_UNRESOLVED = Integer.MIN_VALUE;

    public final UUID id;
    public final int x;
    public final int z;
    public int y;
    public final int tier;

    public double population;
    public double aggression;
    public int expansionCooldown;
    /** Whether the nest's blocks have been placed in the world yet. */
    public boolean built;
    /** Egg chambers still standing, and so the colony's life: at zero it is removed. */
    public int spawners;
    /** Defenders standing in the world as bodies rather than as numbers. */
    public int garrison;
    /** Extra mounds grown onto this colony. A count, not positions: {@link NestCells} derives the rest. */
    public int buds;
    /** The ground height each budded mound was laid at, in the order they were grown. */
    public final IntArrayList budHeights = new IntArrayList();

    /**
     * @return how many buds have been stamped into the world
     */
    public int builtBuds() {
        return budHeights.size();
    }

    public Colony(UUID id, int x, int y, int z, int tier) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tier = tier;
    }

    // --- distance scaling ---

    /**
     * @return the tier a colony founded at this distance from spawn would have, or -1 inside the safe
     *      radius where none may exist.
     */
    public static int tierAtDistance(double distance) {
        int safe = ColonyConfig.safeRadius();
        if (distance < safe) {
            return -1;
        }
        double ramp = (distance - safe) / (double) (ColonyConfig.fullStrengthDistance() - safe);
        int tier = (int) Math.floor(Math.min(1.0, ramp) * ColonyConfig.maxTier());
        return Math.min(ColonyConfig.maxTier(), Math.max(0, tier));
    }

    public static double distanceFromSpawn(BlockPos spawn, int x, int z) {
        double dx = x - spawn.getX();
        double dz = z - spawn.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // --- derived strength ---

    public int populationCap() {
        return ColonyConfig.basePopulationCap() * (tier + 1);
    }

    /**
     * @return the population cap less the bodies already standing, so a garrison and a population cannot both
     *      be full: that would be the same bugs counted twice.
     */
    public double room() {
        return Math.max(0.0, populationCap() - garrison);
    }

    /**
     * @return the most defenders this nest will hold. Garrison bugs are retained rather than despawned, so
     *      without a ceiling a nest visited often enough would grow one without bound.
     */
    public int garrisonCap() {
        return ColonyConfig.garrisonPerChamber() * spawners;
    }

    public double growthPerSecond() {
        return ColonyConfig.growthPerSecond() * (1.0 + tier * 0.5);
    }

    public int warbandSize() {
        return ColonyConfig.baseWarbandSize() * (tier + 1);
    }

    /**
     * @return how wide one of this colony's mounds is. For the whole cluster see {@link #footprintRadius()}.
     */
    public int nestRadius() {
        return 2 + tier;
    }

    /**
     * @return how far the whole cluster reaches: the outermost cell plus its own mound. What anything asking
     *      "does this colony own that block" wants.
     */
    public int footprintRadius() {
        return nestRadius() + NestCells.extent(this);
    }

    /**
     * @return how many mounds this colony may grow onto itself. Rises with tier, so a frontier hive sprawls
     *      while a starting one stays a single dome.
     */
    public int budCap() {
        return NestCells.cap(tier);
    }

    public boolean canStrike() {
        return aggression >= ColonyConfig.strikeThreshold() && population >= warbandSize();
    }

    /**
     * @return whether the lattice has room for another mound. Says nothing about affording one.
     */
    public boolean canBud() {
        return buds < budCap();
    }

    /** Gated on the cheaper of the two kinds; {@code ColonyManager.expand} picks which one it can pay for. */
    public boolean canExpand() {
        return expansionCooldown <= 0
                && population >= Math.min(ColonyConfig.budPopulation(), ColonyConfig.expansionPopulation());
    }

    public ChunkPos chunk() {
        return new ChunkPos(x >> 4, z >> 4);
    }

    public BlockPos pos() {
        return new BlockPos(x, y == Y_UNRESOLVED ? 0 : y, z);
    }

    public boolean hasResolvedY() {
        return y != Y_UNRESOLVED;
    }

    // --- persistence ---

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        tag.putInt("tier", tier);
        tag.putDouble("pop", population);
        tag.putDouble("aggro", aggression);
        tag.putInt("cooldown", expansionCooldown);
        tag.putBoolean("built", built);
        tag.putInt("spawners", spawners);
        tag.putInt("garrison", garrison);
        tag.putInt("buds", buds);
        tag.putIntArray("budY", budHeights.toIntArray());
        return tag;
    }

    public static Colony load(CompoundTag tag) {
        Colony colony = new Colony(tag.getUUID("id"), tag.getInt("x"), tag.getInt("y"), tag.getInt("z"),
                tag.getInt("tier"));
        colony.population = tag.getDouble("pop");
        colony.aggression = tag.getDouble("aggro");
        colony.expansionCooldown = tag.getInt("cooldown");
        colony.built = tag.getBoolean("built");
        colony.spawners = tag.getInt("spawners");
        colony.garrison = tag.getInt("garrison");
        colony.buds = tag.getInt("buds");
        colony.budHeights.addElements(0, tag.getIntArray("budY"));
        return colony;
    }

    @Override
    public String toString() {
        return String.format("colony T%d at (%d, %d) pop %.1f/%d aggro %.0f%s",
                tier, x, z, population, populationCap(), aggression,
                built ? " [built, " + (buds + 1) + " cells, " + spawners + " chambers, "
                        + garrison + "/" + garrisonCap() + " garrison]" : "");
    }
}
