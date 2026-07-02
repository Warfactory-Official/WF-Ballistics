package com.wf.wfballistics.util;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Intersectiond;
import org.joml.Math;
import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;

import java.util.Optional;

/**
 * Oriented bounding box. Ported from SuperbWarfare's {@code com.atsuishio.superbwarfare.tools.OBB},
 * which is itself based on <a href="https://github.com/AnECanSaiTin/HitboxAPI">HitboxAPI</a>.
 *
 * <p>A box defined by a world-space {@code center}, per-axis half-lengths {@code extents}, and a
 * {@code rotation}. Used to give elongated entities (missiles) an accurate, orientation-aware hitbox
 * instead of the coarse vanilla AABB.
 *
 * @param center   center of the box in world space
 * @param extents  half-length along each local axis
 * @param rotation orientation of the box
 * @param part     which sub-part of the entity this box represents
 */
public record OBB(Vector3d center, Vector3d extents, Quaterniond rotation, Part part) {

    public void setCenter(Vector3d center) {
        this.center.set(center);
    }

    public void setExtents(Vector3d extents) {
        this.extents.set(extents);
    }

    public void updateRotation(Quaterniond rotation) {
        this.rotation.set(rotation);
    }

    /**
     * Face of the box that the given point is closest to escaping through (signed by axis direction).
     */
    public int getEmbeddingFace(Vec3 vec3) {
        Vector3d rel = vec3ToVector3d(vec3).sub(center);

        Vector3d[] axes = new Vector3d[3];
        axes[0] = rotation.transform(new Vector3d(1, 0, 0));
        axes[1] = rotation.transform(new Vector3d(0, 1, 0));
        axes[2] = rotation.transform(new Vector3d(0, 0, 1));

        double projX = Math.abs(rel.dot(axes[0]));
        double projY = Math.abs(rel.dot(axes[1]));
        double projZ = Math.abs(rel.dot(axes[2]));

        double min = Double.MAX_VALUE;
        int index = 0;

        double dx = extents.x - projX;
        double dy = extents.y - projY;
        double dz = extents.z - projZ;

        if (dx < min) {
            min = dx;
            index = 1;
        }
        if (dy < min) {
            min = dy;
            index = 2;
        }
        if (dz < min) {
            index = 3;
        }

        return index * (rel.dot(axes[index - 1]) < 0 ? -1 : 1);
    }

    public double getEmbeddingDepth(Vec3 vec3) {
        Vector3d rel = vec3ToVector3d(vec3).sub(center);

        Vector3d[] axes = new Vector3d[3];
        axes[0] = rotation.transform(new Vector3d(1, 0, 0));
        axes[1] = rotation.transform(new Vector3d(0, 1, 0));
        axes[2] = rotation.transform(new Vector3d(0, 0, 1));

        double projX = Math.abs(rel.dot(axes[0]));
        double projY = Math.abs(rel.dot(axes[1]));
        double projZ = Math.abs(rel.dot(axes[2]));

        double dx = extents.x - projX;
        double dy = extents.y - projY;
        double dz = extents.z - projZ;

        double minDepth = Double.MAX_VALUE;

        if (Math.abs(dx) < Math.abs(minDepth)) {
            minDepth = dx;
        }
        if (Math.abs(dy) < Math.abs(minDepth)) {
            minDepth = dy;
        }
        if (Math.abs(dz) < Math.abs(minDepth)) {
            minDepth = dz;
        }

        return minDepth;
    }

    /**
     * @return the 8 world-space corners of the box.
     */
    public Vector3d[] getVertices() {
        Vector3d[] vertices = new Vector3d[8];

        Vector3d[] localVertices = new Vector3d[]{
                new Vector3d(-extents.x, -extents.y, -extents.z),
                new Vector3d(extents.x, -extents.y, -extents.z),
                new Vector3d(extents.x, extents.y, -extents.z),
                new Vector3d(-extents.x, extents.y, -extents.z),
                new Vector3d(-extents.x, -extents.y, extents.z),
                new Vector3d(extents.x, -extents.y, extents.z),
                new Vector3d(extents.x, extents.y, extents.z),
                new Vector3d(-extents.x, extents.y, extents.z)
        };

        for (int i = 0; i < 8; i++) {
            Vector3d vertex = localVertices[i];
            vertex.rotate(rotation);
            vertex.add(center);
            vertices[i] = vertex;
        }

        return vertices;
    }

