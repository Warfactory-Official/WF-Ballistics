package com.wf.wflib.util;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.*;
import org.joml.Math;

import java.util.List;
import java.util.Optional;

/**
 * Oriented bounding box. Based on @AnECanSaiTin's <a href="https://github.com/AnECanSaiTin/HitboxAPI">HitboxAPI</a>.
 *
 * <p>Centre authoritative in doubles, mirrored into {@link #center()} as float: float = 0.008 blocks at 1e5,
 * 0.0625 at 1e6, coarser than sweep skin / contact margin. World queries go through the doubles; write only via
 * {@link #setCenter} / {@link #translate}, never into the mirror.
 */
public final class OBB {

    private final Vector3f center;
    private final Vector3f extents;
    private final Quaternionf rotation;

    private double cx, cy, cz;

    public OBB(Vector3f center, Vector3f extents, Quaternionf rotation) {
        this.center = center;
        this.extents = extents;
        this.rotation = rotation;
        this.cx = center.x;
        this.cy = center.y;
        this.cz = center.z;
    }

    public Vector3f center() {
        return center;
    }

    public Vector3f extents() {
        return extents;
    }

    public Quaternionf rotation() {
        return rotation;
    }

    public double centerX() {
        return cx;
    }

    public double centerY() {
        return cy;
    }

    public double centerZ() {
        return cz;
    }

    public Vec3 centerVec() {
        return new Vec3(cx, cy, cz);
    }

    public void setCenter(Vector3f center) {
        setCenter(center.x, center.y, center.z);
    }

    public void setCenter(Vec3 center) {
        setCenter(center.x, center.y, center.z);
    }

    public void setCenter(double x, double y, double z) {
        this.cx = x;
        this.cy = y;
        this.cz = z;
        this.center.set((float) x, (float) y, (float) z);
    }

    public void setCenter(OBB source) {
        setCenter(source.cx, source.cy, source.cz);
    }

    public void translate(double dx, double dy, double dz) {
        setCenter(cx + dx, cy + dy, cz + dz);
    }

    public void setExtents(Vector3f extents) {
        this.extents.set(extents);
    }

    public void setExtents(float x, float y, float z) {
        this.extents.set(x, y, z);
    }

    public void setRotation(Quaternionf rotation) {
        this.rotation.set(rotation);
    }

    public float getSize() {
        return extents.x * 2 * extents.y * 2 * extents.z * 2;
    }

    /** World corners; float, so hull-relative use only. */
    public Vector3f[] getVertices() {
        Vector3f[] vertices = new Vector3f[]{
                new Vector3f(-extents.x, -extents.y, -extents.z),
                new Vector3f(extents.x, -extents.y, -extents.z),
                new Vector3f(extents.x, extents.y, -extents.z),
                new Vector3f(-extents.x, extents.y, -extents.z),
                new Vector3f(-extents.x, -extents.y, extents.z),
                new Vector3f(extents.x, -extents.y, extents.z),
                new Vector3f(extents.x, extents.y, extents.z),
                new Vector3f(-extents.x, extents.y, extents.z)
        };
        for (Vector3f vertex : vertices) {
            vertex.rotate(rotation);
            vertex.add(center);
        }
        return vertices;
    }

    /** Fresh array per call; callers may mutate it. */
    public Vector3f[] getAxes() {
        Vector3f[] axes = new Vector3f[]{
                new Vector3f(1, 0, 0),
                new Vector3f(0, 1, 0),
                new Vector3f(0, 0, 1)};
        rotation.transform(axes[0]);
        rotation.transform(axes[1]);
        rotation.transform(axes[2]);
        return axes;
    }

