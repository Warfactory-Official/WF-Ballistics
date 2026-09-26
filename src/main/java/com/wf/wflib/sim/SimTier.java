package com.wf.wflib.sim;

import com.mojang.logging.LogUtils;
import com.wf.wflib.drone.WorldThread;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * One kind's records in one level. While a pass is in flight the list belongs to the worker: reads await it,
 * adds queue in an inbox merged at the join (=> a record added mid-tick is advanced from the next tick on).
 */
public final class SimTier<R> {

    private static final Logger LOGGER = LogUtils.getLogger();

    final SimKind<R> kind;
    private final SimWorld world;
    private final List<R> records = new ArrayList<>();
    private final List<R> inbox = new ArrayList<>();
    /** Kind-owned persistent extras (id counters...). */
    private CompoundTag meta = new CompoundTag();
    /** Volatile only so a stale null cannot be read. */
    private volatile @Nullable Future<?> pass;
    private long stallNanos;
    long passNanos;

    SimTier(SimKind<R> kind, SimWorld world) {
        this.kind = kind;
        this.world = world;
    }

    public ResourceKey<Level> dimension() {
        return this.world.dimension();
    }

    public SimKind<R> kind() {
        return this.kind;
    }

    /** Live list once no pass holds it. Mutated in place: copy before removing while walking. */
    public List<R> view() {
        this.await();
        return this.records;
    }

    /** Worker-side access, inside {@link SimKind#advance} only. */
    public List<R> passRecords() {
        return this.records;
    }

    public int count() {
        this.await();
        return this.records.size() + this.inbox.size();
    }

    public void add(R record) {
        WorldThread.assertOn("adding a sim record");
        if (this.pass != null) {
            this.inbox.add(record);
        } else {
            this.records.add(record);
        }
        this.setDirty();
    }

    public boolean remove(R record) {
        this.await();
        boolean removed = this.records.remove(record) || this.inbox.remove(record);
        this.setDirty();
        return removed;
    }

    @Nullable
    public R find(Predicate<R> test) {
        for (R r : this.inbox) {
            if (test.test(r)) {
                return r;
            }
        }
        for (R r : this.view()) {
            if (test.test(r)) {
                return r;
            }
        }
        return null;
    }

    public CompoundTag meta() {
        return this.meta;
    }

    public void setDirty() {
        this.world.setDirty();
    }

    void dispatch(Future<?> running) {
        this.pass = running;
    }

    /** Wait for the pass, merge the inbox. */
    public void await() {
        Future<?> running = this.pass;
        if (running != null) {
            WorldThread.assertOn("waiting for a sim pass");
            long start = System.nanoTime();
            try {
                running.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                LOGGER.error("[wflib] sim pass {} failed", this.kind.id(), e.getCause());
            } finally {
                this.pass = null;
                this.stallNanos += System.nanoTime() - start;
            }
        }
        if (!this.inbox.isEmpty()) {
            this.records.addAll(this.inbox);
            this.inbox.clear();
        }
    }

    /** Tick-thread nanos spent waiting since last asked; reset. */
    public long takeStall() {
        long spent = this.stallNanos;
        this.stallNanos = 0L;
        return spent;
    }

    /** Wall time of the last advance. */
    public long passNanos() {
        return this.passNanos;
    }

    CompoundTag save() {
        this.await();
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (R r : this.records) {
            list.add(this.kind.save(r));
        }
        tag.put("Records", list);
        tag.put("Meta", this.meta);
        return tag;
    }

    void load(CompoundTag tag) {
        ListTag list = tag.getList("Records", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            this.records.add(this.kind.load(list.getCompound(i)));
        }
        this.meta = tag.getCompound("Meta");
    }
}
