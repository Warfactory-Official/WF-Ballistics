package com.wf.wfballistics.drone;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of {@link DroneTask}s and how far through it a drone has got.
 *
 * <p>This is the mission as something you can write rather than something the code decides. A stock delivery
 * is a two-step program ({@code deliver}, {@code exfil}) and reads exactly the same to the state machine as a
 * strike that then goes and watches the crater; the difference between the two lives entirely in this list.
 *
 * <p>Immutable: {@link #advanced()} returns a new one rather than moving a cursor. That is what lets the
 * whole program ride inside a {@code DroneSnapshot} and be read by the off-thread planner with no copying and
 * no synchronisation, alongside everything else the brain is allowed to see.
 *
 * @param cursor index of the step being flown; at or past {@code tasks.size()} the program is spent
 */
public record DroneProgram(List<DroneTask> tasks, int cursor) {

    public static final DroneProgram EMPTY = new DroneProgram(List.of(), 0);

    public DroneProgram {
        tasks = List.copyOf(tasks);
        cursor = Math.max(0, cursor);
    }

    public static DroneProgram of(List<DroneTask> tasks) {
        return tasks.isEmpty() ? EMPTY : new DroneProgram(tasks, 0);
    }

    /**
     * @return the step being flown, or null once the program is spent. A drone with no step left falls back
     * to the behaviour it had before programs existed: finish up and go home.
     */
    @Nullable
    public DroneTask current() {
        return cursor < tasks.size() ? tasks.get(cursor) : null;
    }

    /**
     * @return true if finishing the current step leaves another one to fly. What the arrival handlers ask to
     * decide between carrying on and heading home.
     */
    public boolean hasNext() {
        return cursor + 1 < tasks.size();
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * @return how many steps are left to fly, the current one included.
     */
    public int remaining() {
        return Math.max(0, tasks.size() - cursor);
    }

    /**
     * @return the same program, one step further on.
     */
    public DroneProgram advanced() {
        return cursor >= tasks.size() ? this : new DroneProgram(tasks, cursor + 1);
    }

    /**
     * @return a spent copy: everything queued is abandoned. What a battery abort leaves behind, so a drone
     * sent home early does not pick the queue back up on the way.
     */
    public DroneProgram abandoned() {
        return tasks.isEmpty() ? EMPTY : new DroneProgram(tasks, tasks.size());
    }

    /**
     * @return this program with {@code task} appended, or unchanged if it is already at
     * {@link DroneTask#MAX_STEPS}.
     */
    public DroneProgram plus(DroneTask task) {
        if (tasks.size() >= DroneTask.MAX_STEPS) {
            return this;
        }
        List<DroneTask> next = new ArrayList<>(tasks);
        next.add(task);
        return new DroneProgram(next, cursor);
    }

    public DroneProgram removing(int index) {
        if (index < 0 || index >= tasks.size()) {
            return this;
        }
        List<DroneTask> next = new ArrayList<>(tasks);
        next.remove(index);
        return new DroneProgram(next, cursor);
    }

    /**
     * @return this program with the step at {@code index} moved by {@code delta} places, clamped to the ends.
     */
    public DroneProgram moved(int index, int delta) {
        int to = index + delta;
        if (index < 0 || index >= tasks.size() || to < 0 || to >= tasks.size() || delta == 0) {
            return this;
        }
        List<DroneTask> next = new ArrayList<>(tasks);
        next.add(to, next.remove(index));
        return new DroneProgram(next, cursor);
    }

    public DroneProgram replacing(int index, DroneTask task) {
        if (index < 0 || index >= tasks.size()) {
            return this;
        }
        List<DroneTask> next = new ArrayList<>(tasks);
        next.set(index, task);
        return new DroneProgram(next, cursor);
    }

    /**
     * @return the point the drone should be heading for right now, or null if the program has nothing left
     * to say. {@link DroneTask.Exfil} resolves to the drone's own exfil point, which is why this needs it.
     */
    @Nullable
    public Vec3 destination(Vec3 exfil) {
        DroneTask task = current();
        if (task == null) {
            return null;
        }
        return task.at() != null ? task.at() : exfil;
    }

    /**
     * @return where the drone ends up once the last step is flown, or {@code fallback} for a program with no
     * steps. What the return leg is priced from.
     */
    public Vec3 endsAt(Vec3 fallback, Vec3 exfil) {
        int last = tasks.size() - 1;
        if (last < cursor) {
            return fallback;
        }
        Vec3 at = tasks.get(last).at();
        return at != null ? at : exfil;
    }

    /**
     * @return the total distance flown from {@code origin} through every remaining step. What the dispatcher
     * prices the battery against: a three-stop program costs what all three legs cost, not what the first
     * one does.
     */
    public double routeLength(Vec3 origin, Vec3 exfil) {
        Vec3 from = origin;
        double total = 0.0;
        for (int i = cursor; i < tasks.size(); i++) {
            Vec3 at = tasks.get(i).at();
            Vec3 to = at != null ? at : exfil;
            total += from.distanceTo(to);
            from = to;
        }
        return total;
    }

    public void write(FriendlyByteBuf buf) {
        int count = Math.min(tasks.size(), DroneTask.MAX_STEPS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            DroneTask.writeTagged(buf, tasks.get(i));
        }
        buf.writeVarInt(cursor);
    }

    /**
     * Read a program off the wire.
     *
     * <p>The step count is clamped before anything is allocated, not after. This is read from a packet the
     * drone pad screen sends, so the count is whatever a client says it is, and a length prefix trusted far
     * enough to size a list with is the whole of that class of bug.
     */
    public static DroneProgram read(FriendlyByteBuf buf) {
        int count = Math.min(Math.max(0, buf.readVarInt()), DroneTask.MAX_STEPS);
        List<DroneTask> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tasks.add(DroneTask.read(buf));
        }
        int cursor = Math.max(0, buf.readVarInt());
        return new DroneProgram(tasks, cursor);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (DroneTask task : tasks) {
            list.add(DroneTask.saveTagged(task));
        }
        tag.put("Tasks", list);
        tag.putInt("Cursor", cursor);
        return tag;
    }

    public static DroneProgram load(CompoundTag tag) {
        ListTag list = tag.getList("Tasks", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return EMPTY;
        }
        int count = Math.min(list.size(), DroneTask.MAX_STEPS);
        List<DroneTask> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tasks.add(DroneTask.load(list.getCompound(i)));
        }
        return new DroneProgram(tasks, tag.getInt("Cursor"));
    }
}
