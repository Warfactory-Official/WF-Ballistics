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
import java.util.UUID;

/**
 * A composable replacement for {@link net.minecraft.world.level.Explosion}: the core of the Advanced Explosion
 * Framework (AEF), ported from HBM's "Vanilla New Technology" (VNT) explosion.
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
    /** A throwaway vanilla {@link Explosion} carrying this blast's parameters. */
    public final Explosion compat;
    // One of each gameplay strategy is enough; chain-load via a wrapper if you ever need to combine them.
    private IBlockAllocator blockAllocator;
    private IEntityProcessor entityProcessor;
    private IBlockProcessor blockProcessor;
    private IPlayerProcessor playerProcessor;
    // SFX are deliberately plural and granular (bang, smoke, flash, ...) so they can be mixed per blast.
    private IExplosionSFX[] sfx;
    private boolean bypassClaims = false;
    private UUID igniterFaction = null;

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

    /** Runs the configured pipeline. */
    public void explode() {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.wf.wfballistics.recon.event.SeismicEvents.report(serverLevel, posX, posY, posZ, size);
        }

        com.wf.wfballistics.debug.ExplosionTrace.begin(new Vec3(posX, posY, posZ), size,
                level.getGameTime());

        boolean processBlocks = blockAllocator != null && blockProcessor != null;
        boolean processEntities = entityProcessor != null && playerProcessor != null;

        Set<BlockPos> affectedBlocks = null;
        Map<Player, Vec3> affectedPlayers = null;

        // 1 + 2: gather targets before anything is mutated.
        if (processBlocks) affectedBlocks = blockAllocator.allocate(this, level, posX, posY, posZ, size);
        if (processEntities) affectedPlayers = entityProcessor.process(this, level, posX, posY, posZ, size);

        if (processBlocks && !this.bypassClaims) {
            WarforgeCompat.filterClaimProtected(level, this.igniterFaction, affectedBlocks);
        }

        // 3 + 4: apply effects.
        if (processBlocks) blockProcessor.process(this, level, posX, posY, posZ, affectedBlocks);
        if (processEntities) playerProcessor.process(this, level, posX, posY, posZ, affectedPlayers);

        if (processBlocks) compat.getToBlow().addAll(affectedBlocks);

        com.wf.wfballistics.debug.ExplosionTrace.end();

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

    /**
     * Attribute this blast to a WarForge faction (typically a missile's {@code teamId}) so claim filtering is
     * evaluated from that faction's perspective: it may breach a claim it is actively besieging and is stopped by
     * claims it may not touch.
     */
    public ExplosionAEF igniterFaction(UUID igniterFaction) {
        this.igniterFaction = igniterFaction;
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

    /**
     * A directional shaped charge (Munroe / HEAT): a narrow forward cone about {@code direction} whose on-axis jet
     * drills deep while the cone mouth blows a shallow crater.
     *
     * @param direction the jet axis
     * @param halfAngleDeg cone half-angle in degrees (tighter = deeper, narrower)
     * @param jetPower on-axis power multiplier applied to {@link #size}: the penetration knob (&gt; 1 punches
     *      through blocks a same-size sphere couldn't)
     */
    public ExplosionAEF makeShapedCharge(Vec3 direction, float halfAngleDeg, float jetPower) {
        this.setBlockAllocator(new BlockAllocatorShapedCharge(direction, halfAngleDeg, jetPower));
        this.setBlockProcessor(new BlockProcessorStandard().setNoDrop());
        this.setEntityProcessor(new EntityProcessorCone(direction, halfAngleDeg));
        this.setPlayerProcessor(new PlayerProcessorStandard());
        this.setSFX(new ExplosionEffectStandard());
        return this;
    }

    /** Shaped charge with sensible defaults (see {@link BlockAllocatorShapedCharge}: a ~22° cone, 4x jet). */
    public ExplosionAEF makeShapedCharge(Vec3 direction) {
        return makeShapedCharge(direction, BlockAllocatorShapedCharge.DEFAULT_HALF_ANGLE_DEG,
                BlockAllocatorShapedCharge.DEFAULT_JET_POWER);
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
