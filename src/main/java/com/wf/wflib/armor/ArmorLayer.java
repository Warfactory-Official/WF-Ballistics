package com.wf.wflib.armor;

/**
 * One contributor to a hit's resolution: a garment, or an insert in it, with its own condition.
 *
 * <p>Condition is per layer rather than per stack, which is why a garment and its inserts are
 * separate stacks: a carrier with three inserts has three independent conditions, and the plate that
 * ate the rifle round is the thing that wears out, not the carrier.
 *
 * @param coverage how much of the hit this layer is in the way of, 0..1. Always 1 when the caller
 *                 already knows where the hit landed (WFMedical picks the limb, and the limb picks
 *                 the slot). Below 1 only on the whole-entity door, where nobody knows: a helmet is
 *                 then worth its share of a body rather than the whole of one, which is what stops
 *                 a helmet from protecting a shin.
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

    /** Condition as a fraction, 1.0 when undamaged. */
    public double conditionFraction() {
        return spec.maxCondition() <= 0 ? 0.0D : (double) condition / spec.maxCondition();
    }

    /**
     * How much of its authored rating this layer still supplies. A shot-up plate defends less, which
     * is what makes sustained fire a way through armour rather than merely a waste of ammunition.
     */
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
