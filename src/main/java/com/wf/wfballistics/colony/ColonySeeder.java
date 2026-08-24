package com.wf.wfballistics.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Puts the first nests in the world, so a colony is something a player finds rather than commands into
 * existence. Everything else in this package grows a colony from a colony.
 *
 * <p>A grid of {@link ColonyConfig#naturalSpacing()} cells holding at most one nest each, positioned from the
 * world seed — the shape vanilla uses for villages. A per-chunk roll would instead tie density to how the
 * world was explored, and would clump hard enough to trip {@link ColonyConfig#crowdingLimit()}.
 *
 * <p>A cell is consumed the first time it is examined, even when the answer is "nothing here", and that is
 * recorded in {@link ColonyRegistry} rather than inferred from the colony list. Otherwise razing a nest would
 * be temporary: walking away and back would rebuild what the player just cleared.
 */
public final class ColonySeeder {

    /** How far inside its cell a nest is kept, so two either side of a boundary cannot land side by side. */
    private static final double MARGIN = 0.2;

    private ColonySeeder() {
    }

    /**
     * Found whatever natural colony belongs in this chunk, if any. Called from
     * {@link ColonyManager#onChunkLoaded} ahead of its build pass, so a nest seeded here is built by it.
     */
    public static void seed(ServerLevel level, ColonyRegistry registry, ChunkAccess chunk) {
        int spacing = ColonyConfig.naturalSpacing();
        if (spacing <= 0) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();

        // A chunk can straddle a boundary, so up to four cells reach into it. Iterating the corners covers
        // them all without assuming the ratio.
        for (int cx = Math.floorDiv(minX, spacing); cx <= Math.floorDiv(minX + 15, spacing); cx++) {
            for (int cz = Math.floorDiv(minZ, spacing); cz <= Math.floorDiv(minZ + 15, spacing); cz++) {
                candidate(level, registry, chunk, cx, cz, spacing);
            }
        }
    }

    private static void candidate(ServerLevel level, ColonyRegistry registry, ChunkAccess chunk,
                                  int cx, int cz, int spacing) {
        long cell = key(cx, cz);
        long hash = mix(level.getSeed() ^ key(cx, cz));

        int margin = (int) (spacing * MARGIN);
        int span = Math.max(1, spacing - 2 * margin);
        int x = cx * spacing + margin + (int) Math.floorMod(mix(hash + 1) >>> 16, span);
        int z = cz * spacing + margin + (int) Math.floorMod(mix(hash + 2) >>> 16, span);
        if ((x >> 4) != chunk.getPos().x || (z >> 4) != chunk.getPos().z) {
            return;
        }
        // Consumed on the first look, whatever the answer: see the class note on razing.
        if (!registry.consumeCell(cell)) {
            return;
        }
        if (unit(hash) >= ColonyConfig.naturalChance()) {
            return;
        }
        if (!standable(chunk, x, z)) {
            return;
        }

        Colony colony = ColonyManager.found(level, registry, x, z);
        if (colony != null) {
            // Knowable here and only here: this is the chunk, and it will not load a second time.
            colony.y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        }
    }

    /**
     * @return whether the surface can hold a mound rather than being open water. Read off the chunk being
     * loaded, so nothing else has to be.
     */
    private static boolean standable(ChunkAccess chunk, int x, int z) {
        // getHeight answers with the first free cell, so the ground is below it -- and over an ocean that
        // ground is water, since MOTION_BLOCKING counts fluids.
        int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        return chunk.getBlockState(new BlockPos(x, surface - 1, z)).getFluidState().isEmpty();
    }

    private static long key(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) << 32 | ((long) cz & 0xFFFFFFFFL);
    }

    /** A double in [0, 1) from the top bits, which are the well-mixed ones. */
    private static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static long mix(long value) {
        long h = value * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
