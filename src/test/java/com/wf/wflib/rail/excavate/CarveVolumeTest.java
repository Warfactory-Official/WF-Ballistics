package com.wf.wflib.rail.excavate;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The volume decides what a tunnel is. */
class CarveVolumeTest {

    @Test
    @DisplayName("a capsule contains everything within its radius of the segment, ends included")
    void capsuleIsRoundEnded() {
        CarveVolume tunnel = CarveVolume.capsule(new Vec3(0.5, 0.5, 0.5), new Vec3(16.5, 0.5, 0.5), 2.0);
        assertTrue(tunnel.contains(8, 0, 0), "on the axis");
        assertTrue(tunnel.contains(8, 2, 0), "at the radius");
        assertFalse(tunnel.contains(8, 3, 0), "past the radius");
        // Round ends are what make consecutive arc segments join without a notch.
        assertTrue(tunnel.contains(-1, 0, 0), "behind the start, inside the cap");
        assertFalse(tunnel.contains(-3, 0, 0), "past the cap");
    }

    @Test
    @DisplayName("a capsule's bounds enclose every block it contains")
    void capsuleBoundsEncloseIt() {
        CarveVolume tunnel = CarveVolume.capsule(new Vec3(0.5, 40.5, 0.5), new Vec3(20.5, 46.5, 9.5), 3.0);
        BoundingBox bounds = tunnel.bounds();
        for (int x = bounds.minX() - 2; x <= bounds.maxX() + 2; x++) {
            for (int y = bounds.minY() - 2; y <= bounds.maxY() + 2; y++) {
                for (int z = bounds.minZ() - 2; z <= bounds.maxZ() + 2; z++) {
                    if (tunnel.contains(x, y, z)) {
                        assertTrue(bounds.isInside(x, y, z),
                                "contained but outside bounds: " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a degenerate capsule is a sphere rather than a division by zero")
    void zeroLengthCapsuleIsASphere() {
        CarveVolume point = CarveVolume.capsule(new Vec3(0.5, 0.5, 0.5), new Vec3(0.5, 0.5, 0.5), 2.0);
        assertTrue(point.contains(0, 0, 0));
        assertTrue(point.contains(2, 0, 0));
        assertFalse(point.contains(3, 0, 0));
    }

    @Test
    @DisplayName("a box contains exactly its own bounds")
    void boxIsItsBounds() {
        BoundingBox box = new BoundingBox(-4, 10, -4, 4, 14, 4);
        CarveVolume cut = CarveVolume.box(box);
        assertEquals(box, cut.bounds());
        assertTrue(cut.contains(-4, 10, -4), "min corner");
        assertTrue(cut.contains(4, 14, 4), "max corner");
        assertFalse(cut.contains(-5, 10, -4));
        assertFalse(cut.contains(4, 15, 4));
    }
}
