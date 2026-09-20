package com.wf.wflib.build;

import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.work.WorkJob;
import com.wf.wflib.work.WorkQueue;
import com.wf.wflib.work.WorkRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The per-tick housekeeping a construction site needs that the generic {@code work} package must not know about:
 * whether the ground is still ours, and keeping the blocks being worked on loaded.
 */
public final class BuildManager {

    /** How far around a live claim to hold chunks loaded, in chunks. */
    private static final int CLAIM_CHUNK_RADIUS = 1;

    private BuildManager() {
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        boolean survey = now % SiteRules.RECHECK_TICKS == 0;
        for (WorkJob job : WorkRegistry.get(level).all()) {
            if (!job.dimension().equals(level.dimension()) || job.over()) {
                continue;
            }
            if (survey) {
                enforce(level, job);
            }
            if (job.workable()) {
                holdClaims(level, job);
            }
        }
    }

    /** Re-survey a running job and suspend or resume it. */
    private static void enforce(ServerLevel level, WorkJob job) {
        TerritoryVerdict verdict = SiteRules.survey(level, BuildJobs.factionOf(job), job.bounds());
        String reason = verdict.allowed() ? null : verdict.reason();
        if (job.suspend(reason)) {
            WorkRegistry.get(level).setDirty();
        }
    }

    /** Keep the chunks under this job's live claims loaded. */
    private static void holdClaims(ServerLevel level, WorkJob job) {
        WorkQueue queue = job.queue();
        for (int id : queue.claimedIds()) {
            BlockPos at = queue.order(id).at();
            DetonationChunkGuard.hold(level, Vec3.atCenterOf(at), CLAIM_CHUNK_RADIUS);
        }
    }
}
