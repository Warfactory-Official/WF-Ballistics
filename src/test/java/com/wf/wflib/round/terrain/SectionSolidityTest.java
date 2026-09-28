package com.wf.wflib.round.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Solid voxels' resistances survive the palette + bit-plane packing; planes = ceil(log2(values present)). */
class SectionSolidityTest {

    private static SectionSolidity pack(float[] palette, long seed, short[] out) {
        Random r = new Random(seed);
        long[] solid = new long[64];
        for (int i = 0; i < 4096; i++) {
            if (r.nextInt(4) != 0) {
                solid[i >> 6] |= 1L << i;
                out[i] = (short) r.nextInt(palette.length);
            }
        }
        return SectionSolidity.of(solid, new long[64], null, out, palette);
    }

    @Test
    void everySolidVoxelKeepsItsResistance() {
        float[][] palettes = {{25.0f, 60.0f}, {0.3f, 6.0f, 1000.0f}, {1.5f, 6.0f, 25.0f, 60.0f, 250.0f},
                {0.3f, 1.5f, 6.0f, 25.0f, 40.0f, 60.0f, 130.0f, 250.0f, 1000.0f}};
        int[] planes = {1, 2, 3, 4};
        for (int k = 0; k < palettes.length; k++) {
            short[] m = new short[4096];
            SectionSolidity s = pack(palettes[k], k, m);
            for (int i = 0; i < 4096; i++) {
                if (s.solid(i)) {
                    assertEquals(palettes[k][m[i]], s.resistance(i), "palette " + k + " voxel " + i);
                }
            }
            assertEquals(512 + 16 + 4 * palettes[k].length + 512 * planes[k], s.bytes(), "palette " + k);
        }
    }

    @Test
    void oneValueNeedsNoPlanes() {
        long[] solid = new long[64];
        Arrays.fill(solid, -1L);
        SectionSolidity all = SectionSolidity.of(solid, new long[64], null, null, new float[]{6.0f});
        assertEquals(0, all.bytes());
        assertEquals(6.0f, all.resistance(1234));
        solid[3] = 0L;
        SectionSolidity most = SectionSolidity.of(solid, new long[64], null, null, new float[]{250.0f});
        assertEquals(512, most.bytes());
        assertEquals(250.0f, most.resistance(0));
    }
}
