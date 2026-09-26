package com.wf.wflib.stream.client;

import com.wf.wflib.stream.ChunkStreamAudit;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/** The client half of {@link ChunkStreamAudit}. */
public final class ClientChunkStreamAudit {

    private ClientChunkStreamAudit() {
    }

    /**
     * Writes {@code <file>} (held) and {@code <file>.undrawn} (held, all 8 neighbours held, Sodium not ready).
     *
     * @return {held, offView, undrawn}; undrawn -1 without Sodium
     */
    public static int[] run(ClientLevel level, String file) {
        OffViewChunks cache = (OffViewChunks) level.getChunkSource();
        LongOpenHashSet held = cache.wfHeldPositions();
        ChunkStreamAudit.write(FMLPaths.GAMEDIR.get().resolve(file), held);
        LongOpenHashSet undrawn = undrawn(level, held);
        if (undrawn != null) {
            ChunkStreamAudit.write(FMLPaths.GAMEDIR.get().resolve(file + ".undrawn"), undrawn);
        }
        return new int[]{held.size(), cache.wfOffViewPositions().size(), undrawn == null ? -1 : undrawn.size()};
    }

    @Nullable
    private static LongOpenHashSet undrawn(ClientLevel level, LongOpenHashSet held) {
        LongOpenHashSet ready = SodiumChunkProbe.readySet(level);
        if (ready == null) {
            return null;
        }
        LongOpenHashSet out = new LongOpenHashSet();
        held.forEach(pos -> {
            if (ready.contains(pos) || !interior(held, ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
                return;
            }
            out.add(pos);
        });
        return out;
    }

    private static boolean interior(LongOpenHashSet held, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!held.contains(ChunkPos.asLong(x + dx, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }
}
