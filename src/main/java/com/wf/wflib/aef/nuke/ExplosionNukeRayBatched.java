package com.wf.wflib.aef.nuke;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Ported from HBM; magic constants below unexplained upstream. */
public class ExplosionNukeRayBatched implements IExplosionRay {
    // TODO: sub-chunks instead of chunks.
    /**
     * Value: one shared endpoint {@link Vec3} per ray, pushed into every chunk the ray crosses (may sit outside
     * it); {@link #processChunk} re-derives + re-walks the ray per chunk.
     */
    private final HashMap<ChunkPos, List<Vec3>> perChunk = new HashMap<>();
    private final List<ChunkPos> orderedChunks = new ArrayList<>();
    private final CoordComparator comparator = new CoordComparator();
    private final BlockPos pos;
    private final ChunkPos chunkPos;

    private final Level level;

    private final int strength;
    private final int radius;
    private final int speed;
    private final int gspNumMax;
    int collectTipCnt = 0;
    int processChunkCnt = 0;
    private int gspNum;
    /** theta: polar; phi: azimuthal. */
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
        this.gspNumMax = (int) (2.5 * Math.PI * Math.pow(this.strength, 2));
        this.gspNum = 1;

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
            Vec3 vec = spherical2cartesian(new Vec2((float) theta, (float) phi));

            int length = (int) Math.ceil(strength);
            // res: remaining shockwave intensity, depleted per block.
            float res = strength;
            Vec3 endPoint = null;
            HashSet<ChunkPos> chunkCoords = new HashSet();
            for (int i = 0; i < length && i < this.radius && res > 0; i++) {
                Vec3 dirVec = new Vec3(pos.getX(), pos.getY(), pos.getZ()).add(vec.scale(i));
                BlockPos dirPos = new BlockPos((int) Math.floor(dirVec.x), (int) Math.floor(dirVec.y), (int) Math.floor(dirVec.z));
                BlockState blockState = level.getBlockState(dirPos);
                // fac: resistance-exponent falloff curve; magic constants ported from HBM.
                double fac = 100 - ((double) i) / ((double) length) * 100;
                fac *= 0.07D;

                // FIXME: clears `pos` (blast center), not `dirPos` (this ray cell); looks wrong, preserved as-is.
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
                endList.add(endPoint);
            }

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
        HashSet<BlockPos> toRem = new HashSet<>();
        HashSet<BlockPos> toRemTips = new HashSet<>();

        // enter: skips ray cells that can't reach this chunk yet (avoids no-op iterations).
        int enter = Math.min(Math.abs(pos.getX() - (coord.x << 4)), Math.abs(pos.getZ() - (coord.z << 4))) - 16;

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

        // Bulk clear: flag 2, no recursion (0) => no neighbor-update cascade across the mass removal.
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

    /** Tip (ray endpoint): flag 3, full neighbor update, unlike the bulk clear's flag 2. */
    protected void handleTip(BlockPos blockPos) {
        level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3);
    }

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
            // processTimeMs ignored: collectTip has no time limit.
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

    /** Manhattan distance to the blast center, ascending: nearer chunks cleared first. */
    public class CoordComparator implements Comparator<ChunkPos> {
        @Override
        public int compare(ChunkPos o1, ChunkPos o2) {
            int diff1 = Math.abs((chunkPos.x - o1.x)) + Math.abs((chunkPos.z - o1.z));
            int diff2 = Math.abs((chunkPos.x - o2.x)) + Math.abs((chunkPos.z - o2.z));
            return diff1 - diff2;
        }
    }
}
