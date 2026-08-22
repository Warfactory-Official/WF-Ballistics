package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.ModEntities;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * The castes a colony can field, and when it learns to.
 *
 * <p>Exists because three separate things need to name a caste and none of them should hold a list of entity
 * types: the materialiser turning a warband record into bugs, the bench spawning a test population, and
 * {@link com.wf.wfballistics.colony.Evolution} deciding what a colony has earned the right to send.
 *
 * <p>{@link #minEvolution} is the gate and {@link #baseWeight} is the shape. A caste is unavailable below its
 * gate, appears at {@link #UNLOCK_SHARE} of its weight the moment it clears one, and reaches full weight only
 * at full evolution — so unlocking behemoths changes the swarm's flavour before it changes its weight class.
 * Grunts are the exception: their weight is flat, which means they stay the bulk of every warband while their
 * <em>share</em> falls as the other castes ramp up.
 */
public enum GlyphidCaste {

    /**
     * The baseline bug. Always available, always the majority.
     */
    GRUNT(0.00F, 10.0F, () -> ModEntities.GLYPHID.get()),
    /**
     * Founds nests. Available from the start because a colony that cannot expand is not a colony, and rare
     * because one scout is worth a dozen grunts to the simulation.
     */
    SCOUT(0.00F, 1.0F, () -> ModEntities.GLYPHID_SCOUT.get()),
    BOMBARDIER(0.10F, 4.0F, () -> ModEntities.GLYPHID_BOMBARDIER.get()),
    BRAWLER(0.25F, 4.0F, () -> ModEntities.GLYPHID_BRAWLER.get()),
    DIGGER(0.35F, 3.0F, () -> ModEntities.GLYPHID_DIGGER.get()),
    BLASTER(0.50F, 2.0F, () -> ModEntities.GLYPHID_BLASTER.get()),
    BEHEMOTH(0.65F, 2.0F, () -> ModEntities.GLYPHID_BEHEMOTH.get()),
    NUCLEAR(0.80F, 0.5F, () -> ModEntities.GLYPHID_NUCLEAR.get()),
    BRENDA(0.90F, 0.5F, () -> ModEntities.GLYPHID_BRENDA.get());

    /**
     * Share of its full weight a caste carries the instant it unlocks.
     *
     * <p>Not zero: ramping from nothing means a caste gated at 0 — the scout — never appears in a fresh
     * world, and a colony that cannot field scouts cannot expand, which stalls the whole simulation before
     * it starts.
     */
    private static final float UNLOCK_SHARE = 0.25F;

    private final float minEvolution;
    private final float baseWeight;
    private final Supplier<EntityType<? extends EntityGlyphid>> type;

    GlyphidCaste(float minEvolution, float baseWeight, Supplier<EntityType<? extends EntityGlyphid>> type) {
        this.minEvolution = minEvolution;
        this.baseWeight = baseWeight;
        this.type = type;
    }

    public EntityType<? extends EntityGlyphid> type() {
        return type.get();
    }

    public float minEvolution() {
        return minEvolution;
    }

    public boolean unlockedAt(float evolution) {
        return evolution >= minEvolution;
    }

    /**
     * @return how often this caste should turn up in a warband at the given evolution, unnormalised
     */
    public float weightAt(float evolution) {
        if (evolution < minEvolution) {
            return 0.0F;
        }
        if (this == GRUNT) {
            return baseWeight;
        }
        float span = Math.max(1.0E-4F, 1.0F - minEvolution);
        float ramp = Math.min(1.0F, (evolution - minEvolution) / span);
        return baseWeight * (UNLOCK_SHARE + (1.0F - UNLOCK_SHARE) * ramp);
    }

    /**
     * Pick a caste for one bug. Falls back to {@link #GRUNT}, which is the only caste that can never be
     * gated out.
     */
    public static GlyphidCaste roll(RandomSource random, float evolution) {
        float total = 0.0F;
        for (GlyphidCaste caste : VALUES) {
            total += caste.weightAt(evolution);
        }
        if (total <= 0.0F) {
            return GRUNT;
        }

        float pick = random.nextFloat() * total;
        for (GlyphidCaste caste : VALUES) {
            pick -= caste.weightAt(evolution);
            if (pick <= 0.0F) {
                return caste;
            }
        }
        return GRUNT;
    }

    public static @Nullable GlyphidCaste byName(String name) {
        for (GlyphidCaste caste : VALUES) {
            if (caste.name().equalsIgnoreCase(name)) {
                return caste;
            }
        }
        return null;
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Cached because {@link #roll} walks it twice per bug and a warband materialises hundreds at once.
     */
    public static final GlyphidCaste[] VALUES = values();
}
