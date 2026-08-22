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
    GRUNT(0.00F, 10.0F, 1.0D, () -> ModEntities.GLYPHID.get()),
    /**
     * Founds nests. Available from the start because a colony that cannot expand is not a colony, and rare
     * because one scout is worth a dozen grunts to the simulation.
     */
    SCOUT(0.00F, 1.0F, 0.75D, () -> ModEntities.GLYPHID_SCOUT.get()),
    BOMBARDIER(0.10F, 4.0F, 1.0D, () -> ModEntities.GLYPHID_BOMBARDIER.get()),
    BRAWLER(0.25F, 4.0F, 1.25D, () -> ModEntities.GLYPHID_BRAWLER.get()),
    DIGGER(0.35F, 3.0F, 1.3D, () -> ModEntities.GLYPHID_DIGGER.get()),
    BLASTER(0.50F, 2.0F, 1.25D, () -> ModEntities.GLYPHID_BLASTER.get()),
    BEHEMOTH(0.65F, 2.0F, 1.5D, () -> ModEntities.GLYPHID_BEHEMOTH.get()),
    NUCLEAR(0.80F, 0.5F, 2.0D, () -> ModEntities.GLYPHID_NUCLEAR.get()),
    BRENDA(0.90F, 0.5F, 2.0D, () -> ModEntities.GLYPHID_BRENDA.get());

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
    private final double scale;
    private final Supplier<EntityType<? extends EntityGlyphid>> type;
    private final ResourceLocation skin;

    GlyphidCaste(float minEvolution, float baseWeight, double scale,
                 Supplier<EntityType<? extends EntityGlyphid>> type) {
        this.minEvolution = minEvolution;
        this.baseWeight = baseWeight;
        this.scale = scale;
        this.type = type;
        // The grunt wears the unsuffixed skin the others are named against, being the glyphid every other
        // caste is a variation on. Compared by name because a constructor may not reference a constant of
        // its own enum.
        String name = "GRUNT".equals(name()) ? "glyphid" : "glyphid_" + lowerName();
        this.skin = ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "textures/entity/" + name + ".png");
    }

    public EntityType<? extends EntityGlyphid> type() {
        return type.get();
    }

    /**
     * How much bigger than a grunt this caste is.
     *
     * <p>Here rather than on the entity for the same reason {@link #skin} is: it is the caste that is a
     * different size, not the class, and the sim tier has a caste and no class. {@code EntityGlyphid}
     * reads it back out through {@code getGlyphidScale}, so the number exists once.
     */
    public double scale() {
        return scale;
    }

    /**
     * This caste's texture.
     *
     * <p>Server-safe despite being a render concern: a {@link ResourceLocation} is not a client class, and
     * keeping it here is what stops the skin naming from living as string munging inside the renderer, where
     * a caste whose texture is not named after it would silently draw the wrong one.
     */
    public ResourceLocation skin() {
        return skin;
    }

    /**
     * This caste's numbers, without an entity to ask.
     *
     * <p>Needed by the sim tier, which has a caste and no body. Kept as a switch on the enum rather than
     * derived from {@code EntityGlyphid.getStats()} because that one is an override per subclass, and the
     * point of a record is that no subclass has been instantiated.
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
     * Which caste an entity type is, falling back to {@link #GRUNT}.
     *
     * <p>Built on first use rather than in a static initialiser: the entity types are deferred registry
     * objects, and resolving them while this enum is still initialising would force them before the registry
     * has them. The race between two callers building it is benign — both build the same map — and the
     * volatile write is what publishes it safely.
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

    /**
     * Cached because {@link #roll} walks it twice per bug and a warband materialises hundreds at once.
     */
    public static final GlyphidCaste[] VALUES = values();
}
