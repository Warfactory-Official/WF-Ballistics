package com.wf.wflib.armor;

/**
 * The <b>protection axis</b>: what material answers a hit. Deliberately NOT the same axis as the
 * wound axis (what injury a hit makes), which is WFMedical's {@code DamageCategory} and stays its
 * own set. The projection is many-to-one, wound axis into this one.
 *
 * <p>Piercing and slashing merge here because a knife and a bayonet are defeated by the same weave,
 * even though they make completely different wounds. Fall and unarmed damage have no entry at all:
 * a category with no protection type is the axis split working, not a gap.
 */
public enum ProtectionType {

    /** Bullets: concentrated, high velocity. The type that most wants a threshold. */
    KINETIC(WearPath.IMPACT),
    /** Knives, bayonets, spikes, claws. Defeats soft ballistic armour; wants mail or a weave. */
    SHARP(WearPath.IMPACT),
    /** Blunt force and crush, including backface deformation behind a plate. Padding, never a plate. */
    IMPACT(WearPath.IMPACT),
    /** Overpressure and fragmentation: area rather than point, so coverage matters more than thickness. */
    BLAST(WearPath.IMPACT),
    /** A discrete zap. Insulation. Not an exposure type in this stack: electric damage arrives as a hit. */
    ELECTRIC(WearPath.IMPACT),
    /** Fire and laser. Arrives both ways: an incendiary hit is an impact, standing in the fire is contact. */
    THERMAL(WearPath.BOTH),
    /** Gas and corrosives. Near-binary in practice: a seal either holds or it does not. */
    CHEMICAL(WearPath.EXPOSURE),
    /** Shielding mass. Defined but unauthored until NTM radiation lands in the pack. */
    RADIATION(WearPath.EXPOSURE);

    public static final ProtectionType[] VALUES = values();

    private final WearPath wearPath;

    ProtectionType(WearPath wearPath) {
        this.wearPath = wearPath;
    }

    public WearPath wearPath() {
        return wearPath;
    }

    public boolean takesImpact() {
        return wearPath != WearPath.EXPOSURE;
    }

    public boolean takesExposure() {
        return wearPath != WearPath.IMPACT;
    }

    /**
     * How damage of a type reaches the armour. This is a property of <em>how the damage arrives</em>
     * rather than of the type itself: a flamethrower's initial hit is an impact and standing in the
     * fire afterwards is exposure, and both resolve as {@link #THERMAL}. The value here says which
     * paths a type is allowed to use at all.
     */
    public enum WearPath {
        IMPACT,
        EXPOSURE,
        BOTH
    }
}
