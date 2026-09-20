package com.wf.wflib.build;

import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.compat.WarforgeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Whether a job is allowed to happen where it is. */
public final class SiteRules {

    /** How often a running job re-checks the ground it is standing on, in ticks. */
    public static final int RECHECK_TICKS = 200;

    private SiteRules() {
    }

    /**
     * Survey a whole volume.
     *
     * @return the first refusal found, or {@link TerritoryVerdict#ALLOWED}
     */
    public static TerritoryVerdict survey(ServerLevel level, @Nullable UUID faction, BoundingBox box) {
        return at(level, faction, box, null);
    }

    /**
     * @param where filled in with the first refused position, if given. The operator being told a build was
     *      refused deserves to be told which corner of it was the problem
     */
    public static TerritoryVerdict survey(ServerLevel level, @Nullable UUID faction, BoundingBox box,
                                          BlockPos.MutableBlockPos where) {
        return at(level, faction, box, where);
    }

    private static TerritoryVerdict at(ServerLevel level, @Nullable UUID faction, BoundingBox box,
                                       @Nullable BlockPos.MutableBlockPos where) {
        if (!WarforgeCompat.isActive() || !WarforgeCompat.territoryProtectionEnabled()) {
            return TerritoryVerdict.ALLOWED;
        }
        return walk(box, where, pos -> WarforgeCompat.buildVerdict(level, faction, pos));
    }

    /**
     * Walk the chunks a box touches, asking {@code probe} about one position in each.
     *
     * @param where filled in with the first refused position, if given
     */
    static TerritoryVerdict walk(BoundingBox box, @Nullable BlockPos.MutableBlockPos where,
                                 java.util.function.Function<BlockPos, TerritoryVerdict> probe) {
        int minChunkX = SectionPos.blockToSectionCoord(box.minX());
        int maxChunkX = SectionPos.blockToSectionCoord(box.maxX());
        int minChunkZ = SectionPos.blockToSectionCoord(box.minZ());
        int maxChunkZ = SectionPos.blockToSectionCoord(box.maxZ());
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                int x = Math.clamp(SectionPos.sectionToBlockCoord(cx, 8), box.minX(), box.maxX());
                int z = Math.clamp(SectionPos.sectionToBlockCoord(cz, 8), box.minZ(), box.maxZ());
                BlockPos sample = new BlockPos(x, box.minY(), z);
                TerritoryVerdict verdict = probe.apply(sample);
                if (!verdict.allowed()) {
                    if (where != null) {
                        where.set(sample);
                    }
                    return verdict;
                }
            }
        }
        return TerritoryVerdict.ALLOWED;
    }

    /**
     * The single-block backstop, asked immediately before a drone changes anything.
     */
    public static TerritoryVerdict mayTouch(ServerLevel level, @Nullable UUID faction, BlockPos pos) {
        return WarforgeCompat.buildVerdict(level, faction, pos);
    }
}
