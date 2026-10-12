package com.wf.wflib.ammo.client;

import com.wf.gemrender.rig.RigGeometry;
import com.wf.wflib.ammo.Calibre;
import com.wf.wflib.ammo.CalibreKind;
import com.wf.wflib.ammo.ProjectileLook;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural cartridge + projectile meshes: surfaces of revolution about +Z (base z = 0, nose +Z), fins as boxes.
 * Units blocks. Colours rgb -> texture coordinate via {@code uv} (palette cell centre).
 */
public final class AmmoMeshes {
    static final int CARTRIDGE_SIDES = 12;
    static final int PROJECTILE_SIDES = 16;
    private static final float MM = 0.001f;
    static final float FLAME_LENGTH = 25;
    static final int FLAME_CORE = 0xFFF2B0;
    static final int FLAME = 0xFF9A30;
    static final int FLAME_TIP = 0xE0501A;

    private AmmoMeshes() {
    }

    /** One ring of a profile; the segment arriving at it takes its colour. */
    record Ring(float z, float r, int rgb) {
    }

    public interface Uv {
        float u(int rgb);

        float v(int rgb);
    }

    static final class Mesh {
        final FloatArrayList pos = new FloatArrayList();
        final FloatArrayList nrm = new FloatArrayList();
        final FloatArrayList tex = new FloatArrayList();
        final IntArrayList idx = new IntArrayList();

        int vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
            pos.add(x);
            pos.add(y);
            pos.add(z);
            nrm.add(nx);
            nrm.add(ny);
            nrm.add(nz);
            tex.add(u);
            tex.add(v);
            return pos.size() / 3 - 1;
        }

        void quad(int a, int b, int c, int d) {
            idx.add(a);
            idx.add(b);
            idx.add(c);
            idx.add(a);
            idx.add(c);
            idx.add(d);
        }

