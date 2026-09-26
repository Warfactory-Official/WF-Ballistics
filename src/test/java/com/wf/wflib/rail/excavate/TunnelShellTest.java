package com.wf.wflib.rail.excavate;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a lined tunnel actually holds water out.
 *
 * <p>This is the property the whole lining exists for, and it is the one that cannot be checked by
 * looking: a tunnel with a single unlined cell somewhere in three hundred metres looks perfect until an
 * aquifer finds it, and by then the evidence is a flooded tunnel rather than the hole that caused it.
 * A fluid only moves between blocks that share a face, so the test is over the six face neighbours of
 * every cell of the bore.</p>
 */
class TunnelShellTest {

    /** A dogleg with a curve in it, so the test is not just about a straight box. */
    private static CarveVolume.Corridor dogleg(int width, int floorY, int height) {
        double[] xs = new double[41];
        double[] zs = new double[41];
        for (int i = 0; i <= 20; i++) {
            xs[i] = i * 4.0;
            zs[i] = 0.0;
        }
        for (int i = 1; i <= 20; i++) {
            xs[20 + i] = 80.0 + i * 3.0;
            zs[20 + i] = i * 3.0;
        }
        return CarveVolume.corridor(xs, zs, width, floorY, height);
    }

    private static void assertSealed(CarveVolume bore, CarveVolume shell, BoundingBox search) {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int boreCells = 0;
        for (int x = search.minX(); x <= search.maxX(); x++) {
            for (int y = search.minY(); y <= search.maxY(); y++) {
                for (int z = search.minZ(); z <= search.maxZ(); z++) {
                    if (!bore.contains(x, y, z)) {
                        continue;
                    }
                    boreCells++;
                    for (int[] face : faces) {
                        int nx = x + face[0];
                        int ny = y + face[1];
                        int nz = z + face[2];
                        assertTrue(bore.contains(nx, ny, nz) || shell.contains(nx, ny, nz),
                                "the cell at " + nx + "," + ny + "," + nz + " touches the bore at "
                                        + x + "," + y + "," + z + " and is neither tunnel nor lining");
                    }
                }
            }
        }
        assertTrue(boreCells > 0, "the search box must actually contain the bore");
    }

    @Test
    @DisplayName("every face of the bore is either more tunnel or lining, so nothing can flow in")
    void theShellSeals() {
        CarveVolume.Corridor bore = dogleg(6, 0, 6);
        CarveVolume shell = CarveVolume.difference(bore.grown(1), bore);
        assertSealed(bore, shell, bore.grown(2).bounds());
    }

    @Test
    @DisplayName("the ends are capped too, so a tunnel that stops in an aquifer is still sealed")
    void theEndsAreCapped() {
        CarveVolume.Corridor bore = CarveVolume.corridor(
                new double[]{0.0, 64.0}, new double[]{0.0, 0.0}, 6, 0, 6);
        CarveVolume shell = CarveVolume.difference(bore.grown(1), bore);
        // One block beyond each end of the bore, on the centreline.
        assertTrue(bore.contains(0, 0, 0) || bore.contains(1, 0, 0));
        assertTrue(shell.contains(-1, 2, 0), "the near end has a wall");
        assertTrue(shell.contains(64, 2, 0), "the far end has a wall");
    }

    @Test
    @DisplayName("a tunnel built in stretches is not walled off where two stretches meet")
    void stretchesDoNotBrickEachOtherUp() {
        double[] xs = {0.0, 48.0, 96.0};
        double[] zs = {0.0, 0.0, 0.0};
        CarveVolume.Corridor whole = CarveVolume.corridor(xs, zs, 6, 0, 6);
        CarveVolume.Corridor first = CarveVolume.corridor(
                new double[]{0.0, 48.0}, new double[]{0.0, 0.0}, 6, 0, 6);
        CarveVolume.Corridor second = CarveVolume.corridor(
                new double[]{48.0, 96.0}, new double[]{0.0, 0.0}, 6, 0, 6);

        CarvePlan planA = CarvePlan.tunnel(first, whole, CarvePlan.DEFAULT_LINING, LightingPolicy.DEFERRED);
        CarvePlan planB = CarvePlan.tunnel(second, whole, CarvePlan.DEFAULT_LINING, LightingPolicy.DEFERRED);

        // Whichever order they land in, neither lines a cell the other one opens.
        BoundingBox search = whole.grown(2).bounds();
        for (int x = search.minX(); x <= search.maxX(); x++) {
            for (int y = search.minY(); y <= search.maxY(); y++) {
                for (int z = search.minZ(); z <= search.maxZ(); z++) {
                    if (!whole.contains(x, y, z)) {
                        continue;
                    }
                    assertFalse(planA.shell().contains(x, y, z),
                            "the first stretch would brick up the tunnel at " + x + "," + y + "," + z);
                    assertFalse(planB.shell().contains(x, y, z),
                            "the second stretch would brick up the tunnel at " + x + "," + y + "," + z);
                }
            }
        }
        // And together they still seal the whole thing.
        assertSealed(whole, union(planA.shell(), planB.shell()), search);
    }

