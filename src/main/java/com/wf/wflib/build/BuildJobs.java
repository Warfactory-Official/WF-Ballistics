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

    /** {@code supply}: station code to draw from, or null to let the worker pick. */
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

    /** {@code depot}: station code to return material to, or null for whatever the worker calls home. */
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

    /** Null: no such job (a mission saved across a reload that outlived its job flies as an ordinary one). */
    @Nullable
    public static WorkAssignment joining(UUID jobId, ServerLevel level) {
        WorkJob job = WorkRegistry.get(level).byId(jobId);
        return job == null ? null
                : new WorkAssignment(jobId, job.kind(), -1, null, 0, null, job.workable());
    }

    @Nullable
    public static String blueprintOf(WorkJob job) {
        return job.kind().equals(CONSTRUCT) && job.detail().contains(BLUEPRINT)
                ? job.detail().getString(BLUEPRINT) : null;
    }

    @Nullable
    public static String stationOf(WorkJob job) {
        String key = job.kind().equals(CONSTRUCT) ? SUPPLY : DEPOT;
        return job.detail().contains(key) ? job.detail().getString(key) : null;
    }

    /** Null: console-started, or started by someone with no faction. */
    @Nullable
    public static UUID factionOf(WorkJob job) {
        return job.detail().hasUUID(FACTION) ? job.detail().getUUID(FACTION) : null;
    }
}
