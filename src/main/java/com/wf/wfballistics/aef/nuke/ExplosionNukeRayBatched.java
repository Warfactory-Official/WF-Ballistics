package com.wf.wfballistics.aef.nuke;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Carries out a nuclear blast as a batched, multi-tick ray-march: rays are cast outward on a generalized
 * spiral, the blocks each ray reaches are recorded, then those blocks are cleared chunk-by-chunk so the
 * cost is spread across several ticks instead of stalling one.
 */
public class ExplosionNukeRayBatched implements IExplosionRay {
    // Blocks queued for removal, grouped by chunk: key = chunk position, value = ray endpoints in that chunk.
    private final HashMap<ChunkPos, List<Vec3>> perChunk = new HashMap<>(); //for future: optimize blockmap further by using sub-chunks instead of chunks
    private final List<ChunkPos> orderedChunks = new ArrayList<>();
    private final CoordComparator comparator = new CoordComparator();
    private final BlockPos pos;       // blast center (the explosion entity's position)
    private final ChunkPos chunkPos;  // chunk containing the blast center

    private final Level level;

    private final int strength;
    private final int radius;
    private final int speed;
    private final int gspNumMax;
    int collectTipCnt = 0;
    int processChunkCnt = 0;
    private int gspNum;
    // Spherical angles of the current ray (theta = polar, phi = azimuth).
    private double theta;
    private double phi;
    private boolean isAusf3Complete = false;

    public ExplosionNukeRayBatched(Level level, BlockPos pos, int strength, int speed, int radius) {
        this.level = level;
        this.pos = pos;
        this.chunkPos = new ChunkPos(pos);
        this.strength = strength;
        this.radius = radius;
        this.speed = speed;
        // Total number of points
        this.gspNumMax = (int) (2.5 * Math.PI * Math.pow(this.strength, 2));
        this.gspNum = 1;

        // The beginning of the generalized spiral points
        this.theta = Math.PI;
        this.phi = 0.0;
    }

    private static Vec3 spherical2cartesian(Vec2 sPos) {
        return new Vec3(Math.sin(sPos.x) * Math.cos(sPos.y), Math.cos(sPos.x), Math.sin(sPos.x) * Math.sin(sPos.y));
    }

    @Deprecated
    private static BlockPos OutsideRound(BlockPos pos, Vec3 vec, int i) {
        double x0 = vec.x < 0 ? Math.ceil(pos.getX() + vec.x * i) : Math.floor(pos.getX() + vec.x * i);
        double y0 = vec.y < 0 ? Math.ceil(pos.getY() + vec.y * i) : Math.floor(pos.getY() + vec.y * i);
        double z0 = vec.z < 0 ? Math.ceil(pos.getZ() + vec.z * i) : Math.floor(pos.getZ() + vec.z * i);
        return new BlockPos((int) x0, (int) y0, (int) z0);
    }

    public void collectTip(int count) {
        int amountProcessed = 0;
        while (this.gspNumMax >= this.gspNum) {
            // Convert the spherical angles into a Cartesian direction vector.
            Vec3 vec = spherical2cartesian(new Vec2((float) theta, (float) phi));

            int length = (int) Math.ceil(strength);
            // Remaining shockwave intensity along this ray, depleted as it passes through blocks.
            float res = strength;
            // Farthest block this ray reaches (its endpoint).
            Vec3 endPoint = null;
            HashSet<ChunkPos> chunkCoords = new HashSet();
            // March outward from the center along vec, one block at a time, up to `length` blocks.
            for (int i = 0; i < length && i < this.radius && res > 0; i++) {
                // Position i blocks out from the center along vec.
                Vec3 dirVec = new Vec3(pos.getX(), pos.getY(), pos.getZ()).add(vec.scale(i));
                BlockPos dirPos = new BlockPos((int) Math.floor(dirVec.x), (int) Math.floor(dirVec.y), (int) Math.floor(dirVec.z));
                BlockState blockState = level.getBlockState(dirPos);
                // Shockwave falloff with distance from the center.
                double fac = 100 - ((double) i) / ((double) length) * 100;
                fac *= 0.07D;

                // Uses the block's default explosion resistance; replaceable blocks (grass, plants, ...) are cleared outright.
                if (blockState.canBeReplaced()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2, 0);
                if (!blockState.liquid())
                    res -= (float) Math.pow(blockState.getBlock().getExplosionResistance(), 7.5D - fac);

                if (res > 0 && !blockState.isAir()) {
                    endPoint = dirVec;
                    chunkCoords.add(new ChunkPos(dirPos));
                }
            }

            for (ChunkPos pos : chunkCoords) {
                List<Vec3> endList = perChunk.computeIfAbsent(pos, k -> new ArrayList<>());
                // Chunks reuse the same endpoint, which may fall outside the chunk; that is resolved in the removal phase.
                endList.add(endPoint);
            }

            // Raise one generalized spiral points
            this.generateGspUp();

            if (++amountProcessed >= count)
                return;
        }

        orderedChunks.addAll(perChunk.keySet());
        orderedChunks.sort(comparator);

        isAusf3Complete = true;
    }

