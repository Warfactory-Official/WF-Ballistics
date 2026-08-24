package com.wf.wfballistics.entity.glyphid;

import net.minecraft.world.damagesource.DamageSource;

/**
 * The per-caste numbers: health, speed, damage, and how much of a hit the chitin turns away. Swappable
 * because upstream ships two balance tables; only the one it selects is ported, and the abstract shape lets a
 * second be added without touching the entities.
 */
public abstract class GlyphidStats {

    /** Scales the caste speed numbers into vanilla {@code MOVEMENT_SPEED} attribute units. */
    public static final double MOVEMENT_SPEED_SCALE = 0.25D;

    /**
     * Applied on top of {@link #MOVEMENT_SPEED_SCALE}, so the table keeps upstream's numbers and the swarm's
     * absolute pace is tuned separately. Not folded into the caste speeds, which are relative to each other.
     */
    private static double pace = 1.25D;

    private static final GlyphidStats DEFAULT = new GlyphidStatsNT();

    /**
     * @return the multiplier every caste's speed is scaled by, on top of {@link #MOVEMENT_SPEED_SCALE}
     */
    public static double pace() {
        return pace;
    }

    public static void applyPace(double multiplier) {
        pace = Math.max(0.05D, multiplier);
    }

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
     * Runs a caste's damage rules. Implementations wanting the normal outcome finish by calling
     * {@link EntityGlyphid#attackSuperclass}.
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
     * @param power                  what this caste is worth to an equal-strength squad split. Explicit rather
     *                               than derived: a nuclear is worth far more than its statline says
     * @param digStrength            block hardness chewed through per second, or 0 for a caste that cannot dig
     * @param digCeiling             hardness this caste cannot chew at any speed. Obsidian is 50 and stone
     *                               1.5, so this decides whether a wall is an obstacle or a stop
     */
    public record StatBundle(double health, double speed, double damage, float thresholdMultForArmor,
                             float resistanceMult, double power, double digStrength, double digCeiling) {

        public double movementSpeed() {
            return speed * MOVEMENT_SPEED_SCALE * pace;
        }

        /**
         * @return ticks to chew through a block of the given hardness, or -1 if this caste never can
         */
        public int ticksToChew(float hardness) {
            if (digStrength <= 0.0 || hardness < 0.0F || hardness > digCeiling) {
                return -1;
            }
            return Math.max(1, (int) Math.ceil(hardness / digStrength * 20.0));
        }
    }

    /** The balance table the upstream mod actually selects. */
    public static final class GlyphidStatsNT extends GlyphidStats {

        public GlyphidStatsNT() {
            //                                hp    spd   dmg   plate  res    power  dig/s  ceiling
            this.statsGrunt = new StatBundle(20D, 1D, 2D, 1F, 0.1F, 1D, 1.25D, 10D);
            this.statsBombardier = new StatBundle(15D, 1D, 2D, 1F, 0.1F, 2D, 1.25D, 10D);
            this.statsBrawler = new StatBundle(35D, 1D, 10D, 2F, 0.15F, 3D, 2D, 25D);
            this.statsDigger = new StatBundle(50D, 1D, 10D, 3F, 0.20F, 4D, 6.25D, 60D);
            this.statsBlaster = new StatBundle(35D, 1D, 10D, 2F, 0.15F, 4D, 2D, 25D);
            this.statsBehemoth = new StatBundle(125D, 0.8D, 25D, 5F, 0.35F, 10D, 4D, 50D);
            this.statsBrenda = new StatBundle(250D, 1.2D, 50D, 10F, 0.5F, 20D, 5D, 60D);
            this.statsNuclear = new StatBundle(100D, 0.8D, 50D, 10F, 0.5F, 12D, 2D, 25D);
            // A scout carries no jaws worth the name: it founds nests, it does not open walls.
            this.statsScout = new StatBundle(20D, 1.5D, 5D, 0.5F, 0.5F, 1D, 0D, 0D);
        }

        @Override
        public boolean handleAttack(EntityGlyphid glyphid, DamageSource source, float amount) {
            return glyphid.attackSuperclass(source, amount);
        }
    }
}
