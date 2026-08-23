package com.wf.wfballistics.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * A nest, as data. Exists and simulates whether or not its chunk is loaded — this is the tier that makes
 * the world feel inhabited rather than the world feeling empty until you walk into it.
 *
 * <p>Strength comes from distance to world spawn. Near the start everything is small and slow; far out
 * colonies are larger, grow faster and muster bigger warbands, so pushing outward is a decision with a
 * price rather than just more of the same.
 *
 * <p>Two numbers drive behaviour, and keeping them separate is what produces the build-then-strike rhythm
 * rather than a constant trickle:
 * <ul>
 *   <li>{@link #population} — grows on its own up to a cap. What an attack is paid for out of.</li>
 *   <li>{@link #aggression} — accumulated from nearby industry. What decides <em>whether</em> to attack.</li>
 * </ul>
 * A colony with numbers and no provocation sits there; one with provocation and no numbers keeps building
 * until it can afford to act.
 *
 * <p>{@link #y} is {@link #Y_UNRESOLVED} until the nest first materialises. A colony founded in an unloaded
 * chunk cannot know how high the ground is there, so it does not guess: the surface is sampled when the
 * chunk finally loads and the nest is built.
 */
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
    /**
     * Whether the nest's blocks have been placed in the world yet.
     */
    public boolean built;
    /**
     * Egg chambers still standing in the mound, and so the colony's life.
     *
     * <p>Set by {@link GlyphidNest} from what it actually laid out, and decremented as a player digs them
     * out; at zero the colony is removed. This is the only way the block layer can act on the record, and it
     * is what stops a razed nest from carrying on growing and mustering out of a mound that is no longer
     * there. Zero on a colony that has never been built, which is why breaking a chamber checks it.
     */
    public int spawners;
    /**
     * Defenders this colony currently has standing in the world, as bodies rather than as numbers.
     *
     * <p>This is the anti-double-count, and it is why {@link #population} is not simply spent and forgotten
     * when a chamber hatches. A garrison bug <em>is</em> part of the colony; it has only changed form. So the
     * population it came out of may not regrow while it is alive — {@link #room()} holds growth back by
     * exactly this many — and the colony is no stronger for having embodied it. Kill the garrison and the
     * colony recovers; walk away and it does not.
     *
     * <p>Recounted from the world every time the nest is loaded and ticking rather than kept as a running
     * tally, so it cannot drift. Nothing changes it while the nest is unloaded, because nothing there can
     * die.
     */
    public int garrison;

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
     * radius where none may exist.
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
     * @return how much abstract population this colony is allowed, which is its cap less the bodies it
     * already has standing. Growth stops here rather than at the cap, so a nest cannot hold a full garrison
     * <em>and</em> a full population — that would be the same bugs counted twice.
     */
    public double room() {
        return Math.max(0.0, populationCap() - garrison);
    }

    /**
     * @return the most defenders this nest will hold, which is what its chambers can staff. A garrison is
     * retained rather than allowed to despawn, so without a ceiling a nest visited often enough would grow
     * one without bound.
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
     * @return how wide the nest's blocks sprawl once built.
     */
    public int nestRadius() {
        return 2 + tier;
    }

    public boolean canStrike() {
        return aggression >= ColonyConfig.strikeThreshold() && population >= warbandSize();
    }

    public boolean canExpand() {
        return expansionCooldown <= 0 && population >= ColonyConfig.expansionPopulation();
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
        return tag;
    }

    public static Colony load(CompoundTag tag) {
        Colony colony = new Colony(tag.getUUID("id"), tag.getInt("x"), tag.getInt("y"), tag.getInt("z"),
                tag.getInt("tier"));
        colony.population = tag.getDouble("pop");
        colony.aggression = tag.getDouble("aggro");
        colony.expansionCooldown = tag.getInt("cooldown");
        colony.built = tag.getBoolean("built");
        // Absent on worlds saved before the hive port, which reads back as zero -- no chambers to lose,
        // which is the right answer for a colony that never had any blocks.
        colony.spawners = tag.getInt("spawners");
        colony.garrison = tag.getInt("garrison");
        return colony;
    }

    @Override
    public String toString() {
        return String.format("colony T%d at (%d, %d) pop %.1f/%d aggro %.0f%s",
                tier, x, z, population, populationCap(), aggression,
                built ? " [built, " + spawners + " chambers, " + garrison + "/" + garrisonCap()
                        + " garrison]" : "");
    }
}
