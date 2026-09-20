package com.wf.wflib.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The finishes a mine can wear, and which ground each one suits. */
public enum MineCamo {

    /** The olive the models ship in: no substitution, always variant 0, and the fallback for everything. */
    DEFAULT,

    /** Anything green and growing: grass, leaves, moss, the top of a jungle floor. */
    GRASS(MapColor.GRASS, MapColor.PLANT, MapColor.COLOR_GREEN, MapColor.EMERALD, MapColor.TERRACOTTA_GREEN,
            MapColor.WARPED_NYLIUM, MapColor.WARPED_WART_BLOCK),

    /** Turned earth: dirt, podzol, coarse ground, mud, and the brown end of terracotta. */
    DIRT(MapColor.DIRT, MapColor.PODZOL, MapColor.COLOR_BROWN, MapColor.TERRACOTTA_BROWN,
            MapColor.TERRACOTTA_ORANGE, MapColor.WOOD),

    /** Desert and beach: sand, sandstone, the pale end of terracotta. */
    SAND(MapColor.SAND, MapColor.TERRACOTTA_WHITE, MapColor.TERRACOTTA_YELLOW, MapColor.COLOR_YELLOW,
            MapColor.QUARTZ),

    /** Rock and rubble: stone, deepslate, gravel, clay, concrete, and most built surfaces. */
    STONE(MapColor.STONE, MapColor.DEEPSLATE, MapColor.CLAY, MapColor.COLOR_GRAY,
            MapColor.COLOR_LIGHT_GRAY, MapColor.TERRACOTTA_GRAY, MapColor.TERRACOTTA_LIGHT_GRAY,
            MapColor.METAL, MapColor.COLOR_BLACK),

    /** Snow and ice, where everything else is the one finish that gets you seen. */
    SNOW(MapColor.SNOW, MapColor.ICE, MapColor.WOOL),

    /** The nether, which is its own colour and nothing like any of the above. */
    NETHER(MapColor.NETHER, MapColor.CRIMSON_NYLIUM, MapColor.CRIMSON_STEM, MapColor.CRIMSON_HYPHAE,
            MapColor.COLOR_RED, MapColor.FIRE);

    private static final Map<MapColor, MineCamo> BY_COLOR = Stream.of(values())
            .flatMap(camo -> Stream.of(camo.colors)
                    .map(color -> Map.entry(color, camo)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

    private final MapColor[] colors;
    private final String id;

    MineCamo(MapColor... colors) {
        this.colors = colors;
        this.id = name().toLowerCase(Locale.ROOT);
    }

    /** The name this finish is carried by in entity data and looked up by in a texture path. */
    public String id() {
        return id;
    }

    /** @return the ground colours this finish claims. Two finishes claiming one colour is a bug. */
    public List<MapColor> colors() {
        return List.of(colors);
    }

    /** Resolve a persisted id, falling back to {@link #DEFAULT} for anything unrecognised. */
    public static MineCamo parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT;
        }
        for (MineCamo camo : values()) {
            if (camo.id.equals(id)) {
                return camo;
            }
        }
        return DEFAULT;
    }

    /**
     * @return the finish that suits the ground at {@code pos}: the block the mine is standing <em>on</em>.
     *      Pass an entity's {@code getOnPos()}, which is the one that gets a mine resting on a slab or a path
     *      right; the block under its feet and the block one below it are not the same question.
     */
    public static MineCamo forGround(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            state = level.getBlockState(pos.below());
            pos = pos.below();
        }
        return state.isAir() ? DEFAULT
                : BY_COLOR.getOrDefault(state.getMapColor(level, pos), DEFAULT);
    }
}
