package com.wf.wfballistics.work;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A pile of work several workers share out between themselves, without any of them talking to each other.
 *
 * <p>Deliberately domain-blind. It knows about claims, deadlines, retries and ordering; it does not know what
 * a block is, and nothing in here imports anything from {@code build} or {@code drone}. A {@link WorkOrder}
 * is a position, an ordering key and one integer, which is enough to describe placing a block, breaking one,
 * or whatever the next thing turns out to be.
 *
 * <p>Three things make this more than a list with a lock:
 *
 * <p><b>The sequence gate.</b> Handing out any pending order would send workers to jobs that cannot be done
 * yet: you cannot place a torch before its wall, or sand with nothing under it. Rather than model
 * dependencies, every order carries a {@link WorkOrder#sequence} and the queue refuses to hand out anything
 * more than {@link #LOOKAHEAD} beyond the lowest one still outstanding. That is a coarse instrument and it is
 * meant to be: it costs one integer per order, needs no graph, and expresses "layer by layer" exactly, which
 * is what both of the jobs this was written for actually need. The lookahead is what keeps it from
 * serialising the whole job behind one slow order.
 *
 * <p><b>Deadlines scale with distance.</b> A fixed timeout is the obvious design and it is wrong: how long an
 * order should take depends on how far away it is. Set it flat and a site five hundred blocks from the depot
 * has every claim expire <em>while the worker is still flying to it</em>: the queue reissues work to workers
 * already on their way, they arrive to find the job gone, and it never converges. See {@link #deadlineFor}.
 *
 * <p><b>Claims repel each other.</b> Four workers asking at the same moment would otherwise all be given the
 * four nearest orders, which are next to each other, which puts four of them in the same place. Claiming
 * prefers orders that are clear of other live claims: see {@link #CROWDING}.
 *
 * <p>Not thread-safe, and not meant to be: everything here runs on the server thread.
 */
public final class WorkQueue {

    /**
     * How far past the frontier an order may be and still be handed out, in sequence steps.
     *
     * <p>Zero would be a strict barrier, nothing from the next layer until this one is finished, and it
     * throttles the job to its slowest worker at every layer boundary. Two lets workers start the next layer
     * while the last few of this one are still in the air, which is what the parallelism was for, while
     * staying short enough that nothing gets more than a layer or so ahead of its own support.
     */
    public static final int LOOKAHEAD = 2;
    /**
     * How many times an order is retried before it is written off as {@link WorkStatus#BLOCKED}.
     */
    public static final int MAX_ATTEMPTS = 3;
    /**
     * Shortest deadline a claim can be given, ticks. Covers the case of an order underfoot, where the travel
     * time is nearly nothing but the work itself still takes as long as it takes.
     */
    public static final int MIN_DEADLINE = 200;
    /**
     * Ticks added on top of the travel estimate for doing the job once there: descending onto it, working,
     * climbing away.
     */
    public static final int WORK_ALLOWANCE = 100;
    /**
     * How much slower than a straight run at cruise a worker is allowed to be before its claim lapses. Three
     * is generous, and should be: the cost of a deadline that is too short is a queue that thrashes, and the
     * cost of one that is too long is only that a genuinely lost order takes a while to come back.
     */
    public static final double DEADLINE_SLACK = 3.0;
    /**
     * How far apart claims would ideally be, blocks. Not a rule: see {@link #CROWDING}.
     */
    public static final double CLAIM_SPACING = 8.0;
    /**
     * What a block of crowding is worth, in blocks of travel. A soft penalty rather than a hard exclusion so
     * that a queue whose remaining orders are all in one corner still hands them out instead of deadlocking.
     */
    public static final double CROWDING = 4.0;

    // Orders, held as parallel arrays and sorted by sequence. The sort is what makes the ready window a
    // contiguous run of indices starting at `cursor`, so claiming scans a layer rather than the whole job.
    private final long[] positions;
    private final int[] sequences;
    private final int[] data;
    private final byte[] status;
    private final byte[] attempts;

    private final Map<Integer, Claim> claims = new LinkedHashMap<>();

    /**
     * Lowest index that is not yet terminal. Only ever moves forward, because an order that is DONE or
     * BLOCKED never goes back.
     */
    private int cursor;
    private int done;
    private int blocked;

    private WorkQueue(long[] positions, int[] sequences, int[] data, byte[] status, byte[] attempts) {
        this.positions = positions;
        this.sequences = sequences;
        this.data = data;
        this.status = status;
        this.attempts = attempts;
        recount();
    }

    /**
     * Build a queue from unordered orders. Ids are assigned here, in sequence order, and the caller's ids are
     * discarded: an order's id is its index in this queue and means nothing outside it.
     */
    public static WorkQueue of(List<WorkOrder> orders) {
        List<WorkOrder> sorted = new ArrayList<>(orders);
        sorted.sort(Comparator.comparingInt(WorkOrder::sequence));
        int n = sorted.size();
        long[] positions = new long[n];
        int[] sequences = new int[n];
        int[] data = new int[n];
        for (int i = 0; i < n; i++) {
            WorkOrder order = sorted.get(i);
            positions[i] = order.at().asLong();
            sequences[i] = order.sequence();
            data[i] = order.data();
        }
        return new WorkQueue(positions, sequences, data, new byte[n], new byte[n]);
    }

    // --- reading ---

    public int size() {
        return this.positions.length;
    }

    public int done() {
        return this.done;
    }

    public int blocked() {
        return this.blocked;
    }

    public int claimed() {
        return this.claims.size();
    }

    public int pending() {
        return this.size() - this.done - this.blocked - this.claims.size();
    }

    /**
     * @return true when nothing is left to do, whether or not all of it worked. A job with blocked orders is
     * finished and unsuccessful, which is a distinction the caller has to make for itself.
     */
    public boolean finished() {
        return this.done + this.blocked >= this.size();
    }

    public WorkStatus statusOf(int id) {
        return WorkStatus.byOrdinal(this.status[id]);
    }

    public int attemptsOf(int id) {
        return this.attempts[id];
    }

    public WorkOrder order(int id) {
        return new WorkOrder(id, BlockPos.of(this.positions[id]), this.sequences[id], this.data[id]);
    }

    /**
     * @return the lowest sequence still outstanding, or {@link Integer#MAX_VALUE} if the queue is finished.
     * What the {@link #LOOKAHEAD} window is measured from.
     */
    public int frontier() {
        this.advanceCursor();
        return this.cursor < this.size() ? this.sequences[this.cursor] : Integer.MAX_VALUE;
    }

    @Nullable
    public Claim claimOf(int id) {
        return this.claims.get(id);
    }

    /**
     * @return the ids of every order somebody is holding right now. Small, one per worker, so callers that
     * need to do something per live claim should walk this rather than scanning the whole queue
     */
    public Set<Integer> claimedIds() {
        return java.util.Collections.unmodifiableSet(this.claims.keySet());
    }

    public List<Integer> claimsOf(UUID worker) {
        List<Integer> mine = new ArrayList<>();
        for (Map.Entry<Integer, Claim> entry : this.claims.entrySet()) {
            if (entry.getValue().worker().equals(worker)) {
                mine.add(entry.getKey());
            }
        }
        return mine;
    }

    // --- claiming ---

    /**
     * Hand this worker something to do.
     *
     * @param from          where the worker is now, for both the choice and the deadline
     * @param blocksPerTick how fast it travels, for the deadline. A worker that lies about this only cheats
     *                      itself out of time
     * @return an order, now marked {@link WorkStatus#CLAIMED} to this worker, or null if there is nothing
     * available, which may mean the queue is finished, or only that everything within the lookahead is
     * already claimed. The caller cannot tell the two apart from here and should ask {@link #finished}
     */
    @Nullable
    public WorkOrder claim(UUID worker, Vec3 from, double blocksPerTick, long now) {
        this.advanceCursor();
        if (this.cursor >= this.size()) {
            return null;
        }
        int limit = this.sequences[this.cursor] + LOOKAHEAD;
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = this.cursor; i < this.size() && this.sequences[i] <= limit; i++) {
            if (WorkStatus.byOrdinal(this.status[i]) != WorkStatus.PENDING) {
                continue;
            }
            double score = this.score(i, from);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        BlockPos at = BlockPos.of(this.positions[best]);
        this.status[best] = (byte) WorkStatus.CLAIMED.ordinal();
        this.claims.put(best, new Claim(worker, now, this.deadlineFor(from, at, blocksPerTick, now)));
        return new WorkOrder(best, at, this.sequences[best], this.data[best]);
    }

    /**
     * @return how good a candidate order {@code i} is for a worker at {@code from}: its distance, plus what
     * it costs to be near somebody else's claim.
     */
    private double score(int i, Vec3 from) {
        Vec3 at = Vec3.atCenterOf(BlockPos.of(this.positions[i]));
        double score = from.distanceTo(at);
        for (Integer held : this.claims.keySet()) {
            double gap = at.distanceTo(Vec3.atCenterOf(BlockPos.of(this.positions[held])));
            if (gap < CLAIM_SPACING) {
                score += (CLAIM_SPACING - gap) * CROWDING;
            }
        }
        return score;
    }

    /**
     * @return when a claim on {@code at}, made by a worker at {@code from} moving at {@code blocksPerTick},
     * should be considered lost.
     *
     * <p>Straight-line distance, which understates the real route: a worker climbs to altitude, may fly a
     * dogleg, and has to descend again. {@link #DEADLINE_SLACK} is what covers that, and it is set generously
     * because the two failure modes are wildly asymmetric: too long merely delays the recovery of an order
     * that was genuinely lost, while too short breaks the queue outright.
     */
    private long deadlineFor(Vec3 from, BlockPos at, double blocksPerTick, long now) {
        double speed = Math.max(0.05, blocksPerTick);
        double travel = from.distanceTo(Vec3.atCenterOf(at)) / speed;
        long allowance = (long) Math.ceil(travel * DEADLINE_SLACK) + WORK_ALLOWANCE;
        return now + Math.max(MIN_DEADLINE, allowance);
    }

    /**
     * The work got done.
     *
     * @return false if this worker did not hold that order: a stale worker finishing something that was
     * reassigned underneath it, which must not be allowed to count
     */
    public boolean complete(UUID worker, int id) {
        if (!this.holds(worker, id)) {
            return false;
        }
        this.claims.remove(id);
        this.status[id] = (byte) WorkStatus.DONE.ordinal();
        this.done++;
        return true;
    }

    /**
     * Give an order back.
     *
     * @param penalise whether this counts against the order's {@link #MAX_ATTEMPTS}. True when the work was
     *                 attempted and could not be done (the space is occupied, the material never came) and
     *                 false when the <em>worker</em> failed rather than the order. Getting this backwards
     *                 means three unlucky shoot-downs permanently block a perfectly placeable block
     * @return false if this worker did not hold that order
     */
    public boolean release(UUID worker, int id, boolean penalise) {
        if (!this.holds(worker, id)) {
            return false;
        }
        this.claims.remove(id);
        this.reopen(id, penalise);
        return true;
    }

    /**
     * Drop everything this worker was holding, without penalty. What a worker calls when it is recalled, runs
     * out of battery, or is destroyed: none of that is the orders' fault.
     *
     * @return how many were given back
     */
    public int abandon(UUID worker) {
        List<Integer> mine = this.claimsOf(worker);
        for (int id : mine) {
            this.claims.remove(id);
            this.reopen(id, false);
        }
        return mine.size();
    }

    /**
     * Expire claims whose deadline has passed. Call once per tick from whoever owns the queue.
     *
     * <p>A lapse <em>does</em> count as an attempt, unlike {@link #abandon}. A worker that took a claim and
     * then silently stopped existing is indistinguishable from an order that cannot be reached, and the only
     * thing that separates them over time is that the unreachable one keeps happening.
     *
     * @return how many lapsed
     */
    public int lapse(long now) {
        List<Integer> expired = null;
        for (Map.Entry<Integer, Claim> entry : this.claims.entrySet()) {
            if (entry.getValue().deadline() <= now) {
                if (expired == null) {
                    expired = new ArrayList<>();
                }
                expired.add(entry.getKey());
            }
        }
        if (expired == null) {
            return 0;
        }
        for (int id : expired) {
            this.claims.remove(id);
            this.reopen(id, true);
        }
        return expired.size();
    }

    private boolean holds(UUID worker, int id) {
        Claim claim = id >= 0 && id < this.size() ? this.claims.get(id) : null;
        return claim != null && claim.worker().equals(worker);
    }

    private void reopen(int id, boolean penalise) {
        if (penalise) {
            this.attempts[id] = (byte) Math.min(Byte.MAX_VALUE, this.attempts[id] + 1);
        }
        if (this.attempts[id] >= MAX_ATTEMPTS) {
            this.status[id] = (byte) WorkStatus.BLOCKED.ordinal();
            this.blocked++;
        } else {
            this.status[id] = (byte) WorkStatus.PENDING.ordinal();
        }
    }

    private void advanceCursor() {
        while (this.cursor < this.size() && WorkStatus.byOrdinal(this.status[this.cursor]).terminal()) {
            this.cursor++;
        }
    }

    private void recount() {
        this.done = 0;
        this.blocked = 0;
        for (byte b : this.status) {
            WorkStatus s = WorkStatus.byOrdinal(b);
            if (s == WorkStatus.DONE) {
                this.done++;
            } else if (s == WorkStatus.BLOCKED) {
                this.blocked++;
            }
        }
        this.cursor = 0;
        this.advanceCursor();
    }

    // --- serialisation ---

    /**
     * Written as primitive arrays rather than a list of compounds, which is not premature: a build of any
     * size is tens of thousands of orders, and a compound each would be megabytes of tag objects to allocate
     * on every autosave.
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("Positions", this.positions);
        tag.putIntArray("Sequences", this.sequences);
        tag.putIntArray("Data", this.data);
        tag.putByteArray("Status", this.status);
        tag.putByteArray("Attempts", this.attempts);
        ListTag held = new ListTag();
        for (Map.Entry<Integer, Claim> entry : this.claims.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putInt("Order", entry.getKey());
            one.putUUID("Worker", entry.getValue().worker());
            one.putLong("Since", entry.getValue().since());
            one.putLong("Deadline", entry.getValue().deadline());
            held.add(one);
        }
        tag.put("Claims", held);
        return tag;
    }

    public static WorkQueue load(CompoundTag tag) {
        long[] positions = tag.getLongArray("Positions");
        int n = positions.length;
        // Everything is sized off the positions array, so a truncated or hand-edited tag produces a short
        // queue rather than an exception on a mismatched index later.
        WorkQueue queue = new WorkQueue(positions, sized(tag.getIntArray("Sequences"), n),
                sized(tag.getIntArray("Data"), n), sized(tag.getByteArray("Status"), n),
                sized(tag.getByteArray("Attempts"), n));
        ListTag held = tag.getList("Claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < held.size(); i++) {
            CompoundTag one = held.getCompound(i);
            int id = one.getInt("Order");
            // A claim on an order that is no longer CLAIMED is a contradiction, and trusting it would leave
            // the order permanently unavailable with nobody able to release it.
            if (id >= 0 && id < n && queue.statusOf(id) == WorkStatus.CLAIMED) {
                queue.claims.put(id, new Claim(one.getUUID("Worker"), one.getLong("Since"),
                        one.getLong("Deadline")));
            }
        }
        // Any order left CLAIMED with no surviving claim goes back in the pile, unpenalised: the world
        // reloaded, which is nobody's fault.
        for (int i = 0; i < n; i++) {
            if (queue.statusOf(i) == WorkStatus.CLAIMED && !queue.claims.containsKey(i)) {
                queue.status[i] = (byte) WorkStatus.PENDING.ordinal();
            }
        }
        return queue;
    }

    private static int[] sized(int[] array, int n) {
        if (array.length == n) {
            return array;
        }
        int[] out = new int[n];
        System.arraycopy(array, 0, out, 0, Math.min(array.length, n));
        return out;
    }

    private static byte[] sized(byte[] array, int n) {
        if (array.length == n) {
            return array;
        }
        byte[] out = new byte[n];
        System.arraycopy(array, 0, out, 0, Math.min(array.length, n));
        return out;
    }

    /**
     * Who holds an order and until when.
     *
     * @param since    when it was taken, for diagnostics
     * @param deadline the tick after which {@link #lapse} takes it back
     */
    public record Claim(UUID worker, long since, long deadline) {
    }
}
