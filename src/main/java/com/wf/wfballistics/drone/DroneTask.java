package com.wf.wfballistics.drone;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One step of a {@link DroneProgram}: somewhere to be, and what to do once there. */
public sealed interface DroneTask {

    /** How many steps one program may hold. */
    int MAX_STEPS = 32;
    /** Widest circle a drone will be asked to fly, blocks. */
    double MAX_LOITER_RADIUS = 256.0;

    /** The discriminant. */
    enum Kind {
        MOVE_TO("moveto"),
        DELIVER("deliver"),
        COLLECT("collect"),
        STRIKE("strike"),
        MINELAY("minelay"),
        LOITER("loiter"),
        EXFIL("exfil");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        public static Kind byId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equalsIgnoreCase(id)) {
                    return kind;
                }
            }
            return MOVE_TO;
        }

        /**
         * @return a step of this kind at {@code at}, with defaults for anything the kind needs beyond a
         *      position. What the pad screen builds when a step is added.
         */
        public DroneTask at(Vec3 at) {
            return switch (this) {
                case MOVE_TO -> new MoveTo(at);
                case DELIVER -> new Deliver(at);
                case COLLECT -> new Collect(at);
                case STRIKE -> new Strike(at);
                case MINELAY -> new Minelay(at);
                case LOITER -> new Loiter(at, Loiter.DEFAULT_RADIUS, 0);
                case EXFIL -> new Exfil();
            };
        }
    }

    Kind kind();

    /**
     * @return where this step happens, or null if it has no place of its own: {@link Exfil} goes to
     *      whatever the drone's exfil point is, which is not knowable when the program is written.
     */
    @Nullable
    Vec3 at();

    /**
     * @return the state to enter once the drone reaches {@link #at()}, or null to simply move on to the next
     *      step. A null is what makes {@link MoveTo} a waypoint rather than a destination.
     */
    @Nullable
    DroneState arrivalState();

    /**
     * @return a one-line description, for the pad screen's step list and {@code /wfballistics drone program
     *      list}.
     */
    String label();

    void write(FriendlyByteBuf buf);

    CompoundTag save();

    /**
     * Fly to a waypoint and carry straight on to the next step. The step that makes a route out of a queue.
     */
    record MoveTo(Vec3 at) implements DroneTask {
        @Override
        public Kind kind() {
            return Kind.MOVE_TO;
        }

        @Override
        @Nullable
        public DroneState arrivalState() {
            return null;
        }

        @Override
        public String label() {
            return "Move to " + pos(at);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            writeVec(buf, at);
        }

        @Override
        public CompoundTag save() {
            return saveVec(new CompoundTag(), at);
        }
    }

    /**
     * Put the slung crate down here.
     */
    record Deliver(Vec3 at) implements DroneTask {
        @Override
        public Kind kind() {
            return Kind.DELIVER;
        }

        @Override
        public DroneState arrivalState() {
            return DroneState.DELIVER;
        }

        @Override
        public String label() {
            return "Deliver at " + pos(at);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            writeVec(buf, at);
        }

        @Override
        public CompoundTag save() {
            return saveVec(new CompoundTag(), at);
        }
    }

    /**
     * Pick a crate up from here.
     */
    record Collect(Vec3 at) implements DroneTask {
        @Override
        public Kind kind() {
            return Kind.COLLECT;
        }

        @Override
        public DroneState arrivalState() {
            return DroneState.COLLECT;
        }

        @Override
        public String label() {
            return "Collect at " + pos(at);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            writeVec(buf, at);
        }

        @Override
        public CompoundTag save() {
            return saveVec(new CompoundTag(), at);
        }
    }

    /** Run in on this point and pickle the payload. */
    record Strike(Vec3 at) implements DroneTask {
        @Override
        public Kind kind() {
            return Kind.STRIKE;
        }

        @Override
        public DroneState arrivalState() {
            return DroneState.PAYLOAD_RUN;
        }

        @Override
        public String label() {
            return "Strike " + pos(at);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            writeVec(buf, at);
        }

        @Override
        public CompoundTag save() {
            return saveVec(new CompoundTag(), at);
        }
    }

    /** Lay the rack across this point. */
    record Minelay(Vec3 at) implements DroneTask {
        @Override
        public Kind kind() {
            return Kind.MINELAY;
        }

        @Override
        public DroneState arrivalState() {
            return DroneState.MINELAY;
        }

        @Override
        public String label() {
            return "Mine " + pos(at);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            writeVec(buf, at);
        }

        @Override
        public CompoundTag save() {
            return saveVec(new CompoundTag(), at);
        }
    }

    /**
     * Watch a place: orbit it at {@code radius}, or hold station over it when the radius is zero.
     *
     * @param ticks how long to stay, or 0 to stay until the battery says otherwise. Open-ended is the
     *      interesting setting: a drone told to watch somewhere until it is nearly out of charge is
     *      one whose loiter time is a real consequence of how far away the place is
     */
    record Loiter(Vec3 at, double radius, int ticks) implements DroneTask {

        public static final double DEFAULT_RADIUS = 40.0;

        public Loiter {
            radius = Mth.clamp(radius, 0.0, MAX_LOITER_RADIUS);
            ticks = Math.max(0, ticks);
        }

        /**
         * @return true if this is a station-hold rather than an orbit.
         */
        public boolean stationary() {
            return this.radius < 1.0;
        }

        @Override
        public Kind kind() {
            return Kind.LOITER;
        }

        @Override
        public DroneState arrivalState() {
            return DroneState.SURVEIL;
        }

        @Override
        public String label() {
            String where = stationary() ? "Hold over " + pos(at) : "Loiter " + pos(at) + " r" + (int) radius;
            return where + (ticks > 0 ? " for " + ticks / 20 + "s" : " until low");
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            writeVec(buf, at);
            buf.writeDouble(radius);
            buf.writeVarInt(ticks);
        }

        @Override
        public CompoundTag save() {
            CompoundTag tag = saveVec(new CompoundTag(), at);
            tag.putDouble("Radius", radius);
            tag.putInt("Ticks", ticks);
            return tag;
        }
    }

    /** Go home. */
    record Exfil() implements DroneTask {
        @Override
        public Kind kind() {
            return Kind.EXFIL;
        }

        @Override
        @Nullable
        public Vec3 at() {
            return null;
        }

        @Override
        public DroneState arrivalState() {
            return DroneState.EXFIL;
        }

        @Override
        public String label() {
            return "Exfil";
        }

        @Override
        public void write(FriendlyByteBuf buf) {
        }

        @Override
        public CompoundTag save() {
            return new CompoundTag();
        }
    }

    static DroneTask read(FriendlyByteBuf buf) {
        Kind kind = buf.readEnum(Kind.class);
        return switch (kind) {
            case MOVE_TO -> new MoveTo(readVec(buf));
            case DELIVER -> new Deliver(readVec(buf));
            case COLLECT -> new Collect(readVec(buf));
            case STRIKE -> new Strike(readVec(buf));
            case MINELAY -> new Minelay(readVec(buf));
            case LOITER -> new Loiter(readVec(buf), buf.readDouble(), buf.readVarInt());
            case EXFIL -> new Exfil();
        };
    }

    static void writeTagged(FriendlyByteBuf buf, DroneTask task) {
        buf.writeEnum(task.kind());
        task.write(buf);
    }

    static CompoundTag saveTagged(DroneTask task) {
        CompoundTag tag = task.save();
        tag.putString("Kind", task.kind().id());
        return tag;
    }

    static DroneTask load(CompoundTag tag) {
        Vec3 at = loadVec(tag);
        return switch (Kind.byId(tag.getString("Kind"))) {
            case MOVE_TO -> new MoveTo(at);
            case DELIVER -> new Deliver(at);
            case COLLECT -> new Collect(at);
            case STRIKE -> new Strike(at);
            case MINELAY -> new Minelay(at);
            case LOITER -> new Loiter(at, tag.contains("Radius")
                    ? tag.getDouble("Radius") : Loiter.DEFAULT_RADIUS, tag.getInt("Ticks"));
            case EXFIL -> new Exfil();
        };
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 at) {
        buf.writeDouble(at.x);
        buf.writeDouble(at.y);
        buf.writeDouble(at.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static CompoundTag saveVec(CompoundTag tag, Vec3 at) {
        tag.putDouble("X", at.x);
        tag.putDouble("Y", at.y);
        tag.putDouble("Z", at.z);
        return tag;
    }

    private static Vec3 loadVec(CompoundTag tag) {
        return new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }

    private static String pos(Vec3 at) {
        return (int) at.x + " " + (int) at.y + " " + (int) at.z;
    }
}