    /** Two shells as one volume, which is what the finished tunnel is lined with. */
    private static CarveVolume union(CarveVolume a, CarveVolume b) {
        return new CarveVolume() {
            @Override
            public BoundingBox bounds() {
                return a.bounds().encapsulate(b.bounds());
            }

            @Override
            public boolean contains(int x, int y, int z) {
                return a.contains(x, y, z) || b.contains(x, y, z);
            }
        };
    }

    @Test
    @DisplayName("a 6x6 tunnel is six cells across and six tall, not five or seven")
    void theProfileIsTheSizeItSays() {
        CarveVolume.Corridor bore = CarveVolume.corridor(
                new double[]{0.0, 64.0}, new double[]{0.0, 0.0}, 6, 0, 6);
        int across = 0;
        for (int z = -16; z <= 16; z++) {
            if (bore.contains(32, 0, z)) {
                across++;
            }
        }
        int tall = 0;
        for (int y = -16; y <= 16; y++) {
            if (bore.contains(32, y, 0)) {
                tall++;
            }
        }
        assertEquals(6, across, "six cells across");
        assertEquals(6, tall, "six cells tall");
        assertTrue(bore.contains(32, 0, 0), "the floor is the y it was asked for");
        assertFalse(bore.contains(32, -1, 0), "and nothing below it");
        assertFalse(bore.contains(32, 6, 0), "and nothing above the sixth course");
    }

    @Test
    @DisplayName("a route of a given length bores exactly that length, with square portals")
    void theLengthIsTheLengthItSays() {
        CarveVolume.Corridor bore = CarveVolume.corridor(
                new double[]{0.0, 64.0}, new double[]{0.0, 0.0}, 6, 0, 6);
        int along = 0;
        for (int x = -16; x <= 80; x++) {
            if (bore.contains(x, 0, 0)) {
                along++;
            }
        }
        assertEquals(64, along, "a 64 block route is a 64 block tunnel, not 70");
        assertTrue(bore.contains(0, 0, 0) && bore.contains(63, 0, 0));
        assertFalse(bore.contains(-1, 0, 0), "nothing bored behind the start");
        assertFalse(bore.contains(64, 0, 0), "nothing bored past the end");

        // And the portal is flat across the full width, not a dome.
        for (int z = -3; z <= 2; z++) {
            assertTrue(bore.contains(0, 0, z), "the first course is the full width at z=" + z);
        }
    }

    @Test
    @DisplayName("an odd-width tunnel is also the width it says, which needs the opposite snapping")
    void oddWidthsAreExactToo() {
        CarveVolume.Corridor bore = CarveVolume.corridor(
                new double[]{0.5, 64.5}, new double[]{0.5, 0.5}, 7, 0, 5);
        int across = 0;
        for (int z = -16; z <= 16; z++) {
            if (bore.contains(32, 0, z)) {
                across++;
            }
        }
        assertEquals(7, across);
    }

    @Test
    @DisplayName("a surveyor's arbitrary click still gives an exact profile, because the axis is snapped")
    void anAxisOffTheGridStillGivesAnExactProfile() {
        for (double offset : new double[]{0.0, 0.17, 0.4, 0.51, 0.83, 0.99}) {
            CarveVolume.Corridor bore = CarveVolume.corridor(
                    new double[]{0.0, 64.0}, new double[]{offset, offset}, 6, 0, 6);
            int across = 0;
            for (int z = -16; z <= 16; z++) {
                if (bore.contains(32, 0, z)) {
                    across++;
                }
            }
            assertEquals(6, across, "a route drawn at z=" + offset + " is still six cells across");
        }
    }
}
