package com.wf.wfballistics.entity.glyphid.nav;

import com.wf.wfballistics.debug.SwarmBench;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** One shared answer to "which way from here", for every glyphid walking to the same place. */
public final class GlyphidFlowField {

    /** Columns from the centre to the edge. */
    public static final int RADIUS = 48;
    public static final int SIZE = RADIUS * 2 + 1;
    private static final int COLUMNS = SIZE * SIZE;

    /** How far above and below the plane a floor is looked for: a hillside, but not the bottom of a ravine. */
    private static final int VERTICAL_REACH = 12;
    /** Clearance a glyphid needs to stand somewhere. The tallest caste is 1.5 blocks, so two. */
    private static final int CLEARANCE = 2;
    /** Biggest rise between neighbouring columns that still counts as connected. */
    private static final int CLIMB_UP = 8;
    /** Biggest drop that still counts as connected. A route that will not go downhill goes round the hill. */
    private static final int DROP = 8;
    /** Gaps probed for a bridgeable crossing per field. */
    private static final int MAX_SITES = 16;
    /** Gaps probed per field. */
    private static final int MAX_PROBES = 256;
    /** How far the far bank may sit above or below the near one. A deck is flat, so the two banks must be. */
    private static final int MAX_BANK_STEP = 1;

    private static final int NO_FLOOR = Integer.MIN_VALUE;
    private static final int UNSAMPLED = Integer.MIN_VALUE + 1;
    static final short UNREACHED = -1;

    private final int centreX;
    private final int centreY;
    private final int centreZ;

    /** Floor height per column: {@link #NO_FLOOR} where nothing can stand, {@link #UNSAMPLED} unvisited. */
    private final int[] floor = new int[COLUMNS];
    /** Steps from the destination, {@link #UNREACHED} until the flood arrives. Short: no route is longer. */
    private final short[] distance = new short[COLUMNS];

    private final IntArrayFIFOQueue frontier = new IntArrayFIFOQueue();
    private int filled;
    private boolean complete;
    /** Gaps looked at so far, capped by {@link #MAX_SITES}. */
    private int sitesProbed;
    /** Crossings the probe approved, best first, handed over when the flood finishes. */
    private Candidate[] candidates;
    private int candidateCount;
    /** Rank of the worst candidate held, so a gap that cannot displace it is turned away before the probe. */
    private long worstRank;

    private record Candidate(long rank, int bankX, int bankZ, int deckY, int landingX, int landingZ,
                             GlyphidBridge.Slot[] slots) {
    }

    public GlyphidFlowField(ServerLevel level, int x, int y, int z) {
        this.centreX = x;
        this.centreY = y;
        this.centreZ = z;
        java.util.Arrays.fill(floor, UNSAMPLED);
        java.util.Arrays.fill(distance, UNREACHED);
        // Before the flood, so a finished deck is already ordinary floor by the time anything walks into it.
        GlyphidBridges.stampInto(level, this);

        int origin = index(x, z);
        if (origin >= 0 && (floor[origin] != UNSAMPLED ? floor[origin] : sampleFloor(level, x, z, origin))
                != NO_FLOOR) {
            distance[origin] = 0;
            frontier.enqueue(origin);
            filled = 1;
        } else {
            complete = true;
        }
    }

    public int centreX() {
        return centreX;
    }

    public int centreY() {
        return centreY;
    }

    public int centreZ() {
        return centreZ;
    }

    public boolean complete() {
        return complete;
    }

    public int filled() {
        return filled;
    }

    /** Spend up to {@code budget} columns of flood. */
    public int build(ServerLevel level, int budget) {
        int spent = 0;
        while (spent < budget && !frontier.isEmpty()) {
            int cell = frontier.dequeueInt();
            spent++;
            expand(level, cell);
        }
        if (frontier.isEmpty() && !complete) {
            complete = true;
            handOverCandidates(level);
        }
        return spent;
    }

