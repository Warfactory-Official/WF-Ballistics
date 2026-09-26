package com.wf.wflib.rail;

import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.ProfileVolume;
import com.wf.wflib.rail.excavate.TorchPlan;
import com.wf.wflib.rail.excavate.TunnelProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drawn section swept along a route.
 *
 * <p>The seal is checked twice on purpose: once on the drawing, where it is two dimensional and cheap,
 * and again here on the swept solid, where a bend can open a gap the drawing never had. A profile that
 * is sealed on paper and leaks on a curve would be the worst of both.</p>
 */
class ProfileSweepTest {

    private static final TunnelProfile ARCHED = TunnelProfile.parse("arched", List.of(
            "@torch 8",
            "  ######  ",
            " #......# ",
            "#........#",
            "#........#",
            "#........#",
            "#L......L#",
            "##########"));

    /** A straight run into a curve, so the sweep is tested where the section has to fan out. */
    private static CarveVolume.Corridor dogleg(TunnelProfile profile, int floorY) {
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
        return CarveVolume.corridor(xs, zs, profile.width(), floorY, profile.boreHeight());
    }

    @Test
    @DisplayName("a drawn section stays watertight when it is swept round a bend")
    void sweptSectionSeals() {
        CarveVolume.Corridor path = dogleg(ARCHED, 0);
        CarveVolume bore = new ProfileVolume(path, ARCHED, TunnelProfile.Kind.BORE, 0, false);
        CarveVolume shell = new ProfileVolume(path, ARCHED, TunnelProfile.Kind.LINING, 0, true);
        BoundingBox search = shell.bounds();

        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int cells = 0;
        for (int x = search.minX(); x <= search.maxX(); x++) {
            for (int y = search.minY(); y <= search.maxY(); y++) {
                for (int z = search.minZ(); z <= search.maxZ(); z++) {
                    if (!bore.contains(x, y, z)) {
                        continue;
                    }
                    cells++;
                    for (int[] face : faces) {
                        int nx = x + face[0];
                        int ny = y + face[1];
                        int nz = z + face[2];
                        assertTrue(bore.contains(nx, ny, nz) || shell.contains(nx, ny, nz),
                                "the cell at " + nx + "," + ny + "," + nz + " touches the tunnel at "
                                        + x + "," + y + "," + z + " and is neither tunnel nor lining");
                    }
                }
            }
        }
        assertTrue(cells > 1000, "the sweep should be a real tunnel, not a few cells: " + cells);
    }

    @Test
    @DisplayName("the arch is an arch: the top corners are left as untouched ground")
    void theSectionKeepsItsShape() {
        CarveVolume.Corridor path = CarveVolume.corridor(
                new double[]{0.0, 64.0}, new double[]{0.0, 0.0}, ARCHED.width(), 0, ARCHED.boreHeight());
        CarveVolume bore = new ProfileVolume(path, ARCHED, TunnelProfile.Kind.BORE, 0, false);
        CarveVolume shell = new ProfileVolume(path, ARCHED, TunnelProfile.Kind.LINING, 0, true);

        int across = 0;
        for (int z = -16; z <= 16; z++) {
            if (bore.contains(32, 0, z)) {
                across++;
            }
        }
        assertEquals(8, across, "eight across on the floor");

        assertEquals(5, ARCHED.boreHeight(), "the arched section is five courses tall");
        int atRoof = 0;
        for (int z = -16; z <= 16; z++) {
            if (bore.contains(32, 4, z)) {
                atRoof++;
            }
        }
        assertEquals(6, atRoof, "and six across at the crown, because the roof is arched");

        // The corner the arch cuts off is neither tunnel nor wall: it is ground nobody touched.
        int cornerZ = -1;
        for (int z = -16; z <= 16; z++) {
            if (bore.contains(32, 0, z)) {
                cornerZ = z;
                break;
            }
        }
        assertFalse(bore.contains(32, 4, cornerZ), "the crown does not reach the outermost floor column");
        assertFalse(shell.contains(32, 5, cornerZ - 1), "and the drawing leaves that corner alone");
    }

    @Test
    @DisplayName("a route of a given length still bores exactly that length through a drawn section")
    void theSweepDoesNotOverrunTheRoute() {
        CarveVolume.Corridor path = CarveVolume.corridor(
                new double[]{0.0, 64.0}, new double[]{0.0, 0.0}, ARCHED.width(), 0, ARCHED.boreHeight());
        CarveVolume bore = new ProfileVolume(path, ARCHED, TunnelProfile.Kind.BORE, 0, false);
        CarveVolume shell = new ProfileVolume(path, ARCHED, TunnelProfile.Kind.LINING, 0, true);

        int along = 0;
        for (int x = -24; x <= 96; x++) {
            if (bore.contains(x, 0, 0)) {
                along++;
            }
        }
        assertEquals(64, along, "a 64 block route is a 64 block tunnel");
        assertFalse(bore.contains(-1, 0, 0), "nothing bored behind the start");
        assertFalse(bore.contains(64, 0, 0), "nothing bored past the end");
        assertTrue(shell.contains(-1, 0, 0), "the start has a wall across it");
        assertTrue(shell.contains(64, 0, 0), "and so does the end");
        assertFalse(shell.contains(-2, 0, 0), "and the wall is one block thick, not the section's width");
    }

    @Test
    @DisplayName("every torch lands inside the tunnel, at the interval the section asks for")
    void torchesLandInTheTunnel() {
        CarveVolume.Corridor path = dogleg(ARCHED, 0);
        CarveVolume bore = new ProfileVolume(path, ARCHED, TunnelProfile.Kind.BORE, 0, false);
        List<BlockPos> torches = TorchPlan.positions(TorchPlan.along(path, ARCHED, 0, bore));

        assertTrue(torches.size() > 20, "a 140 block route at eight blocks a torch: " + torches.size());
        for (BlockPos pos : torches) {
            assertTrue(bore.contains(pos.getX(), pos.getY(), pos.getZ()),
                    "a torch at " + pos + " is not inside the tunnel, so it would hole the lining");
            assertEquals(0, pos.getY(), "torches go on the floor course");
        }
    }

    @Test
    @DisplayName("an unlit section asks for no torches at all")
    void noTorchesWhenTheSectionHasNone() {
        TunnelProfile dark = TunnelProfile.parse("dark", List.of("######", "#....#", "######"));
        CarveVolume.Corridor path = dogleg(dark, 0);
        CarveVolume bore = new ProfileVolume(path, dark, TunnelProfile.Kind.BORE, 0, false);
        assertTrue(TorchPlan.along(path, dark, 0, bore).isEmpty());
    }
}
