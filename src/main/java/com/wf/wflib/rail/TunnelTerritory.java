package com.wf.wflib.rail;

import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.TunnelProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whose ground a tunnel would be dug through, and whether the digger is allowed to.
 *
 * <p><b>The excavator bypasses every block protection there is.</b> A carve of an unloaded chunk
 * rewrites its stored NBT directly, off a worker thread, so no place-event fires and
 * {@code ProtectionsModule} never sees it. That makes this check the only thing standing between a
 * faction and a tunnel driven straight under someone else's citadel, and it is why it lives here rather
 * than being left to the ordinary protection hooks.</p>
 *
 * <p>Answered once on the server thread, before anything is queued, and the permitted chunks are then
 * carried as a plain set. WarForge's faction storage is server-thread state and a carve worker must
 * never read it.</p>
 */
public final class TunnelTerritory {

    /** How finely the route is walked. Fine enough that a single claimed chunk is not stepped over. */
    private static final double SAMPLE_STEP = 4.0;

    private TunnelTerritory() {
    }

    /**
     * @param allowed chunks the builder may dig in, as packed {@link ChunkPos} longs
     * @param refusals every chunk they may not, with the reason, in the order the route meets them
     */
    public record Survey(Set<Long> allowed, Map<ChunkPos, TerritoryVerdict> refusals) {

        public boolean clear() {
            return this.refusals.isEmpty();
        }

        /** @return the refusals phrased for a player, worst first, at most {@code limit} of them. */
        public List<String> reasons(int limit) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<ChunkPos, TerritoryVerdict> entry : this.refusals.entrySet()) {
                if (out.size() >= limit) {
                    out.add("and " + (this.refusals.size() - limit) + " more chunk(s)");
                    break;
                }
                out.add("chunk " + entry.getKey().x + ", " + entry.getKey().z + ": "
                        + entry.getValue().reason());
            }
            return out;
        }
    }

    /**
     * Walk the route and ask WarForge about every chunk the tunnel would touch.
     *
     * @param faction the faction doing the digging, or null for a player with none
     */
    public static Survey survey(ServerLevel level, UUID faction, CarveVolume.Corridor path,
                                TunnelProfile profile, int floorY) {
        Set<Long> allowed = new HashSet<>();
        Map<ChunkPos, TerritoryVerdict> refusals = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();

        // The section's own width, plus the lining, plus a block of slack: the shell reaches one block
        // beyond the bore and a chunk touched only by the wall is still a chunk being built in.
        double reach = profile.width() / 2.0 + 1.0;
        double length = Math.max(path.length(), SAMPLE_STEP);

        for (double s = 0.0; s <= length + SAMPLE_STEP; s += SAMPLE_STEP) {
            double[] at = path.pointAt(Math.min(s, path.length()));
            double nx = -at[3];
            double nz = at[2];
            for (double offset = -reach; offset <= reach; offset += SAMPLE_STEP) {
                check(level, faction, floorY, at[0] + nx * offset, at[1] + nz * offset, seen, allowed,
                        refusals);
            }
            // The edges explicitly: a step of four blocks would otherwise miss the outermost course.
            check(level, faction, floorY, at[0] + nx * reach, at[1] + nz * reach, seen, allowed, refusals);
            check(level, faction, floorY, at[0] - nx * reach, at[1] - nz * reach, seen, allowed, refusals);
        }
        return new Survey(allowed, refusals);
    }

    /**
     * Ask about one chunk, the same way the survey does.
     *
     * <p>A machine that works its way along a route over minutes cannot rely on an answer given at
     * launch: a claim can be planted in front of it while it runs. It asks this as the face reaches each
     * new chunk, which costs one lookup per chunk of the route and makes the survey a warning rather
     * than the rule.</p>
     */
    public static TerritoryVerdict verdict(ServerLevel level, UUID faction, ChunkPos chunk, int y) {
        return WarforgeCompat.buildVerdict(level, faction,
                new BlockPos(chunk.getMiddleBlockX(), y, chunk.getMiddleBlockZ()));
    }

    private static void check(ServerLevel level, UUID faction, int floorY, double x, double z,
                              Set<Long> seen, Set<Long> allowed, Map<ChunkPos, TerritoryVerdict> refusals) {
        ChunkPos chunk = new ChunkPos(BlockPos.containing(x, floorY, z));
        if (!seen.add(chunk.toLong())) {
            return;
        }
        // The same question the drones ask, so a tunnel obeys the rule everything else in the mod does.
        TerritoryVerdict verdict = WarforgeCompat.buildVerdict(level, faction,
                new BlockPos(chunk.getMiddleBlockX(), floorY, chunk.getMiddleBlockZ()));
        if (verdict.allowed()) {
            allowed.add(chunk.toLong());
        } else {
            refusals.put(chunk, verdict);
        }
    }
}
