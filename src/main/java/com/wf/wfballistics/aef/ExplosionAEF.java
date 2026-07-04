package com.wf.wfballistics.aef;

import com.wf.wfballistics.aef.interfaces.*;
import com.wf.wfballistics.aef.standard.*;
import com.wf.wfballistics.compat.WarforgeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/**
 * A composable replacement for {@link net.minecraft.world.level.Explosion} — the core of the
 * Advanced Explosion Framework (AEF), ported from HBM's "Vanilla New Technology" (VNT) explosion.
 *
 * <p>Instead of one monolithic explosion class with a pile of boolean flags, a blast is assembled from
 * five interchangeable strategy objects, each owning one stage of the {@link #explode()} pipeline:
 *
 * <ol>
 *   <li>{@link IBlockAllocator}  — ray-marches outward to collect the block positions the blast reaches.</li>
 *   <li>{@link IEntityProcessor} — damages and knocks back entities, returning the player knockback map.</li>
 *   <li>{@link IBlockProcessor}  — drops / removes / converts the allocated blocks.</li>
 *   <li>{@link IPlayerProcessor} — forwards the knockback map to the affected players' clients.</li>
 *   <li>{@link IExplosionSFX}[]  — emits sound and particles (one entry per discrete effect).</li>
 * </ol>
 *
 * <p>Block and entity handling are independent: an explosion with no allocator simply does no terrain
 * damage, one with no entity processor harms nothing. {@link #explode()} skips any stage whose
 * strategies are absent, so partial configurations are valid.
 *
 * <p><b>Threading / side:</b> call {@link #explode()} on the server thread. The gameplay stages mutate the
 * world; the SFX stage is responsible for broadcasting to clients.
 *
 * <p>Typical use:
 * <pre>{@code
 * new ExplosionVNT(level, x, y, z, 6f, source).makeStandard().explode();
 * }</pre>
 */
public class ExplosionAEF {

    public final Level level;
    public final double posX;
    public final double posY;
    public final double posZ;
    public final float size;
    /**
     * The entity that caused the blast, or {@code null} for an unattributed/world explosion.
     */
    public final Entity exploder;
    /**
     * A throwaway vanilla {@link Explosion} carrying this blast's parameters. It exists purely so the
     * framework can reuse vanilla / Forge hooks that require an {@code Explosion} argument — block
     * explosion resistance, {@code Entity#shouldBlockExplode}, the {@code ExplosionEvent.Detonate} Forge
     * event, and explosion {@link net.minecraft.world.damagesource.DamageSource}s.
     */
    public final Explosion compat;
    // One of each gameplay strategy is enough; chain-load via a wrapper if you ever need to combine them.
    private IBlockAllocator blockAllocator;
    private IEntityProcessor entityProcessor;
    private IBlockProcessor blockProcessor;
    private IPlayerProcessor playerProcessor;
    // SFX are deliberately plural and granular (bang, smoke, flash, ...) so they can be mixed per blast.
    private IExplosionSFX[] sfx;
    // When false (default), the blast respects WarForge land claims (protected blocks survive, unless the
    // chunk is in an active siege zone). Set true for a blast that should flatten claimed land regardless.
    private boolean bypassClaims = false;

    public ExplosionAEF(Level level, double x, double y, double z, float size) {
        this(level, x, y, z, size, null);
    }

    /**
     * Convenience: detonate at the centre of a block.
     */
    public ExplosionAEF(Level level, BlockPos pos, float size) {
        this(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, size, null);
    }

    public ExplosionAEF(Level level, double x, double y, double z, float size, Entity exploder) {
        this.level = level;
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.size = size;
        this.exploder = exploder;
        this.compat = new Explosion(level, exploder, x, y, z, size, false, Explosion.BlockInteraction.DESTROY);
    }

    /**
     * Runs the configured pipeline. Stages whose strategies are missing are skipped, so this is safe to
     * call on a partially configured blast.
     */
    public void explode() {
        boolean processBlocks = blockAllocator != null && blockProcessor != null;
        boolean processEntities = entityProcessor != null && playerProcessor != null;

        Set<BlockPos> affectedBlocks = null;
        Map<Player, Vec3> affectedPlayers = null;

        // 1 + 2: gather targets before anything is mutated.
        if (processBlocks) affectedBlocks = blockAllocator.allocate(this, level, posX, posY, posZ, size);
        if (processEntities) affectedPlayers = entityProcessor.process(this, level, posX, posY, posZ, size);

        // Respect WarForge land claims unless this blast bypasses them: drop protected positions before the
        // block processor destroys anything. WarForge already permits destruction in active siege zones, so a
        // besieged base can still be hit. No-op when WarForge isn't installed.
        if (processBlocks && !this.bypassClaims) {
            WarforgeCompat.filterClaimProtected(level, affectedBlocks);
        }

        // 3 + 4: apply effects.
        if (processBlocks) blockProcessor.process(this, level, posX, posY, posZ, affectedBlocks);
        if (processEntities) playerProcessor.process(this, level, posX, posY, posZ, affectedPlayers);

        // Mirror the surviving block set onto the compat explosion so SFX (and other mods reading the
        // Forge event) see the same positions vanilla would have populated.
        if (processBlocks) compat.getToBlow().addAll(affectedBlocks);

        // 5: sound and particles.
        if (sfx != null) {
            for (IExplosionSFX fx : sfx) {
                fx.doEffect(this, level, posX, posY, posZ, size);
            }
        }
    }

    public ExplosionAEF setBlockAllocator(IBlockAllocator blockAllocator) {
        this.blockAllocator = blockAllocator;
        return this;
    }

    public ExplosionAEF setEntityProcessor(IEntityProcessor entityProcessor) {
        this.entityProcessor = entityProcessor;
        return this;
    }

    public ExplosionAEF setBlockProcessor(IBlockProcessor blockProcessor) {
        this.blockProcessor = blockProcessor;
        return this;
    }

    public ExplosionAEF setPlayerProcessor(IPlayerProcessor playerProcessor) {
        this.playerProcessor = playerProcessor;
        return this;
    }

    public ExplosionAEF setSFX(IExplosionSFX... sfx) {
        this.sfx = sfx;
        return this;
    }

    /**
     * When true, this blast ignores WarForge land claims and destroys protected blocks anyway
     */
    public ExplosionAEF bypassClaims(boolean bypassClaims) {
        this.bypassClaims = bypassClaims;
        return this;
    }


    public ExplosionAEF makeStandard() {
        this.setBlockAllocator(new BlockAllocatorStandard());
        this.setBlockProcessor(new BlockProcessorStandard());
        this.setEntityProcessor(new EntityProcessorCross());
        this.setPlayerProcessor(new PlayerProcessorStandard());
        this.setSFX(new ExplosionEffectLarge());
        return this;
    }


    public ExplosionAEF makeAmat() {
        this.setBlockAllocator(new BlockAllocatorStandard(this.size < 15 ? 16 : 32));
        this.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        this.setEntityProcessor(new EntityProcessorCross()
                .withRangeMod(2F)
                .withDamageMod(new CustomDamageHandlerAmat(50F)));
        this.setPlayerProcessor(new PlayerProcessorStandard());
        this.setSFX(new ExplosionEffectAmat());
        return this;
    }
}
