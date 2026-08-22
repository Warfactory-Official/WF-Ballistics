package com.wf.wfballistics.entity.glyphid;

import net.minecraft.world.damagesource.DamageSource;

/**
 * The per-caste numbers: health, speed, damage, and how much of a hit the chitin turns away.
 *
 * <p>Kept as a swappable object rather than baked into the entity classes because the upstream mod ships two
 * balance tables and picks between them at {@link #getStats()}. Only the one it actually selects is ported
 * here; the abstract shape is kept so a second table can be added without touching the entities.
 */
public abstract class GlyphidStats {

    /**
     * Scales the caste speed numbers into vanilla {@code MOVEMENT_SPEED} attribute units.
     */
    public static final double MOVEMENT_SPEED_SCALE = 0.25D;

    private static final GlyphidStats DEFAULT = new GlyphidStatsNT();

    protected StatBundle statsGrunt;
    protected StatBundle statsBombardier;
    protected StatBundle statsBrawler;
    protected StatBundle statsDigger;
    protected StatBundle statsBlaster;
    protected StatBundle statsBehemoth;
    protected StatBundle statsBrenda;
    protected StatBundle statsNuclear;
    protected StatBundle statsScout;

    public static GlyphidStats getStats() {
        return DEFAULT;
    }

    /**
     * Runs a caste's damage rules. Returns whether the hit landed; implementations that want the normal
     * outcome finish by calling {@link EntityGlyphid#attackSuperclass}.
     */
    public abstract boolean handleAttack(EntityGlyphid glyphid, DamageSource source, float amount);

    public StatBundle getGrunt() {
        return statsGrunt;
    }

    public StatBundle getBombardier() {
        return statsBombardier;
    }

    public StatBundle getBrawler() {
        return statsBrawler;
    }

    public StatBundle getDigger() {
        return statsDigger;
    }

    public StatBundle getBlaster() {
        return statsBlaster;
    }

    public StatBundle getBehemoth() {
        return statsBehemoth;
    }

    public StatBundle getBrenda() {
        return statsBrenda;
    }

    public StatBundle getNuclear() {
        return statsNuclear;
    }

    public StatBundle getScout() {
        return statsScout;
    }

    /**
     * One caste's numbers.
     *
     * @param health                 max health
     * @param speed                  caste speed, before {@link #MOVEMENT_SPEED_SCALE}
     * @param damage                 melee damage
     * @param thresholdMultForArmor  damage threshold contributed by each surviving armour plate, so a bug
     *                               that has been shelled turns away less
     * @param resistanceMult         fraction of a hit shrugged off after the threshold applies
     */
    public record StatBundle(double health, double speed, double damage, float thresholdMultForArmor,
                             float resistanceMult) {

        public double movementSpeed() {
            return speed * MOVEMENT_SPEED_SCALE;
        }
    }

    /**
     * The balance table the upstream mod actually selects.
     */
    public static final class GlyphidStatsNT extends GlyphidStats {

        public GlyphidStatsNT() {
            this.statsGrunt = new StatBundle(20D, 1D, 2D, 1F, 0.1F);
            this.statsBombardier = new StatBundle(15D, 1D, 2D, 1F, 0.1F);
            this.statsBrawler = new StatBundle(35D, 1D, 10D, 2F, 0.15F);
            this.statsDigger = new StatBundle(50D, 1D, 10D, 3F, 0.20F);
            this.statsBlaster = new StatBundle(35D, 1D, 10D, 2F, 0.15F);
            this.statsBehemoth = new StatBundle(125D, 0.8D, 25D, 5F, 0.35F);
            this.statsBrenda = new StatBundle(250D, 1.2D, 50D, 10F, 0.5F);
            this.statsNuclear = new StatBundle(100D, 0.8D, 50D, 10F, 0.5F);
            this.statsScout = new StatBundle(20D, 1.5D, 5D, 0.5F, 0.5F);
        }

        @Override
        public boolean handleAttack(EntityGlyphid glyphid, DamageSource source, float amount) {
            return glyphid.attackSuperclass(source, amount);
        }
    }
}