    public void processChunk() {
        if (this.perChunk.isEmpty()) return;

        ChunkPos coord = orderedChunks.get(0);
        List<Vec3> list = perChunk.get(coord);
        HashSet<BlockPos> toRem = new HashSet<>();      // blocks to remove in this chunk
        HashSet<BlockPos> toRemTips = new HashSet<>();  // blocks to remove that sit at a ray's endpoint (edge tips)

        int enter = Math.min(Math.abs(pos.getX() - (coord.x << 4)), Math.abs(pos.getZ() - (coord.z << 4))) - 16; //jump ahead to cut back on NOPs

        enter = Math.max(enter, 0);

        for (Vec3 triplet : list) {
            Vec3 vec = triplet.subtract(pos.getCenter());
            double vecLen = vec.length();
            vec = vec.normalize();
            BlockPos tip = new BlockPos((int) Math.floor(triplet.x), (int) Math.floor(triplet.y), (int) Math.floor(triplet.z));

            boolean inChunk = false;
            for (int i = enter; i < vecLen; i++) {
                BlockPos pos1 = new BlockPos((int) Math.floor(pos.getX() + vec.x * i), (int) Math.floor(pos.getY() + vec.y * i), (int) Math.floor(pos.getZ() + vec.z * i));
                if (pos1.getX() >> 4 != coord.x || pos1.getZ() >> 4 != coord.z) {
                    if (inChunk) {
                        break;
                    } else {
                        continue;
                    }
                }
                inChunk = true;

                if (!level.getBlockState(pos1).isAir()) {
                    if (pos1.equals(tip)) {
                        toRemTips.add(pos1);
                    }
                    toRem.add(pos1);
                }
            }
        }

        for (BlockPos pos : toRem) {
            if (toRemTips.contains(pos)) {
                this.handleTip(pos);
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2, 0);
            }
        }

        perChunk.remove(coord);
        orderedChunks.remove(0);
    }

    protected void handleTip(BlockPos blockPos) {
        level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3);
    }

    // Advances theta/phi to the next point on the generalized spiral.
    private void generateGspUp() {
        if (this.gspNum < this.gspNumMax) {
            int k = this.gspNum + 1;
            double hk = -1.0 + 2.0 * (k - 1.0) / (this.gspNumMax - 1.0);
            this.theta = Math.acos(hk);

            double prev_lon = this.phi;
            double lon = prev_lon + 3.6 / Math.sqrt(this.gspNumMax) / Math.sqrt(1.0 - hk * hk);
            this.phi = lon % (Math.PI * 2);
        } else {
            this.theta = 0.0;
            this.phi = 0.0;
        }
        this.gspNum++;
    }

    @Override
    public void cacheChunksTick(int processTimeMs) {
        if (!isAusf3Complete) {
            // time ignored here since collectTip() did not implement a time limit
            collectTip(speed * 10);
        }
    }

    @Override
    public void destructionTick(int processTimeMs) {
        if (!isAusf3Complete) return;
        long start = System.currentTimeMillis();
        while (!perChunk.isEmpty() && System.currentTimeMillis() < start + processTimeMs)
            processChunk();
    }

    @Override
    public void cancel() {
        isAusf3Complete = true;
        perChunk.clear();
        orderedChunks.clear();
    }

    @Override
    public boolean isComplete() {
        return isAusf3Complete && perChunk.isEmpty();
    }

    // Orders chunks by Manhattan distance from the blast center so nearer chunks are cleared first.
    public class CoordComparator implements Comparator<ChunkPos> {
        @Override
        public int compare(ChunkPos o1, ChunkPos o2) {
            int diff1 = Math.abs((chunkPos.x - o1.x)) + Math.abs((chunkPos.z - o1.z));
            int diff2 = Math.abs((chunkPos.x - o2.x)) + Math.abs((chunkPos.z - o2.z));
            return diff1 - diff2;
        }
    }
}
