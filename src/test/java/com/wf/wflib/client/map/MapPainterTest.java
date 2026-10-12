package com.wf.wflib.client.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPainterTest {

    /** Concave L, clockwise on a z-down map: the notch (15, 15) is outside. */
    private static final double[] L_SHAPE = {0, 0, 20, 0, 20, 10, 10, 10, 10, 20, 0, 20};

    private static double area(double[] xz, int[] triangles) {
        double sum = 0.0;
        for (int i = 0; i < triangles.length; i += 3) {
            int a = triangles[i] * 2;
            int b = triangles[i + 1] * 2;
            int c = triangles[i + 2] * 2;
            sum += Math.abs((xz[b] - xz[a]) * (xz[c + 1] - xz[a + 1]) - (xz[b + 1] - xz[a + 1]) * (xz[c] - xz[a])) / 2;
        }
        return sum;
    }

    private static double[] reversed(double[] xz) {
        double[] out = new double[xz.length];
        for (int i = 0; i < xz.length / 2; i++) {
            out[i * 2] = xz[xz.length - 2 - i * 2];
            out[i * 2 + 1] = xz[xz.length - 1 - i * 2];
        }
        return out;
    }

    @Test
    void concaveTrianglesCoverExactlyThePolygonEitherWinding() {
        assertEquals(300.0, area(L_SHAPE, MapPainter.triangulate(L_SHAPE)), 1e-9);
        double[] ccw = reversed(L_SHAPE);
        assertEquals(300.0, area(ccw, MapPainter.triangulate(ccw)), 1e-9);
        assertEquals(12, MapPainter.triangulate(L_SHAPE).length);
    }

    @Test
    void containsSkipsTheNotch() {
        assertTrue(MapPainter.contains(L_SHAPE, 5, 15));
        assertTrue(MapPainter.contains(L_SHAPE, 15, 5));
        assertFalse(MapPainter.contains(L_SHAPE, 15, 15));
    }

    /** Rotated minimap affine (Xaero's {@code ps}/{@code pc}): inverse returns the block. */
    @Test
    void affineInverts() {
        double angle = Math.toRadians(37);
        double zoom = 3.5;
        double ps = Math.sin(angle) * zoom;
        double pc = Math.cos(angle) * zoom;
        MapPainter.Affine a = new MapPainter.Affine(ps, -pc, 11.0, pc, ps, -7.0);
        double sx = a.screenX(123.0, -456.0);
        double sy = a.screenY(123.0, -456.0);
        assertEquals(123.0, a.blockX(sx, sy), 1e-9);
        assertEquals(-456.0, a.blockZ(sx, sy), 1e-9);
        assertEquals(zoom, a.pixelsPerBlock(), 1e-12);
    }
}
