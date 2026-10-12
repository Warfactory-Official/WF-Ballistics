package com.wf.wflib.armor;

/**
 * One resolution-time contributor (garment or insert), with its own condition.
 *
 * @param coverage fraction of the hit this layer is in front of, {@code [0,1]}; 1 when the caller
 *                 resolved to a known slot, below 1 only from the whole-entity door (see
 *                 {@link ArmorSystem#coverage})
 */
public record ArmorLayer(ArmorSpec spec, int condition, double coverage) {

    public ArmorLayer {
        if (spec == null) {
            throw new IllegalArgumentException("an armour layer needs a spec");
        }
        if (coverage < 0.0D || coverage > 1.0D) {
            throw new IllegalArgumentException("coverage is a fraction: " + coverage);
        }
        condition = Math.max(0, Math.min(condition, spec.maxCondition()));
    }

    public ArmorLayer(ArmorSpec spec, int condition) {
        this(spec, condition, 1.0D);
    }

    public static ArmorLayer pristine(ArmorSpec spec) {
        return new ArmorLayer(spec, spec.maxCondition());
    }

    public double conditionFraction() {
        return spec.maxCondition() <= 0 ? 0.0D : (double) condition / spec.maxCondition();
    }

    /** Fraction of rated DT/DR still supplied; shot-up armour defends less, making sustained fire a way through rather than wasted ammo. */
    public double effectiveness() {
        double floor = spec.wornFloor();
        return floor + (1.0D - floor) * conditionFraction();
    }

    public boolean broken() {
        return condition <= 0;
    }

    public ArmorLayer worn(int points) {
        return points <= 0 ? this : new ArmorLayer(spec, condition - points, coverage);
    }

    public ArmorLayer withCoverage(double newCoverage) {
        return new ArmorLayer(spec, condition, newCoverage);
    }
}
