package com.wf.wflib.rail.excavate;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** The shape a carve removes, in world block coordinates. */
public interface CarveVolume {

    /** @return the block-space box enclosing this volume, used to pick which chunks and sections to visit. */
    BoundingBox bounds();

    /** @return whether this block position is inside the volume. */
    boolean contains(int x, int y, int z);

    /** A capsule: every block within {@code radius} of the segment from {@code from} to {@code to}. */
    static CarveVolume capsule(Vec3 from, Vec3 to, double radius) {
        return new Capsule(from, to, radius);
    }

    /** A cuboid: everything inside the box. */
    static CarveVolume box(BoundingBox box) {
        return new Box(box);
    }

    /** @see #capsule */
    final class Capsule implements CarveVolume {

        private final Vec3 from;
        private final Vec3 axis;
        private final double lengthSq;
        private final double radius;
        private final double radiusSq;
        private final BoundingBox bounds;

        Capsule(Vec3 from, Vec3 to, double radius) {
            this.from = from;
            this.axis = to.subtract(from);
            this.lengthSq = this.axis.lengthSqr();
            this.radius = radius;
            this.radiusSq = radius * radius;
            this.bounds = new BoundingBox(
                    (int) Math.floor(Math.min(from.x, to.x) - radius),
                    (int) Math.floor(Math.min(from.y, to.y) - radius),
                    (int) Math.floor(Math.min(from.z, to.z) - radius),
                    (int) Math.ceil(Math.max(from.x, to.x) + radius),
                    (int) Math.ceil(Math.max(from.y, to.y) + radius),
                    (int) Math.ceil(Math.max(from.z, to.z) + radius));
        }

        @Override
        public BoundingBox bounds() {
            return this.bounds;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            // Block centres, so the tunnel radius means what it says rather than being half a block tight.
            double px = x + 0.5 - this.from.x;
            double py = y + 0.5 - this.from.y;
            double pz = z + 0.5 - this.from.z;
            double t = this.lengthSq <= 1.0E-9 ? 0.0
                    : (px * this.axis.x + py * this.axis.y + pz * this.axis.z) / this.lengthSq;
            t = t < 0.0 ? 0.0 : Math.min(t, 1.0);
            double dx = px - this.axis.x * t;
            double dy = py - this.axis.y * t;
            double dz = pz - this.axis.z * t;
            return dx * dx + dy * dy + dz * dz <= this.radiusSq;
        }

        /** @return the capsule radius, in blocks. */
        public double radius() {
            return this.radius;
        }
    }

    /** @see #box */
    final class Box implements CarveVolume {

        private final BoundingBox box;

        Box(BoundingBox box) {
            this.box = box;
        }

        @Override
        public BoundingBox bounds() {
            return this.box;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return this.box.isInside(x, y, z);
        }
    }
}
