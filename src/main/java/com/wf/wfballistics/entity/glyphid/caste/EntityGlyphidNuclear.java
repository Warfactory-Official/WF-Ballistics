package com.wf.wfballistics.entity.glyphid.caste;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.standard.BlockAllocatorStandard;
import com.wf.wfballistics.aef.standard.BlockMutatorDebris;
import com.wf.wfballistics.aef.standard.BlockProcessorStandard;
import com.wf.wfballistics.aef.standard.EntityProcessorCross;
import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.GlyphidWaypoint;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A five-second warning with legs. Death does not remove it, it starts a fuse, so killing one at the wrong
 * moment is worse than not killing it.
 *
 * <p>{@link #communicate} here reaches only scouts: a nuclear glyphid's orders are about where the colony
 * expands to next, not about what the warband is biting.
 */
public class EntityGlyphidNuclear extends EntityGlyphid {

    /** Ticks from death to detonation. */
    private static final int FUSE = 100;
    /** When the neighbours get their parting gift: late enough that it cannot be farmed from out of range. */
    private static final int BLESSING_AT = 90;
    private static final int PING_INTERVAL = 10;
    private static final int BLESSING_RADIUS = 8;

    private static final float BLAST_SIZE = 25.0F;
    /**
     * Allocator resolution. The blast is large enough that the default ray count leaves visible gaps in the
     * crater wall.
     */
    private static final int BLAST_RESOLUTION = 24;

    private int fuse;
    private boolean signalled;

    public EntityGlyphidNuclear(EntityType<? extends EntityGlyphidNuclear> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getNuclear();
        return EntityGlyphid.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getNuclear();
    }


    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean isNuclearType() {
        return true;
    }

    @Override
    public boolean doesInfectedSpawnMaggots() {
        return false;
    }

    /** Only scouts listen. See the class note. */
    @Override
    public void communicate(int task, @Nullable GlyphidWaypoint waypoint) {
        int radius = waypoint != null ? waypoint.radius : 4;
        AABB box = new AABB(getX(), getY(), getZ(), getX(), getY(), getZ()).inflate(radius);

        List<Entity> nearby = level().getEntities(this, box);
        for (Entity entity : nearby) {
            if (entity instanceof EntityGlyphid bug && bug.isScoutType() && bug.getCurrentTask() != task) {
                bug.setCurrentTask(task, waypoint);
            }
        }
    }

    /**
     * Replaces the vanilla death animation outright rather than running alongside it: the entity has to stay
     * in the world for the whole fuse, and vanilla discards it after twenty ticks.
     */
    @Override
    protected void tickDeath() {
        fuse++;

        if (!signalled) {
            communicate(GlyphidTasks.TASK_INITIATE_RETREAT, null);
            signalled = true;
        }

        if (fuse == BLESSING_AT) {
            blessNeighbours();
        }

        if (fuse >= FUSE) {
            detonate();
            discard();
            return;
        }

        if (!level().isClientSide && fuse % PING_INTERVAL == 0) {
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.NOTE_BLOCK_PLING.value(),
                    SoundSource.HOSTILE, 5.0F, 0.5F);
        }
    }

    /**
     * Upstream applies these to the corpse rather than to the bugs it found, which does nothing. The intent
     * is plainly the opposite: the swarm around a detonating nuclear glyphid walks out of the fireball.
     */
    private void blessNeighbours() {
        AABB box = new AABB(getX(), getY(), getZ(), getX(), getY(), getZ()).inflate(BLESSING_RADIUS);

        for (Entity entity : level().getEntities(this, box)) {
            if (entity instanceof EntityGlyphid bug) {
                bug.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20, 6));
                bug.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 15 * 20, 1));
            }
        }
    }

    private void detonate() {
        if (level().isClientSide) {
            return;
        }

        ExplosionAEF blast = new ExplosionAEF(level(), getX(), getY(), getZ(), BLAST_SIZE, this);
        blast.setBlockAllocator(new BlockAllocatorStandard(BLAST_RESOLUTION));
        blast.setBlockProcessor(new BlockProcessorStandard()
                .withBlockEffect(new BlockMutatorDebris(Blocks.MAGMA_BLOCK))
                .setNoDrop());
        blast.setEntityProcessor(new EntityProcessorCross());
        blast.setPlayerProcessor(new PlayerProcessorStandard());
        blast.explode();

        level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.HOSTILE, 15.0F, 1.0F);
    }

    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.12, 2), 100);
    }
}
