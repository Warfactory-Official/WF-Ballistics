package com.wf.wflib.build;

import com.wf.wflib.WFLib;
import com.wf.wflib.work.WorkAssignment;
import com.wf.wflib.work.WorkJob;
import com.wf.wflib.work.WorkQueue;
import com.wf.wflib.work.WorkRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** The two kinds of job the build system creates, and the shape of the detail tag each carries. */
public final class BuildJobs {

    public static final ResourceLocation CONSTRUCT = rl("construct");
    public static final ResourceLocation SALVAGE = rl("salvage");

    private static final String BLUEPRINT = "Blueprint";
    private static final String SUPPLY = "Supply";
    private static final String DEPOT = "Depot";
    private static final String FACTION = "Faction";

    private BuildJobs() {
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    /**
     * @param blueprint the blueprint's file name, so a job reloaded from disk can find it again
     * @param supply station code to draw materials from, or null to leave it to whoever works the job
     */
    public static WorkJob construct(ServerLevel level, WorkPlan plan, String blueprint,
                                    @Nullable String supply, @Nullable UUID faction) {
        CompoundTag detail = new CompoundTag();
        detail.putString(BLUEPRINT, blueprint);
        if (supply != null) {
            detail.putString(SUPPLY, supply);
        }
        if (faction != null) {
            detail.putUUID(FACTION, faction);
        }
        return new WorkJob(UUID.randomUUID(), CONSTRUCT, level.dimension(), plan.bounds(),
                "build " + blueprint, level.getGameTime(), WorkQueue.of(plan.orders()), detail);
    }

    /**
     * @param depot station code to bring the recovered material back to, or null for whatever the worker
     *      calls home
     */
    public static WorkJob salvage(ServerLevel level, WorkPlan plan, @Nullable String depot,
                                  @Nullable UUID faction) {
        CompoundTag detail = new CompoundTag();
        if (depot != null) {
            detail.putString(DEPOT, depot);
        }
        if (faction != null) {
            detail.putUUID(FACTION, faction);
        }
        String where = plan.bounds().getCenter().toShortString();
        return new WorkJob(UUID.randomUUID(), SALVAGE, level.dimension(), plan.bounds(),
                "salvage " + where, level.getGameTime(), WorkQueue.of(plan.orders()), detail);
    }

    /**
     * A blank assignment for a drone joining {@code jobId}: no order, no station.
     *
     * @return null if there is no such job, so a mission saved across a reload that outlived its job simply
     *      flies as an ordinary one instead of chasing a ghost
     */
    @Nullable
    public static WorkAssignment joining(UUID jobId, ServerLevel level) {
        WorkJob job = WorkRegistry.get(level).byId(jobId);
        return job == null ? null
                : new WorkAssignment(jobId, job.kind(), -1, null, 0, null, job.workable());
    }

    /**
     * @return the blueprint a construction job builds, or null if this is not one.
     */
    @Nullable
    public static String blueprintOf(WorkJob job) {
        return job.kind().equals(CONSTRUCT) && job.detail().contains(BLUEPRINT)
                ? job.detail().getString(BLUEPRINT) : null;
    }

    /**
     * @return the station this job draws materials from, or returns them to, or null if it was not given one.
     */
    @Nullable
    public static String stationOf(WorkJob job) {
        String key = job.kind().equals(CONSTRUCT) ? SUPPLY : DEPOT;
        return job.detail().contains(key) ? job.detail().getString(key) : null;
    }

    /**
     * @return the faction this job is being done on behalf of, or null if it was started by somebody with no
     *      faction, or from the console, which has none.
     */
    @Nullable
    public static UUID factionOf(WorkJob job) {
        return job.detail().hasUUID(FACTION) ? job.detail().getUUID(FACTION) : null;
    }
}