    /** Enclosing world AABB, from double centre + projected half sizes. */
    public AABB bounds() {
        Vector3f[] ax = getAxes();
        double hx = extents.x * Math.abs(ax[0].x) + extents.y * Math.abs(ax[1].x) + extents.z * Math.abs(ax[2].x);
        double hy = extents.x * Math.abs(ax[0].y) + extents.y * Math.abs(ax[1].y) + extents.z * Math.abs(ax[2].y);
        double hz = extents.x * Math.abs(ax[0].z) + extents.y * Math.abs(ax[1].z) + extents.z * Math.abs(ax[2].z);
        return new AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    public static boolean isColliding(OBB obb, OBB other) {
        Vector3f[] axes1 = obb.getAxes();
        Vector3f[] axes2 = other.getAxes();
        Vector3f relative = new Vector3f((float) (other.cx - obb.cx), (float) (other.cy - obb.cy),
                (float) (other.cz - obb.cz));
        return Intersectionf.testObOb(new Vector3f(), axes1[0], axes1[1], axes1[2], obb.extents(),
                relative, axes2[0], axes2[1], axes2[2], other.extents());
    }

    public static boolean isColliding(OBB obb, AABB aabb) {
        Vector3f[] obbAxes = obb.getAxes();
        Vector3f obbHalfExtents = obb.extents();
        Vector3f aabbCenter = obb.relative(aabb);
        Vector3f aabbHalfExtents = new Vector3f((float) (aabb.getXsize() / 2f), (float) (aabb.getYsize() / 2f),
                (float) (aabb.getZsize() / 2f));
        return Intersectionf.testObOb(
                0, 0, 0,
                obbAxes[0].x, obbAxes[0].y, obbAxes[0].z,
                obbAxes[1].x, obbAxes[1].y, obbAxes[1].z,
                obbAxes[2].x, obbAxes[2].y, obbAxes[2].z,
                obbHalfExtents.x, obbHalfExtents.y, obbHalfExtents.z,
                aabbCenter.x, aabbCenter.y, aabbCenter.z,
                1, 0, 0,
                0, 1, 0,
                0, 0, 1,
                aabbHalfExtents.x, aabbHalfExtents.y, aabbHalfExtents.z
        );
    }

    /**
     * SAT penetration of a, displaced by (dx, dy, dz), against b. Centres subtracted in double.
     * @param out unit axis of least overlap, pointing a -> b
     * @return depth along out; <= 0 => separated
     */
    public static float mtv(OBB a, double dx, double dy, double dz, OBB b, Vector3f out) {
        Vector3f[] axesA = a.getAxes();
        Vector3f[] axesB = b.getAxes();
        Vector3f d = new Vector3f((float) (b.cx - a.cx - dx), (float) (b.cy - a.cy - dy), (float) (b.cz - a.cz - dz));
        Vector3f axis = new Vector3f();
        float best = Float.MAX_VALUE;
        for (int i = 0; i < 15; i++) {
            if (i < 3) {
                axis.set(axesA[i]);
            } else if (i < 6) {
                axis.set(axesB[i - 3]);
            } else {
                axesA[(i - 6) / 3].cross(axesB[(i - 6) % 3], axis);
                float length = axis.length();
                // Parallel edges: face axes already cover this direction.
                if (length < 1.0e-4f) {
                    continue;
                }
                axis.div(length);
            }
            float overlap = radius(axis, axesA, a.extents) + radius(axis, axesB, b.extents) - Math.abs(d.dot(axis));
            if (overlap <= 0) {
                return overlap;
            }
            if (overlap < best) {
                best = overlap;
                out.set(axis);
            }
        }
        if (out.dot(d) < 0) {
            out.negate();
        }
        return best;
    }

    private static float radius(Vector3f axis, Vector3f[] axes, Vector3f extents) {
        return Math.abs(axis.dot(axes[0])) * extents.x + Math.abs(axis.dot(axes[1])) * extents.y
                + Math.abs(axis.dot(axes[2])) * extents.z;
    }

    private Vector3f relative(AABB aabb) {
        return new Vector3f((float) ((aabb.minX + aabb.maxX) * 0.5 - cx), (float) ((aabb.minY + aabb.maxY) * 0.5 - cy),
                (float) ((aabb.minZ + aabb.maxZ) * 0.5 - cz));
    }

    public static AABB toAABB(List<OBB> obbs) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (OBB obb : obbs) {
            AABB b = obb.bounds();
            minX = java.lang.Math.min(minX, b.minX);
            minY = java.lang.Math.min(minY, b.minY);
            minZ = java.lang.Math.min(minZ, b.minZ);
            maxX = java.lang.Math.max(maxX, b.maxX);
            maxY = java.lang.Math.max(maxY, b.maxY);
            maxZ = java.lang.Math.max(maxZ, b.maxZ);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static boolean intersectsBox(Matrix3f basis, float cx, float cy, float cz,
                                        Vector3f extents,
                                        double minX, double minY, double minZ,
                                        double maxX, double maxY, double maxZ) {
        return Intersectionf.testObOb(
                cx, cy, cz,
                basis.m00(), basis.m01(), basis.m02(),
                basis.m10(), basis.m11(), basis.m12(),
                basis.m20(), basis.m21(), basis.m22(),
                extents.x, extents.y, extents.z,
                (float) ((minX + maxX) * 0.5), (float) ((minY + maxY) * 0.5),
                (float) ((minZ + maxZ) * 0.5),
                1, 0, 0,
                0, 1, 0,
                0, 0, 1,
                (float) ((maxX - minX) * 0.5), (float) ((maxY - minY) * 0.5),
                (float) ((maxZ - minZ) * 0.5));
    }

    /** Per-pose OBB-vs-AABB SAT precomputation, reused across boxes. */
    public static final class SatFrame {

        private static final float EPS = 1.0e-6f;

        /** Most the EPS nudge widens a projected radius on any axis, in blocks. */
        public static float inflation(Vector3f extents) {
            return EPS * (extents.x + extents.y + extents.z);
        }

        private final Matrix3f basis = new Matrix3f();

        /** r[i][j] = component i of hull axis j. */
        public float r00, r01, r02, r10, r11, r12, r20, r21, r22;
        /** |r| + EPS: parallel axes stay conservative. */
        public float a00, a01, a02, a10, a11, a12, a20, a21, a22;

        public SatFrame set(Quaternionf rotation) {
            Matrix3f m = rotation.get(basis);
            r00 = m.m00(); r01 = m.m10(); r02 = m.m20();
            r10 = m.m01(); r11 = m.m11(); r12 = m.m21();
            r20 = m.m02(); r21 = m.m12(); r22 = m.m22();
            a00 = Math.abs(r00) + EPS; a01 = Math.abs(r01) + EPS; a02 = Math.abs(r02) + EPS;
            a10 = Math.abs(r10) + EPS; a11 = Math.abs(r11) + EPS; a12 = Math.abs(r12) + EPS;
            a20 = Math.abs(r20) + EPS; a21 = Math.abs(r21) + EPS; a22 = Math.abs(r22) + EPS;
            return this;
        }

        public Matrix3f basis() {
            return basis;
        }
    }

    public static boolean intersectsBox(SatFrame f, double cx, double cy, double cz,
                                        Vector3f extents,
                                        double minX, double minY, double minZ,
                                        double maxX, double maxY, double maxZ) {
        float h0 = (float) ((maxX - minX) * 0.5);
        float h1 = (float) ((maxY - minY) * 0.5);
        float h2 = (float) ((maxZ - minZ) * 0.5);
        // Subtract in double, narrow after: world-sized terms cancel.
        float t0 = (float) ((minX + maxX) * 0.5 - cx);
        float t1 = (float) ((minY + maxY) * 0.5 - cy);
        float t2 = (float) ((minZ + maxZ) * 0.5 - cz);
        float e0 = extents.x;
        float e1 = extents.y;
        float e2 = extents.z;

        if (Math.abs(t0) > h0 + e0 * f.a00 + e1 * f.a01 + e2 * f.a02) return false;
        if (Math.abs(t1) > h1 + e0 * f.a10 + e1 * f.a11 + e2 * f.a12) return false;
        if (Math.abs(t2) > h2 + e0 * f.a20 + e1 * f.a21 + e2 * f.a22) return false;

        if (Math.abs(t0 * f.r00 + t1 * f.r10 + t2 * f.r20)
                > e0 + h0 * f.a00 + h1 * f.a10 + h2 * f.a20) return false;
        if (Math.abs(t0 * f.r01 + t1 * f.r11 + t2 * f.r21)
                > e1 + h0 * f.a01 + h1 * f.a11 + h2 * f.a21) return false;
        if (Math.abs(t0 * f.r02 + t1 * f.r12 + t2 * f.r22)
                > e2 + h0 * f.a02 + h1 * f.a12 + h2 * f.a22) return false;

        if (Math.abs(t2 * f.r10 - t1 * f.r20)
                > h1 * f.a20 + h2 * f.a10 + e1 * f.a02 + e2 * f.a01) return false;
        if (Math.abs(t2 * f.r11 - t1 * f.r21)
                > h1 * f.a21 + h2 * f.a11 + e2 * f.a00 + e0 * f.a02) return false;
        if (Math.abs(t2 * f.r12 - t1 * f.r22)
                > h1 * f.a22 + h2 * f.a12 + e0 * f.a01 + e1 * f.a00) return false;
        if (Math.abs(t0 * f.r20 - t2 * f.r00)
                > h2 * f.a00 + h0 * f.a20 + e1 * f.a12 + e2 * f.a11) return false;
        if (Math.abs(t0 * f.r21 - t2 * f.r01)
                > h2 * f.a01 + h0 * f.a21 + e2 * f.a10 + e0 * f.a12) return false;
        if (Math.abs(t0 * f.r22 - t2 * f.r02)
                > h2 * f.a02 + h0 * f.a22 + e0 * f.a11 + e1 * f.a10) return false;
        if (Math.abs(t1 * f.r00 - t0 * f.r10)
                > h0 * f.a10 + h1 * f.a00 + e1 * f.a22 + e2 * f.a21) return false;
        if (Math.abs(t1 * f.r01 - t0 * f.r11)
                > h0 * f.a11 + h1 * f.a01 + e2 * f.a20 + e0 * f.a22) return false;
        return !(Math.abs(t1 * f.r02 - t0 * f.r12)
                > h0 * f.a12 + h1 * f.a02 + e0 * f.a21 + e1 * f.a20);
    }

    public static Vector3f getClosestPointOBB(Vector3f point, OBB obb) {
        Vector3f nearP = new Vector3f(obb.center());
        Vector3f dist = point.sub(nearP, new Vector3f());
        float[] extents = new float[]{obb.extents().x, obb.extents().y, obb.extents().z};
        Vector3f[] axes = obb.getAxes();
        for (int i = 0; i < 3; i++) {
            float distance = Math.clamp(dist.dot(axes[i]), -extents[i], extents[i]);
            nearP.x += distance * axes[i].x;
            nearP.y += distance * axes[i].y;
            nearP.z += distance * axes[i].z;
        }
        return nearP;
    }

    /** Float endpoints: coarse far from origin. Prefer {@link #clip(Vec3, Vec3)} / {@link #clipFraction}. */
    public Optional<Vector3f> clip(Vector3f pFrom, Vector3f pTo) {
        Vector3f[] axes = getAxes();
        Vector3f localFrom = worldToLocal(pFrom, axes);
        Vector3f localTo = worldToLocal(pTo, axes);
        Vector3f dir = new Vector3f(localTo).sub(localFrom);
        double tEnter = 0.0;
        double tExit = 1.0;
        for (int i = 0; i < 3; i++) {
            double min = -extents.get(i);
            double max = extents.get(i);
            double origin = localFrom.get(i);
            double direction = dir.get(i);
            if (Math.abs(direction) < 1e-7f) {
                if (origin < min || origin > max) {
                    return Optional.empty();
                }
                continue;
            }
            double t1 = (min - origin) / direction;
            double t2 = (max - origin) / direction;
            tEnter = java.lang.Math.max(tEnter, java.lang.Math.min(t1, t2));
            tExit = java.lang.Math.min(tExit, java.lang.Math.max(t1, t2));
            if (tEnter > tExit) {
                return Optional.empty();
            }
        }
        Vector3f localHit = new Vector3f(dir).mul((float) tEnter).add(localFrom);
        return Optional.of(localToWorld(localHit, axes));
    }

    /** First point of {@code from -> to} in this box (start if inside); null = miss. */
    @Nullable
    public Vec3 clip(Vec3 from, Vec3 to) {
        double t = clipFraction(from.x, from.y, from.z, to.x, to.y, to.z, getAxes());
        return t < 0 ? null : from.add(to.subtract(from).scale(t));
    }

    /**
     * Entry fraction of a segment in [0, 1], or -1 on a miss. Doubles throughout: at 1e6 a float
     * subtraction is coarser than a block.
     */
    public double clipFraction(double fromX, double fromY, double fromZ,
                               double toX, double toY, double toZ, Vector3f[] axes) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double rx = fromX - cx;
        double ry = fromY - cy;
        double rz = fromZ - cz;
        double enter = 0;
        double exit = 1;
        for (int i = 0; i < 3; i++) {
            Vector3f axis = axes[i];
            double origin = rx * axis.x + ry * axis.y + rz * axis.z;
            double direction = dx * axis.x + dy * axis.y + dz * axis.z;
            double extent = extents.get(i);
            if (Math.abs(direction) < 1.0e-12) {
                if (origin < -extent || origin > extent) {
                    return -1;
                }
                continue;
            }
            double first = (-extent - origin) / direction;
            double last = (extent - origin) / direction;
            if (first > last) {
                double swap = first;
                first = last;
                last = swap;
            }
            if (first > enter) {
                enter = first;
            }
            if (last < exit) {
                exit = last;
            }
            if (enter > exit) {
                return -1;
            }
        }
        return enter;
    }

    public Vector3f worldToLocal(Vector3f worldPoint, Vector3f[] axes) {
        return worldToLocal(worldPoint, axes, new Vector3f());
    }

    /** dest may alias worldPoint. */
    public Vector3f worldToLocal(Vector3f worldPoint, Vector3f[] axes, Vector3f dest) {
        float rx = worldPoint.x - center.x;
        float ry = worldPoint.y - center.y;
        float rz = worldPoint.z - center.z;
        dest.set(
                rx * axes[0].x + ry * axes[0].y + rz * axes[0].z,
                rx * axes[1].x + ry * axes[1].y + rz * axes[1].z,
                rx * axes[2].x + ry * axes[2].y + rz * axes[2].z);
        return dest;
    }

    public Vector3f worldToLocal(double wx, double wy, double wz, Vector3f[] axes, Vector3f dest) {
        float rx = (float) (wx - cx);
        float ry = (float) (wy - cy);
        float rz = (float) (wz - cz);
        dest.set(
                rx * axes[0].x + ry * axes[0].y + rz * axes[0].z,
                rx * axes[1].x + ry * axes[1].y + rz * axes[1].z,
                rx * axes[2].x + ry * axes[2].y + rz * axes[2].z);
        return dest;
    }

    public Vector3f localToWorld(Vector3f localPoint, Vector3f[] axes) {
        return localToWorld(localPoint, axes, new Vector3f());
    }

    /** Full-precision centre; rotated offset float (hull-sized). */
    public void localToWorld(Vector3f localPoint, Vector3f[] axes, double[] dest) {
        float lx = localPoint.x, ly = localPoint.y, lz = localPoint.z;
        dest[0] = cx + (axes[0].x * lx + axes[1].x * ly + axes[2].x * lz);
        dest[1] = cy + (axes[0].y * lx + axes[1].y * ly + axes[2].y * lz);
        dest[2] = cz + (axes[0].z * lx + axes[1].z * ly + axes[2].z * lz);
    }

    public Vector3f localToWorld(Vector3f localPoint, Vector3f[] axes, Vector3f dest) {
        float lx = localPoint.x, ly = localPoint.y, lz = localPoint.z;
        dest.set(
                center.x + axes[0].x * lx + axes[1].x * ly + axes[2].x * lz,
                center.y + axes[0].y * lx + axes[1].y * ly + axes[2].y * lz,
                center.z + axes[0].z * lx + axes[1].z * ly + axes[2].z * lz
        );
        return dest;
    }

    private Vector3f relative(Vec3 point) {
        return new Vector3f((float) (point.x - cx), (float) (point.y - cy), (float) (point.z - cz));
    }

    public OBB inflate(float amount) {
        return inflate(amount, amount, amount);
    }

    public OBB inflate(float x, float y, float z) {
        OBB inflated = new OBB(new Vector3f(center), new Vector3f(extents).add(x, y, z), rotation);
        inflated.setCenter(cx, cy, cz);
        return inflated;
    }

    public OBB move(Vec3 vec3) {
        OBB moved = new OBB(new Vector3f(center), extents, rotation);
        moved.setCenter(cx + vec3.x, cy + vec3.y, cz + vec3.z);
        return moved;
    }

    public boolean contains(Vec3 vec3) {
        Vector3f rel = relative(vec3);
        Vector3f[] axes = getAxes();
        return Math.abs(rel.dot(axes[0])) <= extents.x
                && Math.abs(rel.dot(axes[1])) <= extents.y
                && Math.abs(rel.dot(axes[2])) <= extents.z;
    }

    public double embeddingDepth(Vec3 vec3) {
        Vector3f rel = relative(vec3);
        Vector3f[] axes = getAxes();
        float projX = Math.abs(rel.dot(axes[0]));
        float projY = Math.abs(rel.dot(axes[1]));
        float projZ = Math.abs(rel.dot(axes[2]));
        return Math.min(extents.x - projX, Math.min(extents.y - projY, extents.z - projZ));
    }

    /** Signed 1-based axis index of the face nearest {@code vec3}. */
    public int embeddingFace(Vec3 vec3) {
        Vector3f rel = relative(vec3);
        Vector3f[] axes = getAxes();
        float dx = extents.x - Math.abs(rel.dot(axes[0]));
        float dy = extents.y - Math.abs(rel.dot(axes[1]));
        float dz = extents.z - Math.abs(rel.dot(axes[2]));
        float min = Float.MAX_VALUE;
        int index = 0;
        if (dx < min) {
            index = 1;
            min = dx;
        }
        if (dy < min) {
            index = 2;
            min = dy;
        }
        if (dz < min) {
            index = 3;
        }
        return (rel.dot(axes[index - 1]) < 0 ? -1 : 1) * index;
    }

    /** MTV from this box toward {@code aabb}; zero when separated. */
    public Vector3f calculateMTV(AABB aabb) {
        Vector3f aabbCenter = relative(aabb);
        Vector3f origin = new Vector3f();
        Vector3f aabbExtents = new Vector3f((float) aabb.getXsize() / 2f, (float) aabb.getYsize() / 2f,
                (float) aabb.getZsize() / 2f);
        Vector3f[] axesOBB = this.getAxes();
        Vector3f[] axesAABB = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
        Vector3f[] testAxes = new Vector3f[15];
        System.arraycopy(axesAABB, 0, testAxes, 0, 3);
        System.arraycopy(axesOBB, 0, testAxes, 3, 3);
        int count = 6;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                testAxes[count++] = new Vector3f(axesAABB[i]).cross(axesOBB[j]);
            }
        }
        float minOverlap = Float.MAX_VALUE;
        Vector3f mtvAxis = new Vector3f();
        for (Vector3f axis : testAxes) {
            if (axis.lengthSquared() < 1e-6f) {
                continue;
            }
            axis.normalize();
            float overlap = getOverlap(axis, aabbCenter, aabbExtents, origin, this.extents(), axesOBB);
            if (overlap <= 0) {
                return new Vector3f(0);
            }
            if (overlap < minOverlap) {
                minOverlap = overlap;
                mtvAxis.set(axis);
            }
        }
        if (aabbCenter.dot(mtvAxis) < 0) {
            mtvAxis.negate();
        }
        return mtvAxis.mul(minOverlap);
    }

    /** {@link #calculateMTV} against a box given by bounds, pose from a {@link SatFrame}; returns depth. */
    public static float mtv(SatFrame f, double cx, double cy, double cz, Vector3f extents,
                            double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ, Vector3f dest) {
        float h0 = (float) ((maxX - minX) * 0.5);
        float h1 = (float) ((maxY - minY) * 0.5);
        float h2 = (float) ((maxZ - minZ) * 0.5);
        float t0 = (float) ((minX + maxX) * 0.5 - cx);
        float t1 = (float) ((minY + maxY) * 0.5 - cy);
        float t2 = (float) ((minZ + maxZ) * 0.5 - cz);
        float e0 = extents.x;
        float e1 = extents.y;
        float e2 = extents.z;

        float best = Float.MAX_VALUE;
        float bestX = 0, bestY = 0, bestZ = 0;

        float o = h0 + e0 * f.a00 + e1 * f.a01 + e2 * f.a02 - Math.abs(t0);
        if (o <= 0) { dest.set(0); return 0; }
        if (o < best) { best = o; bestX = 1; bestY = 0; bestZ = 0; }
        o = h1 + e0 * f.a10 + e1 * f.a11 + e2 * f.a12 - Math.abs(t1);
        if (o <= 0) { dest.set(0); return 0; }
        if (o < best) { best = o; bestX = 0; bestY = 1; bestZ = 0; }
        o = h2 + e0 * f.a20 + e1 * f.a21 + e2 * f.a22 - Math.abs(t2);
        if (o <= 0) { dest.set(0); return 0; }
        if (o < best) { best = o; bestX = 0; bestY = 0; bestZ = 1; }

        o = e0 + h0 * f.a00 + h1 * f.a10 + h2 * f.a20
                - Math.abs(t0 * f.r00 + t1 * f.r10 + t2 * f.r20);
        if (o <= 0) { dest.set(0); return 0; }
        if (o < best) { best = o; bestX = f.r00; bestY = f.r10; bestZ = f.r20; }
        o = e1 + h0 * f.a01 + h1 * f.a11 + h2 * f.a21
                - Math.abs(t0 * f.r01 + t1 * f.r11 + t2 * f.r21);
        if (o <= 0) { dest.set(0); return 0; }
        if (o < best) { best = o; bestX = f.r01; bestY = f.r11; bestZ = f.r21; }
        o = e2 + h0 * f.a02 + h1 * f.a12 + h2 * f.a22
                - Math.abs(t0 * f.r02 + t1 * f.r12 + t2 * f.r22);
        if (o <= 0) { dest.set(0); return 0; }
        if (o < best) { best = o; bestX = f.r02; bestY = f.r12; bestZ = f.r22; }

        // Cross axes: depth = overlap / axis length.
        for (int j = 0; j < 3; j++) {
            float r0j = j == 0 ? f.r00 : j == 1 ? f.r01 : f.r02;
            float r1j = j == 0 ? f.r10 : j == 1 ? f.r11 : f.r12;
            float r2j = j == 0 ? f.r20 : j == 1 ? f.r21 : f.r22;
            float a0j = j == 0 ? f.a00 : j == 1 ? f.a01 : f.a02;
            float a1j = j == 0 ? f.a10 : j == 1 ? f.a11 : f.a12;
            float a2j = j == 0 ? f.a20 : j == 1 ? f.a21 : f.a22;
            int j1 = (j + 1) % 3;
            int j2 = (j + 2) % 3;
            float ej1 = j1 == 0 ? e0 : j1 == 1 ? e1 : e2;
            float ej2 = j2 == 0 ? e0 : j2 == 1 ? e1 : e2;

            float lenSq = r1j * r1j + r2j * r2j;
            if (lenSq > 1.0e-6f) {
                o = h1 * a2j + h2 * a1j + ej1 * rowAbs(f, 0, j2) + ej2 * rowAbs(f, 0, j1) - Math.abs(t2 * r1j - t1 * r2j);
                if (o <= 0) { dest.set(0); return 0; }
                float len = (float) java.lang.Math.sqrt(lenSq);
                float depth = o / len;
                if (depth < best) { best = depth; bestX = 0; bestY = -r2j / len; bestZ = r1j / len; }
            }
            lenSq = r0j * r0j + r2j * r2j;
            if (lenSq > 1.0e-6f) {
                o = h2 * a0j + h0 * a2j + ej1 * rowAbs(f, 1, j2) + ej2 * rowAbs(f, 1, j1) - Math.abs(t0 * r2j - t2 * r0j);
                if (o <= 0) { dest.set(0); return 0; }
                float len = (float) java.lang.Math.sqrt(lenSq);
                float depth = o / len;
                if (depth < best) { best = depth; bestX = r2j / len; bestY = 0; bestZ = -r0j / len; }
            }
            lenSq = r0j * r0j + r1j * r1j;
            if (lenSq > 1.0e-6f) {
                o = h0 * a1j + h1 * a0j + ej1 * rowAbs(f, 2, j2) + ej2 * rowAbs(f, 2, j1) - Math.abs(t1 * r0j - t0 * r1j);
                if (o <= 0) { dest.set(0); return 0; }
                float len = (float) java.lang.Math.sqrt(lenSq);
                float depth = o / len;
                if (depth < best) { best = depth; bestX = -r1j / len; bestY = r0j / len; bestZ = 0; }
            }
        }

        if (t0 * bestX + t1 * bestY + t2 * bestZ < 0) {
            bestX = -bestX;
            bestY = -bestY;
            bestZ = -bestZ;
        }
        dest.set(bestX * best, bestY * best, bestZ * best);
        return best;
    }

    private static float rowAbs(SatFrame f, int i, int j) {
        return i == 0 ? (j == 0 ? f.a00 : j == 1 ? f.a01 : f.a02)
                : i == 1 ? (j == 0 ? f.a10 : j == 1 ? f.a11 : f.a12)
                : (j == 0 ? f.a20 : j == 1 ? f.a21 : f.a22);
    }

    private float getOverlap(Vector3f axis, Vector3f c1, Vector3f e1, Vector3f c2, Vector3f e2, Vector3f[] axes2) {
        float r1 = Math.abs(axis.x) * e1.x + Math.abs(axis.y) * e1.y + Math.abs(axis.z) * e1.z;
        float r2 = Math.abs(axis.dot(axes2[0])) * e2.x + Math.abs(axis.dot(axes2[1])) * e2.y
                + Math.abs(axis.dot(axes2[2])) * e2.z;
        float distance = Math.abs(new Vector3f(c1).sub(c2).dot(axis));
        return (r1 + r2) - distance;
    }

    public OBB copy() {
        OBB copy = new OBB(new Vector3f(center), new Vector3f(extents), new Quaternionf(rotation));
        copy.setCenter(cx, cy, cz);
        return copy;
    }
}