    private void expand(ServerLevel level, int cell) {
        int cx = cell % SIZE;
        int cz = cell / SIZE;
        int here = floor[cell];
        short next = (short) (distance[cell] + 1);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = cx + dx;
                int nz = cz + dz;
                if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) {
                    continue;
                }
                int neighbour = nz * SIZE + nx;
                if (distance[neighbour] != UNREACHED) {
                    continue;
                }
                int blockX = centreX + nx - RADIUS;
                int blockZ = centreZ + nz - RADIUS;
                int floorY = floor[neighbour] == UNSAMPLED
                        ? sampleFloor(level, blockX, blockZ, neighbour)
                        : floor[neighbour];
                if (floorY == NO_FLOOR) {
                    // Nothing to stand on: the one rejection a swarm of ants has an answer to.
                    considerGap(level, cx, cz, here, dx, dz);
                    continue;
                }
                if (floorY - here > CLIMB_UP || here - floorY > DROP) {
                    continue;
                }
                distance[neighbour] = next;
                filled++;
                frontier.enqueue(neighbour);
            }
        }
    }

    /**
     * The height a glyphid would stand at in this column, searched outward from the destination plane rather than
     * down from the sky: a column under an overhang has two floors, and the one level with its neighbours is the
     * one that matters.
     */
    private int sampleFloor(ServerLevel level, int blockX, int blockZ, int cell) {
        int y = findFloor(level, blockX, blockZ);
        floor[cell] = y;
        return y;
    }

    /** The same search without the write, so a bridge probe can ask about a column it does not own. */
    private int findFloor(ServerLevel level, int blockX, int blockZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int offset = 0; offset <= VERTICAL_REACH; offset++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int y = centreY + offset * sign;
                if (offset == 0 && sign < 0) {
                    continue;
                }
                if (standable(level, pos, blockX, y, blockZ)) {
                    return y;
                }
            }
        }
        return NO_FLOOR;
    }

    /**
     * Look at a column the flood turned down for having nothing to stand on, and if the swarm could carry itself
     * across, hand the crossing to {@link GlyphidBridges}.
     */
    private void considerGap(ServerLevel level, int cx, int cz, int here, int dx, int dz) {
        // Cardinals only: a diagonal deck is a line of anchors meeting at their corners, which is not a floor.
        if (sitesProbed >= MAX_PROBES || (dx != 0 && dz != 0) || !SwarmBench.bridges) {
            return;
        }
        int bankX = centreX + cx - RADIUS;
        int bankZ = centreZ + cz - RADIUS;
        long rank = rank(bankX, bankZ);
        if (candidateCount >= MAX_SITES && rank >= worstRank) {
            return;
        }
        sitesProbed++;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int step = 1; step <= GlyphidBridge.MAX_SPAN; step++) {
            int x = bankX + dx * step;
            int z = bankZ + dz * step;
            int floorY = findFloor(level, x, z);
            if (floorY != NO_FLOOR) {
                if (step >= 2 && Math.abs(floorY - here) <= MAX_BANK_STEP) {
                    remember(bankX, bankZ, here, dx, dz, step, x, z);
                }
                return;
            }
            if (!clearForDeck(level, pos, x, here, z)) {
                return;
            }
        }
    }

    /** Remember an approved crossing, keeping the {@link #MAX_SITES} nearest the destination. */
    private void remember(int bankX, int bankZ, int deckY, int dx, int dz, int landingStep, int landingX,
                          int landingZ) {
        GlyphidBridge.Slot[] slots = new GlyphidBridge.Slot[landingStep - 1];
        for (int i = 1; i < landingStep; i++) {
            slots[i - 1] = new GlyphidBridge.Slot(i - 1, bankX + dx * i, bankZ + dz * i);
        }
        Candidate candidate = new Candidate(rank(bankX, bankZ), bankX, bankZ, deckY, landingX, landingZ,
                slots);

        if (candidates == null) {
            candidates = new Candidate[MAX_SITES];
        }
        if (candidateCount < MAX_SITES) {
            candidates[candidateCount++] = candidate;
        } else {
            int worst = 0;
            for (int i = 1; i < candidateCount; i++) {
                if (candidates[i].rank() > candidates[worst].rank()) {
                    worst = i;
                }
            }
            if (candidate.rank() >= candidates[worst].rank()) {
                return;
            }
            candidates[worst] = candidate;
        }
        worstRank = 0L;
        for (int i = 0; i < candidateCount; i++) {
            worstRank = Math.max(worstRank, candidates[i].rank());
        }
    }

    /** How far a bank is from the destination, squared. See {@link #candidates} for why this is the ordering. */
    private long rank(int bankX, int bankZ) {
        long spanX = bankX - centreX;
        long spanZ = bankZ - centreZ;
        return spanX * spanX + spanZ * spanZ;
    }

    /** Offer the approved crossings nearest the destination first, once the flood has seen all of them. */
    private void handOverCandidates(ServerLevel level) {
        Candidate[] found = candidates;
        if (found == null) {
            return;
        }
        java.util.Arrays.sort(found, 0, candidateCount,
                java.util.Comparator.comparingLong(Candidate::rank));
        for (int i = 0; i < candidateCount; i++) {
            Candidate candidate = found[i];
            GlyphidBridges.propose(level, candidate.bankX(), candidate.bankZ(), candidate.deckY(),
                    candidate.landingX(), candidate.landingZ(), candidate.slots());
        }
        candidates = null;
        candidateCount = 0;
    }

    /**
     * Whether a span column has room for an anchor and for whatever walks over it: one cell of body below the deck
     * and {@link #CLEARANCE} above, which is what {@link #standable} asks of real ground.
     */
    private static boolean clearForDeck(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int deckY,
                                        int z) {
        for (int dy = -1; dy < CLEARANCE; dy++) {
            pos.set(x, deckY + dy, z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Write a finished deck in as ordinary floor. */
    void stampDeck(int blockX, int blockZ, int deckY) {
        int cell = index(blockX, blockZ);
        if (cell >= 0) {
            floor[cell] = deckY;
        }
    }

    private static boolean standable(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        pos.set(x, y - 1, z);
        BlockState below = level.getBlockState(pos);
        if (!below.isSolidRender(level, pos)) {
            return false;
        }
        for (int h = 0; h < CLEARANCE; h++) {
            pos.set(x, y + h, z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the centre of the neighbouring column a glyphid at this position should walk into, or null if
     *      this position is off the field, in an unreached column, or already at the destination.
     */
    public double[] step(double x, double y, double z) {
        int cell = index(Math.floor(x), Math.floor(z));
        if (cell < 0 || distance[cell] == UNREACHED) {
            return null;
        }
        int cx = cell % SIZE;
        int cz = cell / SIZE;
        short best = distance[cell];
        int bestCell = -1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = cx + dx;
                int nz = cz + dz;
                if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) {
                    continue;
                }
                int neighbour = nz * SIZE + nx;
                short d = distance[neighbour];
                if (d != UNREACHED && d < best) {
                    best = d;
                    bestCell = neighbour;
                }
            }
        }
        if (bestCell < 0) {
            return null;
        }
        return new double[]{
                centreX + bestCell % SIZE - RADIUS + 0.5,
                floor[bestCell],
                centreZ + bestCell / SIZE - RADIUS + 0.5};
    }

    private int index(double blockX, double blockZ) {
        int dx = (int) blockX - centreX + RADIUS;
        int dz = (int) blockZ - centreZ + RADIUS;
        if (dx < 0 || dx >= SIZE || dz < 0 || dz >= SIZE) {
            return -1;
        }
        return dz * SIZE + dx;
    }

    /**
     * @return true if this block is inside the square this field covers.
     */
    public boolean covers(int blockX, int blockZ) {
        return index(blockX, blockZ) >= 0;
    }
}
