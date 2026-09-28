package com.wf.wflib.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** The handful of wire shapes the debug overlays draw. */
public final class DebugLines {

    private DebugLines() {
    }

    public static void line(PoseStack pose, VertexConsumer buffer,
                            double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a) {
        Matrix4f mat = pose.last()
                .pose();
        float nx = (float) (x1 - x0);
        float ny = (float) (y1 - y0);
        float nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-5F) {
            nx /= len;
            ny /= len;
            nz /= len;
        } else {
            ny = 1.0f;
        }
        buffer.addVertex(mat, (float) x0, (float) y0, (float) z0)
                .setColor(r, g, b, a)
                .setNormal(nx, ny, nz);
        buffer.addVertex(mat, (float) x1, (float) y1, (float) z1)
                .setColor(r, g, b, a)
                .setNormal(nx, ny, nz);
    }

    public static void line(PoseStack pose, VertexConsumer buffer, Vec3 from, Vec3 to,
                            float r, float g, float b, float a) {
        line(pose, buffer, from.x, from.y, from.z, to.x, to.y, to.z, r, g, b, a);
    }

    /** A horizontal ring centred on {@code (cx, y, cz)}. */
    public static void circle(PoseStack pose, VertexConsumer buffer, double cx, double y, double cz,
                              double radius, int segments, float r, float g, float b, float a) {
        arc(pose, buffer, cx, y, cz, radius, 0.0, Math.PI * 2.0, segments, r, g, b, a);
    }

    /**
     * A horizontal arc from {@code fromRad} through {@code sweepRad}, measured the way a yaw is: 0 along +Z,
     * turning towards -X, so it lines up with {@link net.minecraft.world.entity.Entity#getYRot()}.
     */
    public static void arc(PoseStack pose, VertexConsumer buffer, double cx, double y, double cz,
                           double radius, double fromRad, double sweepRad, int segments,
                           float r, float g, float b, float a) {
        double px = 0.0;
        double pz = 0.0;
        for (int i = 0; i <= segments; i++) {
            double t = fromRad + sweepRad * (i / (double) segments);
            double x = cx - Math.sin(t) * radius;
            double z = cz + Math.cos(t) * radius;
            if (i > 0) {
                line(pose, buffer, px, y, pz, x, y, z, r, g, b, a);
            }
            px = x;
            pz = z;
        }
    }

    /**
     * A wedge: the two edges of a {@code sweepRad}-wide sector centred on {@code centreRad}, joined by the arc at
     * {@code radius}.
     */
    public static void wedge(PoseStack pose, VertexConsumer buffer, double cx, double y, double cz,
                             double radius, double centreRad, double sweepRad, int segments,
                             float r, float g, float b, float a) {
        double from = centreRad - sweepRad * 0.5;
        double to = centreRad + sweepRad * 0.5;
        line(pose, buffer, cx, y, cz,
                cx - Math.sin(from) * radius, y, cz + Math.cos(from) * radius, r, g, b, a);
        line(pose, buffer, cx, y, cz,
                cx - Math.sin(to) * radius, y, cz + Math.cos(to) * radius, r, g, b, a);
        arc(pose, buffer, cx, y, cz, radius, from, sweepRad, segments, r, g, b, a);
    }

    /** A cone of the given half-angle about {@code axis}, drawn as ribs plus a rim. */
    public static void cone(PoseStack pose, VertexConsumer buffer, Vec3 apex, Vec3 axis,
                            double halfAngleRad, double length, int ribs,
                            float r, float g, float b, float a) {
        Vec3 unit = axis.normalize();
        Vec3 up = Math.abs(unit.y) < 0.999 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = up.cross(unit)
                .normalize();
        Vec3 v = unit.cross(u);

        double ct = Math.cos(halfAngleRad) * length;
        double st = Math.sin(halfAngleRad) * length;
        Vec3 prev = null;
        Vec3 first = null;
        for (int i = 0; i < ribs; i++) {
            double phi = (i / (double) ribs) * Math.PI * 2.0;
            Vec3 rim = apex.add(unit.scale(ct))
                    .add(u.scale(st * Math.cos(phi)))
                    .add(v.scale(st * Math.sin(phi)));
            line(pose, buffer, apex, rim, r, g, b, a);
            if (prev != null) {
                line(pose, buffer, prev, rim, r, g, b, a);
            } else {
                first = rim;
            }
            prev = rim;
        }
        if (prev != null && first != null) {
            line(pose, buffer, prev, first, r, g, b, a);
        }
        // The axis itself, so the direction reads at a glance even edge-on.
        line(pose, buffer, apex, apex.add(unit.scale(length)), r, g, b, a);
    }

    /** A small axis cross, for marking a point. */
    public static void cross(PoseStack pose, VertexConsumer buffer, Vec3 at, double size,
                             float r, float g, float b, float a) {
        cross(pose, buffer, at.x, at.y, at.z, size, r, g, b, a);
    }

    public static void cross(PoseStack pose, VertexConsumer buffer, double x, double y, double z, double size,
                             float r, float g, float b, float a) {
        line(pose, buffer, x - size, y, z, x + size, y, z, r, g, b, a);
        line(pose, buffer, x, y - size, z, x, y + size, z, r, g, b, a);
        line(pose, buffer, x, y, z - size, x, y, z + size, r, g, b, a);
    }
}
