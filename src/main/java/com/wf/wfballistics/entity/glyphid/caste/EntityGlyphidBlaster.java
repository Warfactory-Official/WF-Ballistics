package com.wf.wfballistics.entity.glyphid.caste;

import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** The bombardier that graduated to high explosive: faster, flatter and tighter than the acid it grew out of. */
public class EntityGlyphidBlaster extends EntityGlyphidBombardier {

    public EntityGlyphidBlaster(EntityType<? extends EntityGlyphidBlaster> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        GlyphidStats.StatBundle stats = GlyphidStats.getStats().getBlaster();
        return EntityGlyphidBombardier.createAttributes()
                .add(Attributes.MAX_HEALTH, stats.health())
                .add(Attributes.MOVEMENT_SPEED, stats.movementSpeed())
                .add(Attributes.ATTACK_DAMAGE, stats.damage());
    }

    @Override
    public GlyphidStats.StatBundle getStats() {
        return GlyphidStats.getStats().getBlaster();
    }

    @Override
    public boolean dropsExplosives() {
        return true;
    }

    @Override
    public int bombCount() {
        return 4;
    }

    @Override
    public float spread() {
        return 0.5F;
    }

    @Override
    public double muzzleSpeed() {
        return 1.25;
    }

    /**
     * Plated heavily enough that small-arms fire strips it slowly: the exponent is quartered against a grunt's, so
     * it takes roughly four times the hit to knock a plate off.
     */
    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.25, 2), 100);
    }
}
