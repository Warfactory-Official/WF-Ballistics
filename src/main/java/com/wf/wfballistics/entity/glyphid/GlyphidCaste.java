package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The castes a colony can field, and when it learns to. One list, for the materialiser, the bench and
 * {@link com.wf.wfballistics.colony.Evolution}.
 *
 * <p>{@link #minEvolution} gates and {@link #baseWeight} shapes: a caste appears at {@link #UNLOCK_SHARE} of
 * its weight the moment it unlocks and reaches full weight only at full evolution, so unlocking behemoths
 * changes the swarm's flavour before its weight class. Grunt weight is flat, so grunts stay the bulk while
 * their share falls.
 */
public enum GlyphidCaste {

    /** The baseline bug. Always available, always the majority. */
    GRUNT(0.00F, 10.0F, 1.0D, () -> ModEntities.GLYPHID.get()),
    /** Founds nests. Available from the start, and rare: one scout is worth a dozen grunts. */
    SCOUT(0.00F, 1.0F, 0.75D, () -> ModEntities.GLYPHID_SCOUT.get()),
    BOMBARDIER(0.10F, 4.0F, 1.0D, () -> ModEntities.GLYPHID_BOMBARDIER.get()),
    BRAWLER(0.25F, 4.0F, 1.25D, () -> ModEntities.GLYPHID_BRAWLER.get()),
    DIGGER(0.35F, 3.0F, 1.3D, () -> ModEntities.GLYPHID_DIGGER.get()),
    BLASTER(0.50F, 2.0F, 1.25D, () -> ModEntities.GLYPHID_BLASTER.get()),
    BEHEMOTH(0.65F, 2.0F, 1.5D, () -> ModEntities.GLYPHID_BEHEMOTH.get()),
    NUCLEAR(0.80F, 0.5F, 2.0D, () -> ModEntities.GLYPHID_NUCLEAR.get()),
    BRENDA(0.90F, 0.5F, 2.0D, () -> ModEntities.GLYPHID_BRENDA.get());

    /**
     * Share of its full weight a caste carries the instant it unlocks. Not zero, or a caste gated at 0 — the
     * scout — never appears in a fresh world and nothing can expand.
     */
    private static final float UNLOCK_SHARE = 0.25F;

    private final float minEvolution;
    private final float baseWeight;
    private final double scale;
    private final Supplier<EntityType<? extends EntityGlyphid>> type;
    private final ResourceLocation skin;

    GlyphidCaste(float minEvolution, float baseWeight, double scale,
                 Supplier<EntityType<? extends EntityGlyphid>> type) {
        this.minEvolution = minEvolution;
        this.baseWeight = baseWeight;
        this.scale = scale;
        this.type = type;
        // The grunt wears the unsuffixed skin. Compared by name: a constructor may not reference a constant
        // of its own enum.
        String name = "GRUNT".equals(name()) ? "glyphid" : "glyphid_" + lowerName();
        this.skin = ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "textures/entity/" + name + ".png");
    }

    public EntityType<? extends EntityGlyphid> type() {
        return type.get();
    }

    /**
     * How much bigger than a grunt this caste is. Here rather than on the entity because the sim tier has a
     * caste and no class; {@code EntityGlyphid.getGlyphidScale} reads it back, so the number exists once.
     */
    public double scale() {
        return scale;
    }

    /** This caste's texture. Server-safe, and keeps the naming out of string munging in the renderer. */
    public ResourceLocation skin() {
        return skin;
    }

    /**
     * This caste's numbers, without an entity to ask — for the sim tier, which has a caste and no body.
     * A switch rather than {@code EntityGlyphid.getStats()}, which is an override on a subclass.
     */
    public GlyphidStats.StatBundle stats() {
        GlyphidStats table = GlyphidStats.getStats();
        return switch (this) {
            case GRUNT -> table.getGrunt();
            case SCOUT -> table.getScout();
            case BOMBARDIER -> table.getBombardier();
            case BRAWLER -> table.getBrawler();
            case DIGGER -> table.getDigger();
            case BLASTER -> table.getBlaster();
            case BEHEMOTH -> table.getBehemoth();
            case NUCLEAR -> table.getNuclear();
            case BRENDA -> table.getBrenda();
        };
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

    /**
     * Which caste an entity type is, falling back to {@link #GRUNT}. Built on first use, since resolving the
     * deferred entity types in a static initialiser would force them before the registry has them. The race
     * between two builders is benign; the volatile write publishes the map safely.
     */
    public static GlyphidCaste byType(EntityType<?> type) {
        Map<EntityType<?>, GlyphidCaste> map = BY_TYPE;
        if (map == null) {
            map = new IdentityHashMap<>();
            for (GlyphidCaste caste : VALUES) {
                map.put(caste.type(), caste);
            }
            BY_TYPE = map;
        }
        GlyphidCaste caste = map.get(type);
        return caste != null ? caste : GRUNT;
    }

    private static volatile @Nullable Map<EntityType<?>, GlyphidCaste> BY_TYPE;

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

    /** Cached: {@link #roll} walks it twice per bug and a warband materialises hundreds at once. */
    public static final GlyphidCaste[] VALUES = values();
}
