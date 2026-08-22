package com.wf.wfballistics.entity.glyphid.caste;

import com.wf.wfballistics.entity.glyphid.GlyphidStats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * The bombardier that graduated to high explosive.
 *
 * <p>Faster, flatter and tighter than the acid it grew out of, which is what makes it a siege caste: a
 * bombardier corrodes what it hits, a blaster removes it.
 *
 * <p>Deliberate deviation from upstream, which fires ten of these per volley. Ten is survivable when each
 * one is an acid puddle and is not when each one is a live charge run through
 * {@link com.wf.wfballistics.aef.ExplosionAEF} — the terrain damage and the per-volley cost both scale with
 * the count, and a warband can field several of these at once. Four is the number that keeps a blaster
 * frightening without turning one volley into a crater.
 */
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
     * Plated heavily enough that small-arms fire strips it slowly: the exponent is quartered against a
     * grunt's, so it takes roughly four times the hit to knock a plate off.
     */
    @Override
    public boolean isArmorBroken(float amount) {
        return random.nextInt(100) <= Math.min(Math.pow(amount * 0.25, 2), 100);
    }
}
