package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Stage 3 of the explosion pipeline: applies an effect to every position chosen by the
 * {@link IBlockAllocator} (drop items, remove blocks, convert to fire/rubble, ...).
 * <p>
 * Runs server-side only. Implementations are free to remove entries from {@code affectedBlocks} — the
 * standard SFX reads this set afterwards to spawn per-block debris particles, so a processor that wants
 * to suppress those particles can simply clear the set (see
 * {@link com.wf.wfballistics.aef.standard.BlockProcessorNoDamage}).
 */
public interface IBlockProcessor {

    /**
     * @param affectedBlocks the positions produced by the allocator; this method owns them and may mutate
     *                       the set in place
     */
    void process(ExplosionAEF explosion, Level level, double x, double y, double z, Set<BlockPos> affectedBlocks);
}
