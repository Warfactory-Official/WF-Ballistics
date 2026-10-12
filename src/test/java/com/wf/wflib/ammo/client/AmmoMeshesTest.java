package com.wf.wflib.ammo.client;

import com.wf.gemrender.rig.RigGeometry;
import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.AmmoVariant;
import com.wf.wflib.ammo.Calibre;
import com.wf.wflib.ammo.ProjectileLook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmmoMeshesTest {
    private static final AmmoMeshes.Uv UV = new AmmoMeshes.Uv() {
        @Override
        public float u(int rgb) {
            return (rgb & 0xFF) / 255f;
        }

        @Override
        public float v(int rgb) {
            return (rgb >> 8 & 0xFF) / 255f;
        }
    };

    /** z extent, max radius, signed volume (outward winding => > 0). */
    private static double[] stats(RigGeometry g) {
        float[] p = g.positions();
        double zmin = 1e9, zmax = -1e9, rmax = 0, vol = 0;
        for (int i = 0; i < p.length; i += 3) {
            zmin = Math.min(zmin, p[i + 2]);
            zmax = Math.max(zmax, p[i + 2]);
            rmax = Math.max(rmax, Math.hypot(p[i], p[i + 1]));
        }
        int[] ix = g.indices();
        for (int t = 0; t < ix.length; t += 3) {
            int a = ix[t] * 3, b = ix[t + 1] * 3, c = ix[t + 2] * 3;
            vol += (p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1])
                    - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c])
                    + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])) / 6.0;
        }
        return new double[]{zmin, zmax, rmax, vol};
    }

    @Test
    void cartridgeSpansOverallLengthWithinRim() {
        for (AmmoVariant v : Ammo.variants()) {
            Calibre c = Ammo.calibre(v.calibre());
            ProjectileLook look = ProjectileLook.resolve(c, v);
            double[] s = stats(AmmoMeshes.cartridge(c, look, ProjectileLook.COPPER, UV));
            double len = c.hasCase() ? c.overallMm() : look.lengthMm();
            assertEquals(0, s[0], 1e-6, v.id().toString());
            assertEquals(len / 1000, s[1], 1e-5, v.id().toString());
            if (c.hasCase()) {
                assertTrue(s[2] <= Math.max(Math.max(c.rimMm(), c.caseMm()), c.bulletMm() * 1.04) / 2000 + 1e-6, v.id().toString());
            }
            assertTrue(s[3] > 0, v.id() + " inside out: volume " + s[3]);
        }
    }

    @Test
    void projectileSpansLookLength() {
        for (AmmoVariant v : Ammo.variants()) {
            ProjectileLook look = ProjectileLook.resolve(Ammo.calibre(v.calibre()), v);
            double[] s = stats(AmmoMeshes.projectile(look, UV));
            assertEquals(0, s[0], 1e-6, v.id().toString());
            assertEquals(look.lengthMm() / 1000, s[1], 1e-5, v.id().toString());
            assertTrue(s[3] > 0, v.id() + " inside out");
        }
    }

    /** Flame: nozzle ring (z 0) = the rocket's tail ring, tip FLAME_LENGTH nozzle radii behind, outward. */
    @Test
    void flameBehindTheNozzle() {
        int n = 0;
        for (AmmoVariant v : Ammo.variants()) {
            ProjectileLook look = ProjectileLook.resolve(Ammo.calibre(v.calibre()), v);
            if (!look.flame()) {
                continue;
            }
            n++;
            float rn = AmmoMeshes.nozzle(look);
            double[] s = stats(AmmoMeshes.flame(look, UV));
            assertEquals(-AmmoMeshes.FLAME_LENGTH * rn, s[0], 1e-6, v.id().toString());
            assertEquals(0, s[1], 1e-6, v.id().toString());
            assertEquals(1.25 * rn, s[2], 1e-6, v.id().toString());
            assertTrue(s[3] > 0, v.id() + " inside out");
            float[] p = AmmoMeshes.projectile(look, UV).positions();
            double tail = 0;
            for (int i = 0; i < p.length; i += 3) {
                if (p[i + 2] == 0) {
                    tail = Math.max(tail, Math.hypot(p[i], p[i + 1]));
                }
            }
            assertEquals(rn, tail, 1e-6, v.id() + " tail ring");
        }
        assertTrue(n > 0, "no flame look in the catalogue");
    }

    @Test
    void tipColouredOnlyWhenSet() {
        Calibre c = Ammo.calibre(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("wflib", "5.56x45"));
        AmmoVariant m855 = Ammo.variant(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("wflib",
                "5.56x45/ball"));
        RigGeometry g = AmmoMeshes.cartridge(c, ProjectileLook.resolve(c, m855), ProjectileLook.COPPER, UV);
        float tipU = UV.u(m855.tip()), tipV = UV.v(m855.tip());
        boolean found = false;
        float[] t = g.texCoords();
        for (int i = 0; i < t.length; i += 2) {
            found |= t[i] == tipU && t[i + 1] == tipV;
        }
        assertTrue(found, "M855 green tip drawn");
    }
}