        RigGeometry build() {
            return new RigGeometry(pos.toFloatArray(), nrm.toFloatArray(), tex.toFloatArray(), idx.toIntArray());
        }
    }

    /** Lathe with per-segment normals (faceted along the profile, smooth around), base + nose caps. */
    static void lathe(Mesh m, List<Ring> rings, int sides, Uv uv) {
        float[] cos = new float[sides + 1];
        float[] sin = new float[sides + 1];
        for (int i = 0; i <= sides; i++) {
            double a = 2 * Math.PI * i / sides;
            cos[i] = (float) Math.cos(a);
            sin[i] = (float) Math.sin(a);
        }
        for (int s = 1; s < rings.size(); s++) {
            Ring p = rings.get(s - 1), q = rings.get(s);
            float dz = q.z - p.z, dr = q.r - p.r;
            float len = (float) Math.sqrt(dz * dz + dr * dr);
            if (len < 1e-7f) {
                continue;
            }
            float nr = dz / len, nz = -dr / len;
            float u = uv.u(q.rgb), v = uv.v(q.rgb);
            int base = -1;
            for (int i = 0; i <= sides; i++) {
                int a = m.vertex(p.r * cos[i], p.r * sin[i], p.z, nr * cos[i], nr * sin[i], nz, u, v);
                m.vertex(q.r * cos[i], q.r * sin[i], q.z, nr * cos[i], nr * sin[i], nz, u, v);
                if (i == 0) {
                    base = a;
                }
            }
            for (int i = 0; i < sides; i++) {
                int a = base + 2 * i;
                m.quad(a, a + 2, a + 3, a + 1);
            }
        }
        cap(m, rings.getFirst(), sides, -1, uv, cos, sin);
        cap(m, rings.getLast(), sides, 1, uv, cos, sin);
    }

    private static void cap(Mesh m, Ring r, int sides, int dir, Uv uv, float[] cos, float[] sin) {
        if (r.r < 1e-7f) {
            return;
        }
        float u = uv.u(r.rgb), v = uv.v(r.rgb);
        int c = m.vertex(0, 0, r.z, 0, 0, dir, u, v);
        int first = m.pos.size() / 3;
        for (int i = 0; i <= sides; i++) {
            m.vertex(r.r * cos[i], r.r * sin[i], r.z, 0, 0, dir, u, v);
        }
        for (int i = 0; i < sides; i++) {
            if (dir > 0) {
                m.idx.add(c);
                m.idx.add(first + i);
                m.idx.add(first + i + 1);
            } else {
                m.idx.add(c);
                m.idx.add(first + i + 1);
                m.idx.add(first + i);
            }
        }
    }

    /** {@code n} fins, radial span {@code r0..r1}, axial {@code z0..z1}, thickness {@code t}. */
    static void fins(Mesh m, int n, float r0, float r1, float z0, float z1, float t, int rgb, Uv uv) {
        float u = uv.u(rgb), v = uv.v(rgb);
        for (int k = 0; k < n; k++) {
            double a = 2 * Math.PI * k / n;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            box(m, r0, r1, -t / 2, t / 2, z0, z1, c, s, u, v);
        }
    }

    /** Box in (radial x, tangential y, z), rotated by (c, s) about Z. */
    private static void box(Mesh m, float x0, float x1, float y0, float y1, float z0, float z1, float c, float s,
                            float u, float v) {
        float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (float[] f : n) {
            float[][] q = new float[4][];
            int i = 0;
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    int bb = a == 0 ? b : 1 - b;
                    float x, y, z;
                    if (f[0] != 0) {
                        x = f[0] > 0 ? x1 : x0;
                        y = a == 0 ? y0 : y1;
                        z = bb == 0 ? z0 : z1;
                    } else if (f[1] != 0) {
                        y = f[1] > 0 ? y1 : y0;
                        x = a == 0 ? x0 : x1;
                        z = bb == 0 ? z0 : z1;
                    } else {
                        z = f[2] > 0 ? z1 : z0;
                        x = a == 0 ? x0 : x1;
                        y = bb == 0 ? y0 : y1;
                    }
                    q[i++] = new float[]{x, y, z};
                }
            }
            int first = -1;
            for (float[] p : q) {
                int id = m.vertex(p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2], f[0] * c - f[1] * s,
                        f[0] * s + f[1] * c, f[2], u, v);
                if (first < 0) {
                    first = id;
                }
            }
            float[] a0 = q[0], a1 = q[1], a2 = q[2];
            float cx = (a1[1] - a0[1]) * (a2[2] - a0[2]) - (a1[2] - a0[2]) * (a2[1] - a0[1]);
            float cy = (a1[2] - a0[2]) * (a2[0] - a0[0]) - (a1[0] - a0[0]) * (a2[2] - a0[2]);
            float cz = (a1[0] - a0[0]) * (a2[1] - a0[1]) - (a1[1] - a0[1]) * (a2[0] - a0[0]);
            if (cx * f[0] + cy * f[1] + cz * f[2] >= 0) {
                m.quad(first, first + 1, first + 2, first + 3);
            } else {
                m.quad(first, first + 3, first + 2, first + 1);
            }
        }
    }

    /**
     * Nose {@code z0 -> z1}: {@code r cos(t pi/2)^p}, {@code t = f (1 - meplat)}; last {@code tipFrac} tip-coloured.
     */
    static void ogive(List<Ring> out, float z0, float z1, float r, float p, float meplat, int body, int tip,
                      float tipFrac) {
        int steps = 8;
        float tipFrom = 1 - tipFrac;
        float prev = 0;
        for (int i = 1; i <= steps; i++) {
            float f = (float) i / steps;
            if (tip != body && prev < tipFrom && f > tipFrom) {
                float rt = radius(r, p, tipFrom * (1 - meplat));
                out.add(new Ring(z0 + (z1 - z0) * tipFrom, rt, body));
                out.add(new Ring(z0 + (z1 - z0) * tipFrom, rt, tip));
            }
            float rr = radius(r, p, f * (1 - meplat));
            out.add(new Ring(z0 + (z1 - z0) * f, i == steps ? Math.max(rr, r * 0.04f) : rr, f > tipFrom ? tip : body));
            prev = f;
        }
    }

    private static float radius(float r, float p, float t) {
        return (float) (r * Math.pow(Math.cos(t * Math.PI / 2), p));
    }

    /** Projectile in flight, blocks. */
    public static RigGeometry projectile(ProjectileLook look, Uv uv) {
        Mesh m = new Mesh();
        float l = look.lengthMm() * MM, r = look.diameterMm() * MM / 2;
        int body = look.body(), band = look.band(), tip = look.tip() < 0 ? body : look.tip();
        List<Ring> rs = new ArrayList<>();
        switch (look.shape()) {
            case BULLET -> {
                rs.add(new Ring(0, r * 0.78f, body));
                rs.add(new Ring(0.12f * l, r, body));
                rs.add(new Ring(0.5f * l, r, body));
                ogive(rs, 0.5f * l, l, r, 0.9f, 0.02f, body, tip, 0.3f);
            }
            case SHELL -> {
                rs.add(new Ring(0, r * 0.9f, body));
                rs.add(new Ring(0.05f * l, r, body));
                rs.add(new Ring(0.16f * l, r, body));
                rs.add(new Ring(0.16f * l, r * 1.04f, band));
                rs.add(new Ring(0.24f * l, r * 1.04f, band));
                rs.add(new Ring(0.24f * l, r, body));
                rs.add(new Ring(0.55f * l, r, body));
                ogive(rs, 0.55f * l, 0.92f * l, r, 0.8f, 0.25f, body, body, 0f);
                float rf = rs.getLast().r();
                rs.add(new Ring(rs.getLast().z(), rf, tip));
                rs.add(new Ring(l, rf * 0.55f, tip));
            }
            case GRENADE -> {
                rs.add(new Ring(0, r * 0.92f, body));
                rs.add(new Ring(0.04f * l, r, body));
                rs.add(new Ring(0.18f * l, r, body));
                rs.add(new Ring(0.18f * l, r * 1.03f, band));
                rs.add(new Ring(0.28f * l, r * 1.03f, band));
                rs.add(new Ring(0.28f * l, r, body));
                rs.add(new Ring(0.5f * l, r, body));
                ogive(rs, 0.5f * l, l, r, 0.55f, 0.0f, body, tip, 0.25f);
            }
            case DART -> {
                rs.add(new Ring(0, r * 0.9f, body));
                rs.add(new Ring(0.02f * l, r, body));
                rs.add(new Ring(0.86f * l, r, body));
                rs.add(new Ring(0.86f * l, r, tip));
                rs.add(new Ring(l, r * 0.15f, tip));
                fins(m, 6, r * 0.8f, r * 3.2f, 0.0f, 0.13f * l, r * 0.35f, band, uv);
            }
            case FINNED -> {
                float boom = r * 0.32f;
                rs.add(new Ring(0, boom, body));
                rs.add(new Ring(0.3f * l, boom, body));
                rs.add(new Ring(0.42f * l, r, body));
                rs.add(new Ring(0.62f * l, r, body));
                rs.add(new Ring(0.8f * l, r * 0.32f, body));
                rs.add(new Ring(0.8f * l, r * 0.13f, tip));
                rs.add(new Ring(l, r * 0.13f, tip));
                fins(m, 6, boom * 0.9f, r * 0.95f, 0.0f, 0.12f * l, r * 0.06f, band, uv);
            }
            case ROCKET -> {
                float tube = Math.min(r, 20 * MM);
                rs.add(new Ring(0, nozzle(look), ProjectileLook.DARK));
                rs.add(new Ring(0.03f * l, tube, body));
                rs.add(new Ring(0.55f * l, tube, body));
                rs.add(new Ring(0.62f * l, r, body));
                rs.add(new Ring(0.76f * l, r, body));
                ogive(rs, 0.76f * l, 0.95f * l, r, 0.9f, 0.18f, body, body, 0f);
                float rf = rs.getLast().r();
                rs.add(new Ring(rs.getLast().z(), rf * 0.7f, tip));
                rs.add(new Ring(l, rf * 0.5f, tip));
                fins(m, 4, tube * 0.95f, Math.max(r, tube * 2.1f), 0.01f * l, 0.12f * l, tube * 0.08f, band, uv);
            }
        }
        lathe(m, rs, PROJECTILE_SIDES, uv);
        return m.build();
    }

    /** Motor flame behind the nozzle (z <= 0), tip at {@code -FLAME_LENGTH x} nozzle radius. */
    public static RigGeometry flame(ProjectileLook look, Uv uv) {
        float rn = nozzle(look);
        float l = FLAME_LENGTH * rn;
        Mesh m = new Mesh();
        lathe(m, List.of(new Ring(-l, 0, FLAME_TIP), new Ring(-0.55f * l, 1.25f * rn, FLAME),
                new Ring(-0.12f * l, 1.1f * rn, FLAME_CORE), new Ring(0, rn, FLAME_CORE)), PROJECTILE_SIDES, uv);
        return m.build();
    }

    /** Rocket nozzle radius = the ring at z 0 of {@link ProjectileLook.Shape#ROCKET}. */
    static float nozzle(ProjectileLook look) {
        return Math.min(look.diameterMm() * MM / 2, 20 * MM) * 0.8f;
    }

    /** Cartridge: case (none for rockets) + the exposed projectile of {@code look} (bullet: ogive). */
    public static RigGeometry cartridge(Calibre c, ProjectileLook look, int bulletRgb, Uv uv) {
        if (!c.hasCase()) {
            return projectile(look, uv);
        }
        Mesh m = new Mesh();
        List<Ring> rs = new ArrayList<>();
        int caseRgb = c.caseMaterial().rgb;
        float len = c.caseLengthMm() * MM, oal = c.overallMm() * MM;
        float rim = c.rimMm() * MM / 2, head = c.caseMm() * MM / 2, neck = c.neckMm() * MM / 2;
        float b = c.bulletMm() * MM / 2;
        if (c.kind() == CalibreKind.SHOTSHELL) {
            float brass = len * 0.22f;
            int brassRgb = com.wf.wflib.ammo.CaseMaterial.BRASS.rgb;
            rs.add(new Ring(0, rim, brassRgb));
            rs.add(new Ring(len * 0.03f, rim, brassRgb));
            rs.add(new Ring(len * 0.03f, head, brassRgb));
            rs.add(new Ring(brass, head, brassRgb));
            rs.add(new Ring(brass, head * 0.99f, caseRgb));
            rs.add(new Ring(len * 0.97f, head * 0.99f, caseRgb));
            rs.add(new Ring(len, head * 0.8f, caseRgb));
            lathe(m, rs, CARTRIDGE_SIDES, uv);
            return m.build();
        }
        float rimLen = c.caseMm() * MM * 0.07f;
        rs.add(new Ring(0, rim, caseRgb));
        rs.add(new Ring(rimLen, rim, caseRgb));
        boolean rimless = rim < head * 1.05f;
        float z = rimLen;
        if (rimless) {
            float g = rimLen;
            rs.add(new Ring(z, head * 0.82f, caseRgb));
            rs.add(new Ring(z + g, head * 0.82f, caseRgb));
            z += g * 1.6f;
            rs.add(new Ring(z, head, caseRgb));
        } else {
            rs.add(new Ring(z, head, caseRgb));
        }
        boolean bottleneck = neck < head * 0.93f;
        if (bottleneck) {
            float neckLen = Math.min(c.bulletMm() * MM * 1.0f, len * 0.2f);
            float shoulderLen = (head - neck) * 1.3f;
            rs.add(new Ring(len - neckLen - shoulderLen, head * 0.97f, caseRgb));
            rs.add(new Ring(len - neckLen, neck, caseRgb));
        }
        rs.add(new Ring(len, neck, caseRgb));
        if (oal > len + 1e-6f) {
            nose(rs, m, c, look, len, oal, b, bulletRgb, uv);
        } else {
            rs.add(new Ring(len, b, caseRgb));
        }
        lathe(m, rs, CARTRIDGE_SIDES, uv);
        return m.build();
    }

    /** Exposed projectile from the case mouth. */
    private static void nose(List<Ring> rs, Mesh m, Calibre c, ProjectileLook look, float z0, float z1, float b,
                             int bulletRgb, Uv uv) {
        int tip = look.tip() < 0 ? (look.visible() ? look.body() : bulletRgb) : look.tip();
        float e = z1 - z0;
        switch (c.kind()) {
            case PISTOL, RIFLE, SHOTSHELL -> {
                boolean pistol = c.kind() == CalibreKind.PISTOL;
                rs.add(new Ring(z0, b, bulletRgb));
                rs.add(new Ring(z0 + e * (pistol ? 0.15f : 0.25f), b, bulletRgb));
                ogive(rs, z0 + e * (pistol ? 0.15f : 0.25f), z1, b, pistol ? 0.55f : 0.9f, pistol ? 0.12f : 0.02f,
                        bulletRgb, tip, 0.3f);
            }
            default -> {
                int body = look.body();
                if (look.shape() == ProjectileLook.Shape.DART) {
                    float rod = look.diameterMm() * MM / 2;
                    rs.add(new Ring(z0, b, look.band()));
                    rs.add(new Ring(z0 + e * 0.35f, b, look.band()));
                    rs.add(new Ring(z0 + e * 0.35f, rod, body));
                    rs.add(new Ring(z0 + e * 0.9f, rod, body));
                    rs.add(new Ring(z1, rod * 0.15f, tip));
                } else if (look.shape() == ProjectileLook.Shape.FINNED) {
                    rs.add(new Ring(z0, b, body));
                    rs.add(new Ring(z0 + e * 0.35f, b, body));
                    rs.add(new Ring(z0 + e * 0.75f, b * 0.32f, body));
                    rs.add(new Ring(z0 + e * 0.75f, b * 0.13f, tip));
                    rs.add(new Ring(z1, b * 0.13f, tip));
                } else {
                    rs.add(new Ring(z0, b * 1.03f, look.band()));
                    rs.add(new Ring(z0 + e * 0.08f, b * 1.03f, look.band()));
                    rs.add(new Ring(z0 + e * 0.08f, b, body));
                    rs.add(new Ring(z0 + e * 0.35f, b, body));
                    ogive(rs, z0 + e * 0.35f, z1, b, look.shape() == ProjectileLook.Shape.GRENADE ? 0.55f : 0.8f,
                            0.05f, body, tip, 0.2f);
                }
            }
        }
    }
}
