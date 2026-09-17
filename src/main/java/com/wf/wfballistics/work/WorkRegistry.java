package com.wf.wfballistics.work;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every open {@link WorkJob} on the server. */
public final class WorkRegistry extends SavedData {

    public static final String NAME = "wfballistics_work";
    /** How long a finished job stays in the list before it is swept, in ticks. */
    public static final long RETENTION = 24000L;

    private final Map<UUID, WorkJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, Long> finishedAt = new LinkedHashMap<>();

    public static WorkRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WorkRegistry::new, (tag, reg) -> WorkRegistry.load(tag)), NAME);
    }

    public static WorkRegistry get(ServerLevel level) {
        return get(level.getServer());
    }

    public void add(WorkJob job) {
        this.jobs.put(job.id(), job);
        setDirty();
    }

    @Nullable
    public WorkJob byId(UUID id) {
        return id == null ? null : this.jobs.get(id);
    }

    public List<WorkJob> all() {
        return new ArrayList<>(this.jobs.values());
    }

    /**
     * @return every job in this dimension that is still worth working, nearest first.
     */
    public List<WorkJob> openNear(ResourceKey<Level> dimension, BlockPos near) {
        List<WorkJob> open = new ArrayList<>();
        for (WorkJob job : this.jobs.values()) {
            if (job.dimension().equals(dimension) && job.workable()) {
                open.add(job);
            }
        }
        open.sort(java.util.Comparator.comparingDouble(job -> job.centre().distSqr(near)));
        return open;
    }

    public boolean cancel(UUID id) {
        WorkJob job = this.byId(id);
        if (job == null || job.cancelled()) {
            return false;
        }
        job.cancel();
        setDirty();
        return true;
    }

    public boolean remove(UUID id) {
        boolean gone = this.jobs.remove(id) != null;
        if (gone) {
            this.finishedAt.remove(id);
            setDirty();
        }
        return gone;
    }

    public int size() {
        return this.jobs.size();
    }

    /** Expire lapsed claims and sweep jobs that have been over for a while. */
    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        List<UUID> sweep = null;
        for (WorkJob job : this.jobs.values()) {
            if (!job.dimension().equals(level.dimension())) {
                continue;
            }
            if (job.over()) {
                Long since = this.finishedAt.putIfAbsent(job.id(), now);
                if (since != null && now - since >= RETENTION) {
                    if (sweep == null) {
                        sweep = new ArrayList<>();
                    }
                    sweep.add(job.id());
                }
                continue;
            }
            if (job.queue().lapse(now) > 0) {
                setDirty();
            }
        }
        if (sweep != null) {
            for (UUID id : sweep) {
                this.jobs.remove(id);
                this.finishedAt.remove(id);
            }
            setDirty();
        }
    }

    /**
     * Give back everything this worker was holding, everywhere.
     *
     * @return how many claims were released
     */
    public int abandonAll(UUID worker) {
        int released = 0;
        for (WorkJob job : this.jobs.values()) {
            released += job.queue().abandon(worker);
        }
        if (released > 0) {
            setDirty();
        }
        return released;
    }

    public static WorkRegistry load(CompoundTag tag) {
        WorkRegistry registry = new WorkRegistry();
        ListTag list = tag.getList("Jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            WorkJob job = WorkJob.load(list.getCompound(i));
            registry.jobs.put(job.id(), job);
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (WorkJob job : this.jobs.values()) {
            list.add(job.save());
        }
        tag.put("Jobs", list);
        return tag;
    }
}