    /**
     * @return the three world-space orthogonal axes of the box.
     */
    public Vector3d[] getAxes() {
        Vector3d[] axes = new Vector3d[]{
                new Vector3d(1, 0, 0),
                new Vector3d(0, 1, 0),
                new Vector3d(0, 0, 1)};
        rotation.transform(axes[0]);
        rotation.transform(axes[1]);
        rotation.transform(axes[2]);
        return axes;
    }

    /**
     * Separating-axis test between two OBBs.
     */
    public static boolean isColliding(OBB obb, OBB other) {
        Vector3d[] axes1 = obb.getAxes();
        Vector3d[] axes2 = other.getAxes();
        return Intersectiond.testObOb(obb.center(), axes1[0], axes1[1], axes1[2], obb.extents(),
                other.center(), axes2[0], axes2[1], axes2[2], other.extents());
    }

    /**
     * Separating-axis test between an OBB and an axis-aligned box.
     */
    public static boolean isColliding(OBB obb, AABB aabb) {
        Vector3d obbCenter = obb.center();
        Vector3d[] obbAxes = obb.getAxes();
        Vector3d obbHalfExtents = obb.extents();
        Vector3d aabbCenter = vec3ToVector3d(aabb.getCenter());
        Vector3d aabbHalfExtents = new Vector3d(aabb.getXsize() / 2, aabb.getYsize() / 2f, aabb.getZsize() / 2f);
        return Intersectiond.testObOb(
                obbCenter.x, obbCenter.y, obbCenter.z,
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
     * @return the point on/inside the OBB closest to {@code point}.
     */
    public static Vector3d getClosestPointOBB(Vector3d point, OBB obb) {
        Vector3d nearP = new Vector3d(obb.center());
        Vector3d dist = point.sub(nearP, new Vector3d());

        double[] extents = new double[]{obb.extents().x, obb.extents().y, obb.extents().z};
        Vector3d[] axes = obb.getAxes();

        for (int i = 0; i < 3; i++) {
            double distance = dist.dot(axes[i]);
            distance = Math.clamp(distance, -extents[i], extents[i]);

            nearP.x += distance * axes[i].x;
            nearP.y += distance * axes[i].y;
            nearP.z += distance * axes[i].z;
        }

        return nearP;
    }

    /**
     * Clips the segment {@code pFrom -> pTo} against the box, returning the entry point (world space)
     * if the segment intersects the box. Uses the slab algorithm in the box's local frame.
     */
    public Optional<Vector3d> clip(Vector3d pFrom, Vector3d pTo) {
        // Local basis vectors of the box (world-space directions).
        Vector3d[] axes = new Vector3d[3];
        axes[0] = rotation.transform(new Vector3d(1, 0, 0));
        axes[1] = rotation.transform(new Vector3d(0, 1, 0));
        axes[2] = rotation.transform(new Vector3d(0, 0, 1));

        // Bring the endpoints into the box's local frame.
        Vector3d localFrom = worldToLocal(pFrom, axes);
        Vector3d localTo = worldToLocal(pTo, axes);

        // Ray direction in the local frame.
        Vector3d dir = new Vector3d(localTo).sub(localFrom);

        // Slab parameters.
        double tEnter = 0;
        double tExit = 1;

        for (int i = 0; i < 3; i++) {
            double min = -extents.get(i);
            double max = extents.get(i);
            double origin = localFrom.get(i);
            double direction = dir.get(i);

            // Ray parallel to this axis' slab.
            if (Math.abs(direction) < 1e-7f) {
                if (origin < min || origin > max) {
                    return Optional.empty();
                }
                continue;
            }

            double t1 = (min - origin) / direction;
            double t2 = (max - origin) / direction;

            double tNear = Math.min(t1, t2);
            double tFar = Math.max(t1, t2);

            if (tNear > tEnter) tEnter = tNear;
            if (tFar < tExit) tExit = tFar;

            if (tEnter > tExit) {
                return Optional.empty();
            }
        }

        // Intersection point in the local frame, converted back to world space.
        Vector3d localHit = new Vector3d(dir).mul(tEnter).add(localFrom);
        return Optional.of(localToWorld(localHit, axes));
    }

    // world -> local
    private Vector3d worldToLocal(Vector3d worldPoint, Vector3d[] axes) {
        Vector3d rel = new Vector3d(worldPoint).sub(center);
        return new Vector3d(
                rel.dot(axes[0]),
                rel.dot(axes[1]),
                rel.dot(axes[2])
        );
    }

    // local -> world
    private Vector3d localToWorld(Vector3d localPoint, Vector3d[] axes) {
        Vector3d result = new Vector3d(center);
        result.add(axes[0].mul(localPoint.x, new Vector3d()));
        result.add(axes[1].mul(localPoint.y, new Vector3d()));
        result.add(axes[2].mul(localPoint.z, new Vector3d()));
        return result;
    }

    public OBB inflate(double amount) {
        Vector3d newExtents = new Vector3d(extents).add(amount, amount, amount);
        return new OBB(center, newExtents, rotation, part);
    }

    public OBB inflate(double x, double y, double z) {
        Vector3d newExtents = new Vector3d(extents).add(x, y, z);
        return new OBB(center, newExtents, rotation, part);
    }

    public OBB move(Vec3 vec3) {
        Vector3d newCenter = new Vector3d(center.x + vec3.x, center.y + vec3.y, center.z + vec3.z);
        return new OBB(newCenter, extents, rotation, part);
    }

    /**
     * @return true if {@code vec3} lies inside the box.
     */
    public boolean contains(Vec3 vec3) {
        Vector3d rel = vec3ToVector3d(vec3).sub(center);

        Vector3d[] axes = new Vector3d[3];
        axes[0] = rotation.transform(new Vector3d(1, 0, 0));
        axes[1] = rotation.transform(new Vector3d(0, 1, 0));
        axes[2] = rotation.transform(new Vector3d(0, 0, 1));

        double projX = Math.abs(rel.dot(axes[0]));
        double projY = Math.abs(rel.dot(axes[1]));
        double projZ = Math.abs(rel.dot(axes[2]));

        return projX <= extents.x &&
                projY <= extents.y &&
                projZ <= extents.z;
    }

    /**
     * Ray/segment intersection returning the world-space hit point, or null if there is no hit.
     */
    public static Vec3 rayIntersect(OBB obb, Vec3 start, Vec3 end) {
        Vec3 center = vector3dToVec3(obb.center());
        Vec3 extents = vector3dToVec3(obb.extents());
        Quaterniond rotation = obb.rotation();

        Vector3d localStart = toLocal(obb, start);
        Vector3d localEnd = toLocal(obb, end);

        double minX = -extents.x, minY = -extents.y, minZ = -extents.z;
        double maxX = extents.x, maxY = extents.y, maxZ = extents.z;

        Vector2d result = new Vector2d();
        boolean intersects = Intersectiond.intersectRayAab(
                localStart.x, localStart.y, localStart.z,
                localEnd.x - localStart.x, localEnd.y - localStart.y, localEnd.z - localStart.z,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                result
        );

        if (intersects) {
            double t = result.x;
            Vector3d localHit = new Vector3d(
                    localStart.x + t * (localEnd.x - localStart.x),
                    localStart.y + t * (localEnd.y - localStart.y),
                    localStart.z + t * (localEnd.z - localStart.z)
            );

            rotation.transform(localHit);
            return new Vec3(localHit.x + center.x, localHit.y + center.y, localHit.z + center.z);
        }
        return null;
    }

    // world point -> box local frame
    private static Vector3d toLocal(OBB obb, Vec3 worldPoint) {
        Vec3 center = vector3dToVec3(obb.center());
        Quaterniond rotation = obb.rotation();
        Quaterniond inverse = new Quaterniond(rotation).conjugate();

        Vector3d relative = new Vector3d(
                worldPoint.x - center.x,
                worldPoint.y - center.y,
                worldPoint.z - center.z
        );

        inverse.transform(relative);
        return relative;
    }

    public enum Part {
        EMPTY,
        BODY,
        INTERACTIVE
    }

    public static Vector3d vec3ToVector3d(Vec3 vec3) {
        return new Vector3d(vec3.x, vec3.y, vec3.z);
    }

    public static Vec3 vector3dToVec3(Vector3d vector3d) {
        return new Vec3(vector3d.x, vector3d.y, vector3d.z);
    }
}
