package com.wf.wflib.round.pen;

import com.wf.wflib.WFLib;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Penetration class: {@link PenTable} fallback for blocks without a data map value. {@link #resistance} = mm
 * steel-eq per metre. Order: {@code wflib:penetration/<name>} tag (most resistant wins) > unbreakable > blast
 * resistance >= 600 > vanilla/common tags > sound type > blast resistance bands.
 */
public enum PenMaterial {
    GLASS(0.3f),
    SOFT(1.5f),
    WOOD(6.0f),
    EARTH(25.0f),
    STONE(60.0f),
    METAL(250.0f),
    ARMOR(1000.0f),
    IMPENETRABLE(Float.POSITIVE_INFINITY);

    public static final PenMaterial[] VALUES = values();

    public final float resistance;
    public final TagKey<Block> tag;

    PenMaterial(float resistance) {
        this.resistance = resistance;
        this.tag = TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "penetration/" + name().toLowerCase()));
    }

    /** Needs a bootstrapped game; kept off the enum init (JUnit uses {@link #VALUES}). */
    private static final class BySound {
        static final Map<SoundType, PenMaterial> MAP = new IdentityHashMap<>();

        static {
            sound(GLASS, SoundType.GLASS);
            sound(SOFT, SoundType.WOOL, SoundType.SNOW, SoundType.POWDER_SNOW, SoundType.LILY_PAD, SoundType.SLIME_BLOCK,
                    SoundType.HONEY_BLOCK, SoundType.WET_GRASS, SoundType.CORAL_BLOCK, SoundType.SWEET_BERRY_BUSH,
                    SoundType.CROP, SoundType.HARD_CROP, SoundType.VINE, SoundType.NETHER_WART, SoundType.WART_BLOCK,
                    SoundType.SHROOMLIGHT, SoundType.WEEPING_VINES, SoundType.TWISTING_VINES, SoundType.FUNGUS,
                    SoundType.ROOTS, SoundType.NETHER_SPROUTS, SoundType.CAVE_VINES, SoundType.SPORE_BLOSSOM,
                    SoundType.AZALEA, SoundType.FLOWERING_AZALEA, SoundType.MOSS_CARPET, SoundType.PINK_PETALS,
                    SoundType.MOSS, SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF, SoundType.HANGING_ROOTS,
                    SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES, SoundType.CHERRY_SAPLING, SoundType.GLOW_LICHEN,
                    SoundType.SCULK, SoundType.SCULK_VEIN, SoundType.FROGLIGHT, SoundType.FROGSPAWN, SoundType.SPONGE,
                    SoundType.WET_SPONGE, SoundType.COBWEB, SoundType.CANDLE, SoundType.BAMBOO_SAPLING);
            sound(WOOD, SoundType.WOOD, SoundType.LADDER, SoundType.BAMBOO, SoundType.SCAFFOLDING, SoundType.STEM,
                    SoundType.HANGING_SIGN, SoundType.NETHER_WOOD_HANGING_SIGN, SoundType.BAMBOO_WOOD_HANGING_SIGN,
                    SoundType.BAMBOO_WOOD, SoundType.NETHER_WOOD, SoundType.CHERRY_WOOD,
                    SoundType.CHERRY_WOOD_HANGING_SIGN, SoundType.CHISELED_BOOKSHELF, SoundType.MANGROVE_ROOTS);
            sound(EARTH, SoundType.GRAVEL, SoundType.GRASS, SoundType.SAND, SoundType.SOUL_SAND, SoundType.SOUL_SOIL,
                    SoundType.NYLIUM, SoundType.ROOTED_DIRT, SoundType.MUD, SoundType.MUDDY_MANGROVE_ROOTS,
                    SoundType.PACKED_MUD, SoundType.SUSPICIOUS_SAND, SoundType.SUSPICIOUS_GRAVEL);
            sound(STONE, SoundType.STONE, SoundType.BASALT, SoundType.NETHERRACK, SoundType.NETHER_BRICKS,
                    SoundType.NETHER_ORE, SoundType.BONE_BLOCK, SoundType.NETHER_GOLD_ORE, SoundType.GILDED_BLACKSTONE,
                    SoundType.AMETHYST, SoundType.AMETHYST_CLUSTER, SoundType.SMALL_AMETHYST_BUD,
                    SoundType.MEDIUM_AMETHYST_BUD, SoundType.LARGE_AMETHYST_BUD, SoundType.TUFF, SoundType.TUFF_BRICKS,
                    SoundType.POLISHED_TUFF, SoundType.CALCITE, SoundType.DRIPSTONE_BLOCK, SoundType.POINTED_DRIPSTONE,
                    SoundType.SCULK_SENSOR, SoundType.SCULK_CATALYST, SoundType.SCULK_SHRIEKER, SoundType.DEEPSLATE,
                    SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES, SoundType.POLISHED_DEEPSLATE,
                    SoundType.MUD_BRICKS, SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED);
            sound(METAL, SoundType.METAL, SoundType.ANVIL, SoundType.LANTERN, SoundType.NETHERITE_BLOCK,
                    SoundType.ANCIENT_DEBRIS, SoundType.LODESTONE, SoundType.CHAIN, SoundType.COPPER,
                    SoundType.COPPER_BULB, SoundType.COPPER_GRATE, SoundType.TRIAL_SPAWNER, SoundType.VAULT,
                    SoundType.HEAVY_CORE);
        }

        private static void sound(PenMaterial m, SoundType... types) {
            for (SoundType t : types) {
                MAP.put(t, m);
            }
        }
    }

    static PenMaterial resolve(BlockState state) {
        for (int m = VALUES.length - 1; m >= 0; m--) {
            if (state.is(VALUES[m].tag)) {
                return VALUES[m];
            }
        }
        if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0.0f) {
            return IMPENETRABLE;
        }
        float blast = state.getBlock().getExplosionResistance();
        if (blast >= 600.0f) {
            return ARMOR;
        }
        if (state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)) {
            return GLASS;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) {
            return SOFT;
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS)) {
            return WOOD;
        }
        @SuppressWarnings("deprecation")
        PenMaterial bySound = BySound.MAP.get(state.getSoundType());
        if (bySound != null) {
            return bySound;
        }
        return blast <= 1.0f ? SOFT : blast <= 4.0f ? WOOD : blast <= 20.0f ? STONE : METAL;
    }
}
