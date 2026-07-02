package com.wf.wfballistics.chunk;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.sim.MissileSimConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;


public final class MissileChunkLoader {
    private LongOpenHashSet ticking = new LongOpenHashSet();
    private LongOpenHashSet nonTicking = new LongOpenHashSet();


    public void update(MissileEntity missile, ServerLevel level, Vec3 pos, Vec3 vel, boolean loadFan) {
        long ownChunk = missile.chunkPosition().toLong();

        LongOpenHashSet desiredTicking = new LongOpenHashSet();
        desiredTicking.add(ownChunk);


        LongOpenHashSet desiredNonTicking = new LongOpenHashSet();
        if (loadFan) {
            collectFan(desiredNonTicking, pos, vel);
            desiredNonTicking.remove(ownChunk); // own chunk is handled as ticking. Totally hasn't caused me to get stuck for a while
        }

        for (long key : desiredTicking) {
            if (!ticking.contains(key)) {
                force(level, missile, key, true, true);
            }
        }
        for (long key : desiredNonTicking) {
            if (!nonTicking.contains(key)) {
                force(level, missile, key, true, false);
            }
        }
        for (LongIterator it = ticking.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!desiredTicking.contains(key)) {
                force(level, missile, key, false, true);
            }
        }
        for (LongIterator it = nonTicking.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!desiredNonTicking.contains(key)) {
                force(level, missile, key, false, false);
            }
        }

        this.ticking = desiredTicking;
        this.nonTicking = desiredNonTicking;
    }

    public void releaseAll(MissileEntity missile, ServerLevel level) {
        for (LongIterator it = ticking.iterator(); it.hasNext(); ) {
            force(level, missile, it.nextLong(), false, true);
        }
        for (LongIterator it = nonTicking.iterator(); it.hasNext(); ) {
            force(level, missile, it.nextLong(), false, false);
        }
        ticking.clear();
        nonTicking.clear();
    }

    private static void force(ServerLevel level, MissileEntity missile, long chunkKey, boolean add, boolean ticking) {
        ForgeChunkManager.forceChunk(level, WFBallistics.MODID, missile,
                ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), add, ticking);
    }


    private static void collectFan(LongOpenHashSet out, Vec3 pos, Vec3 vel) {
        double dirX = 0.0;
        double dirZ = 0.0;
        double horiz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (horiz > 1.0E-4) {
            dirX = vel.x / horiz;
            dirZ = vel.z / horiz;
        }

        double aheadX = pos.x + dirX * MissileSimConfig.FAN_LOOKAHEAD;
        double aheadZ = pos.z + dirZ * MissileSimConfig.FAN_LOOKAHEAD;

        double minX = Math.min(pos.x, aheadX) - MissileSimConfig.FAN_LATERAL;
        double maxX = Math.max(pos.x, aheadX) + MissileSimConfig.FAN_LATERAL;
        double minZ = Math.min(pos.z, aheadZ) - MissileSimConfig.FAN_LATERAL;
        double maxZ = Math.max(pos.z, aheadZ) + MissileSimConfig.FAN_LATERAL;

        int minCX = SectionPos.blockToSectionCoord(minX) - 1;
        int maxCX = SectionPos.blockToSectionCoord(maxX) + 1;
        int minCZ = SectionPos.blockToSectionCoord(minZ) - 1;
        int maxCZ = SectionPos.blockToSectionCoord(maxZ) + 1;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                out.add(ChunkPos.asLong(cx, cz));
            }
        }
    }
}
