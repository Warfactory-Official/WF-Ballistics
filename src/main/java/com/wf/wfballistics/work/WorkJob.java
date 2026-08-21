package com.wf.wfballistics.work;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.UUID;

/**
 * A {@link WorkQueue} with somewhere to be and a name on it.
 *
 * <p>Thin on purpose. The queue is the interesting part and knows nothing about the domain; this adds only
 * what any job needs regardless of what the work is (which world it is in, what volume it occupies, what to
 * call it in a list) plus an opaque {@link #detail()} tag for whatever created it. A construction job keeps
 * its blueprint id and its supplying station in there; something else will keep something else.
 *
 * <p>{@link #kind()} is a plain id rather than a registry entry. There is no {@code WorkKind} interface
 * because there is nothing yet that all kinds would have to implement, and inventing one before there is a
 * second consumer would be guessing at its shape.
 */
public final class WorkJob {

    private final UUID id;
    private final ResourceLocation kind;
    private final ResourceKey<Level> dimension;
    private final BoundingBox bounds;
    private final String label;
    private final long createdAt;
    private final WorkQueue queue;
    private final CompoundTag detail;
    private boolean cancelled;
    /**
     * Why the job is temporarily not being worked, or null if it is.
     *
     * <p>Distinct from {@link #cancelled}, which is somebody's decision and permanent. A suspension is the
     * world's decision and reversible: a siege starts over a half-built structure, the drones stop being sent,
     * the siege ends, the build resumes. Not saved: it is re-derived within
     * {@code SiteRules.RECHECK_TICKS} of a reload, and a stale reason on disk would be worse than none.
     */
    private transient String suspended;

    public WorkJob(UUID id, ResourceLocation kind, ResourceKey<Level> dimension, BoundingBox bounds,
                   String label, long createdAt, WorkQueue queue, CompoundTag detail) {
        this.id = id;
        this.kind = kind;
        this.dimension = dimension;
        this.bounds = bounds;
        this.label = label;
        this.createdAt = createdAt;
        this.queue = queue;
        this.detail = detail;
    }

    public UUID id() {
        return this.id;
    }

    public ResourceLocation kind() {
        return this.kind;
    }

    public ResourceKey<Level> dimension() {
        return this.dimension;
    }

    public BoundingBox bounds() {
        return this.bounds;
    }

    public String label() {
        return this.label;
    }

    public long createdAt() {
        return this.createdAt;
    }

    public WorkQueue queue() {
        return this.queue;
    }

    public CompoundTag detail() {
        return this.detail;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    public void cancel() {
        this.cancelled = true;
    }

    @org.jetbrains.annotations.Nullable
    public String suspended() {
        return this.suspended;
    }

    /**
     * @return true if the reason changed, so the caller can log it once rather than every tick
     */
    public boolean suspend(@org.jetbrains.annotations.Nullable String reason) {
        if (java.util.Objects.equals(this.suspended, reason)) {
            return false;
        }
        this.suspended = reason;
        return true;
    }

    /**
     * @return true when nothing more will be done here: the queue ran out, or somebody called it off.
     *
     * <p>A suspension is deliberately <em>not</em> over: the job is still live, still holds its progress, and
     * will pick up again. What it stops is being handed to drones: see {@link #workable}.
     */
    public boolean over() {
        return this.cancelled || this.queue.finished();
    }

    /**
     * @return true if drones should be sent to this right now.
     */
    public boolean workable() {
        return !this.over() && this.suspended == null;
    }

    public BlockPos origin() {
        return new BlockPos(this.bounds.minX(), this.bounds.minY(), this.bounds.minZ());
    }

    public BlockPos centre() {
        return this.bounds.getCenter();
    }

    /**
     * @return a one-line summary for {@code /wfballistics job list}.
     */
    public String progress() {
        WorkQueue q = this.queue;
        String state = this.cancelled ? "cancelled"
                : q.finished() ? "finished"
                : this.suspended != null ? "suspended (" + this.suspended + ")"
                : "running";
        String stuck = q.blocked() > 0 ? ", " + q.blocked() + " blocked" : "";
        return String.format("%s %d/%d%s (%d claimed)", state, q.done(), q.size(), stuck, q.claimed());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", this.id);
        tag.putString("Kind", this.kind.toString());
        tag.putString("Dimension", this.dimension.location().toString());
        tag.putIntArray("Bounds", new int[]{this.bounds.minX(), this.bounds.minY(), this.bounds.minZ(),
                this.bounds.maxX(), this.bounds.maxY(), this.bounds.maxZ()});
        tag.putString("Label", this.label);
        tag.putLong("CreatedAt", this.createdAt);
        tag.put("Queue", this.queue.save());
        tag.put("Detail", this.detail);
        if (this.cancelled) {
            tag.putBoolean("Cancelled", true);
        }
        return tag;
    }

    public static WorkJob load(CompoundTag tag) {
        int[] b = tag.getIntArray("Bounds");
        BoundingBox bounds = b.length == 6
                ? new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5])
                : new BoundingBox(BlockPos.ZERO);
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("Dimension"));
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                dimensionId != null ? dimensionId : Level.OVERWORLD.location());
        ResourceLocation kind = ResourceLocation.tryParse(tag.getString("Kind"));
        WorkJob job = new WorkJob(tag.getUUID("Id"),
                kind != null ? kind : ResourceLocation.withDefaultNamespace("unknown"),
                dimension, bounds, tag.getString("Label"), tag.getLong("CreatedAt"),
                WorkQueue.load(tag.getCompound("Queue")), tag.getCompound("Detail"));
        job.cancelled = tag.getBoolean("Cancelled");
        return job;
    }
}
