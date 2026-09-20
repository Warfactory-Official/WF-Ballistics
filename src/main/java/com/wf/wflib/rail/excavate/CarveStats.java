package com.wf.wflib.rail.excavate;

import java.util.concurrent.atomic.AtomicLong;

/** What excavation actually cost. */
public final class CarveStats {

    private final AtomicLong storedChunks = new AtomicLong();
    private final AtomicLong storedCells = new AtomicLong();
    private final AtomicLong storedNanos = new AtomicLong();
    private final AtomicLong attendedBlocks = new AtomicLong();
    private final AtomicLong blockEntitiesPruned = new AtomicLong();
    private final AtomicLong aborted = new AtomicLong();
    private final AtomicLong abortedNanos = new AtomicLong();
    private final AtomicLong refusedSections = new AtomicLong();

    private final AtomicLong readNanos = new AtomicLong();
    private final AtomicLong queueNanos = new AtomicLong();
    private final AtomicLong carveNanos = new AtomicLong();
    private final AtomicLong storeNanos = new AtomicLong();
    private final AtomicLong flushNanos = new AtomicLong();
    private final AtomicLong phased = new AtomicLong();
    private final AtomicLong flushed = new AtomicLong();

    void recordStored(int cells, int prunedBlockEntities, long nanos) {
        this.storedChunks.incrementAndGet();
        this.storedCells.addAndGet(cells);
        this.blockEntitiesPruned.addAndGet(prunedBlockEntities);
        this.storedNanos.addAndGet(nanos);
    }

    /**
     * One stored column, broken down.
     *
     * @param read the chunk read future completing: disk, decompress and NBT parse
     * @param queue the worker being busy with someone else after that read landed
     * @param carve the section rewrite itself, which is the only part that is ours
     * @param store handing the tag to the IO mailbox, which does not include writing it
     */
    void recordPhases(long read, long queue, long carve, long store) {
        this.readNanos.addAndGet(read);
        this.queueNanos.addAndGet(queue);
        this.carveNanos.addAndGet(carve);
        this.storeNanos.addAndGet(store);
        this.phased.incrementAndGet();
    }

    /** The IO worker finishing the write: serialise, compress, region file. Never on a carve worker. */
    void recordFlush(long nanos) {
        this.flushNanos.addAndGet(nanos);
        this.flushed.incrementAndGet();
    }

    void recordAborted(long nanos) {
        this.aborted.incrementAndGet();
        this.abortedNanos.addAndGet(nanos);
    }

    void recordRefusals(int sections) {
        this.refusedSections.addAndGet(sections);
    }

    void recordAttended(int blocks) {
        this.attendedBlocks.addAndGet(blocks);
    }

    public long storedChunks() {
        return this.storedChunks.get();
    }

    public long storedCells() {
        return this.storedCells.get();
    }

    public long attendedBlocks() {
        return this.attendedBlocks.get();
    }

    public long blockEntitiesPruned() {
        return this.blockEntitiesPruned.get();
    }

    public long aborted() {
        return this.aborted.get();
    }

    public long refusedSections() {
        return this.refusedSections.get();
    }

    /** @return per-column means in microseconds: read, queue, carve, store, flush. */
    public double[] meanPhaseMicros() {
        long n = Math.max(1L, this.phased.get());
        long f = Math.max(1L, this.flushed.get());
        return new double[]{
                this.readNanos.get() / (double) n / 1000.0,
                this.queueNanos.get() / (double) n / 1000.0,
                this.carveNanos.get() / (double) n / 1000.0,
                this.storeNanos.get() / (double) n / 1000.0,
                this.flushNanos.get() / (double) f / 1000.0};
    }

    /** @return how many columns have a phase breakdown recorded. */
    public long phased() {
        return this.phased.get();
    }

    /** @return mean time to carve one stored chunk, in microseconds. */
    public double meanStoredMicros() {
        long n = this.storedChunks.get();
        return n == 0 ? 0.0 : this.storedNanos.get() / (double) n / 1000.0;
    }

    /** @return mean cost of a carve that was thrown away, in microseconds - the abort-cost dial. */
    public double meanAbortedMicros() {
        long n = this.aborted.get();
        return n == 0 ? 0.0 : this.abortedNanos.get() / (double) n / 1000.0;
    }

    public void reset() {
        this.storedChunks.set(0);
        this.storedCells.set(0);
        this.storedNanos.set(0);
        this.attendedBlocks.set(0);
        this.blockEntitiesPruned.set(0);
        this.aborted.set(0);
        this.abortedNanos.set(0);
        this.refusedSections.set(0);
        this.readNanos.set(0);
        this.queueNanos.set(0);
        this.carveNanos.set(0);
        this.storeNanos.set(0);
        this.flushNanos.set(0);
        this.phased.set(0);
        this.flushed.set(0);
    }

    /** @return the phase breakdown, which is the only thing that says what to make faster. */
    public String phases() {
        double[] m = meanPhaseMicros();
        return String.format(
                "per column over %d: read %.0f us, queue %.0f us, carve %.0f us, store %.0f us"
                        + " | flush %.0f us on the IO thread over %d",
                this.phased.get(), m[0], m[1], m[2], m[3], m[4], this.flushed.get());
    }

    @Override
    public String toString() {
        return String.format(
                "stored %d chunks / %d cells (mean %.0f us), attended %d blocks, pruned %d BEs, "
                        + "aborted %d (mean %.0f us), refused %d sections",
                storedChunks(), storedCells(), meanStoredMicros(), attendedBlocks(), blockEntitiesPruned(),
                aborted(), meanAbortedMicros(), refusedSections());
    }
}
