package com.wf.wflib.armor;

/**
 * The protection axis: what material answers a hit. NOT the wound axis (what injury a hit makes,
 * WFMedical's {@code DamageCategory}, its own set): wound axis projects many-to-one into this one.
 *
 * <p>Piercing/slashing merge here: knife and bayonet both defeated by the same weave despite different
 * wounds. Fall and unarmed damage have no entry: a category with no protection type is the axis split
 * working, not a gap.
 */
public enum ProtectionType {

    /** Bullets; the type that most wants a {@code dt} threshold. */
    KINETIC(WearPath.IMPACT),
    /** Knives, bayonets, spikes, claws. Defeats soft ballistic armour; wants mail or a weave. */
    SHARP(WearPath.IMPACT),
    /** Blunt force/crush incl. backface deformation behind a plate. Padding, never a plate. */
    IMPACT(WearPath.IMPACT),
    /** Overpressure + fragmentation: area not point, so coverage beats thickness. */
    BLAST(WearPath.IMPACT),
    /** Discrete zap; insulation. No exposure form in this stack: arrives only as a hit. */
    ELECTRIC(WearPath.IMPACT),
    /** Fire, laser. Only type using both wear paths. */
    THERMAL(WearPath.BOTH),
    /** Gas, corrosives. Near-binary: a seal holds or it does not. */
    CHEMICAL(WearPath.EXPOSURE),
    /** Shielding mass; unauthored until NTM radiation lands in the pack. */
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
     * Property of how damage arrives, not of the type: a flamethrower's hit is impact, standing in
     * the fire after is exposure, both resolve as {@link #THERMAL}.
     */
    public enum WearPath {
        IMPACT,
        EXPOSURE,
        BOTH
    }
}
