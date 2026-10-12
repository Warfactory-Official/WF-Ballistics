package com.wf.wflib.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** First nests; a colony is something a player finds, not something commanded into existence. */
public final class ColonySeeder {

    /** Inset from the cell edge; keeps nests either side of a boundary apart. */
    private static final double MARGIN = 0.2;

    private ColonySeeder() {
    }

    public static void seed(ServerLevel level, ColonyRegistry registry, ChunkAccess chunk) {
        int spacing = ColonyConfig.naturalSpacing();
        if (spacing <= 0) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();

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
        // Consumed on first look regardless of outcome; raze() never frees it => no natural reseed there.
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
            // Only chance to resolve y: this chunk will not load a second time.
            colony.y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        }
    }

    /** Fluid check only; reads the loading chunk itself so nothing else needs loading. */
    private static boolean standable(ChunkAccess chunk, int x, int z) {
        int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        return chunk.getBlockState(new BlockPos(x, surface - 1, z)).getFluidState().isEmpty();
    }

    private static long key(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) << 32 | ((long) cz & 0xFFFFFFFFL);
    }

    /** [0, 1) from the top bits: best-mixed. */
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
