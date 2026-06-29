package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IBlockMutator;
import com.wf.wfballistics.aef.interfaces.IBlockProcessor;
import com.wf.wfballistics.aef.interfaces.IDropChanceMutator;
import com.wf.wfballistics.aef.interfaces.IFortuneMutator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The standard destructive block processor: for every allocated position it optionally rolls item drops,
 * removes the block, and optionally hands the (now-cleared) position to an {@link IBlockMutator} so the
 * blast can leave fire, rubble, etc. in its wake.
 *
 * <p>Configured fluently:
 * <pre>{@code
 * new BlockProcessorStandard().setNoDrop().withBlockEffect(new BlockMutatorFire())
 * }</pre>
 *
 * <p><b>Drop chance</b> defaults to the vanilla {@code 1 / size} and can be overridden per block via an
 * {@link IDropChanceMutator}. <b>Fortune</b> is exposed through {@link IFortuneMutator} for API parity, but
 * 1.20's loot-table drops are tool-driven; wiring fortune into the loot context is left as an extension
 * point (the mutator is queried but the value is not yet threaded into {@link Block#dropResources}).
 */
public class BlockProcessorStandard implements IBlockProcessor {

    protected IDropChanceMutator chance;
    protected IFortuneMutator fortune;
    protected IBlockMutator convert;

    public BlockProcessorStandard() { }

    public BlockProcessorStandard withChance(IDropChanceMutator chance) {
        this.chance = chance;
        return this;
    }

    public BlockProcessorStandard withFortune(IFortuneMutator fortune) {
        this.fortune = fortune;
        return this;
    }

    public BlockProcessorStandard withBlockEffect(IBlockMutator convert) {
        this.convert = convert;
        return this;
    }

    @Override
    public void process(ExplosionAEF explosion, Level level, double x, double y, double z, Set<BlockPos> affectedBlocks) {
        float dropChance = 1.0F / explosion.size;
        List<BlockPos> empties = new ArrayList<>();

        for (Iterator<BlockPos> it = affectedBlocks.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) {
                // The allocator may have included air pockets along a ray; drop them so the post pass and
                // the SFX debris only consider blocks that were actually there.
                empties.add(pos);
                continue;
            }

            if (state.getBlock().dropFromExplosion(explosion.compat)) {
                float roll = chance != null
                        ? chance.mutateDropChance(explosion, state, pos.getX(), pos.getY(), pos.getZ(), dropChance)
                        : dropChance;
                if (fortune != null) {
                    fortune.mutateFortune(explosion, state, pos.getX(), pos.getY(), pos.getZ());
                }
                if (roll > 0F && level.random.nextFloat() <= roll) {
                    BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
                    Block.dropResources(state, level, pos, be);
                }
            }

            // Removes the block and fires wasExploded (block entities, observers, etc.).
            state.getBlock().onBlockExploded(state, level, pos, explosion.compat);

            if (convert != null) {
                convert.mutatePre(explosion, state, pos);
            }
        }

        affectedBlocks.removeAll(empties);

        if (convert != null) {
            for (BlockPos pos : affectedBlocks) {
                if (level.getBlockState(pos).isAir()) {
                    convert.mutatePost(explosion, pos);
                }
            }
        }
    }

    /** No block ever drops items (clean vaporising blast). */
    public BlockProcessorStandard setNoDrop() {
        this.chance = new DropChanceMutatorStandard(0F);
        return this;
    }

    /** Every block drops its items (loot-friendly mining charge). */
    public BlockProcessorStandard setAllDrop() {
        this.chance = new DropChanceMutatorStandard(1F);
        return this;
    }

    public BlockProcessorStandard setFortune(int level) {
        this.fortune = (explosion, state, x, y, z) -> level;
        return this;
    }
}
