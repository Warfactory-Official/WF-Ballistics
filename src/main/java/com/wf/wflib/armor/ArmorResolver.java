package com.wf.wflib.armor;

import java.util.ArrayList;
import java.util.List;

/**
 * The one reduction step. Pure arithmetic over {@link ArmorLayer}s, with no Minecraft types, so it
 * is unit-testable and so both callers (WFMedical per limb, and whole-entity for mobs with no limb
 * model) share exactly one implementation.
 *
 * <p>Damage reduction is DT then DR, Factorio's decrease/percent under HBM's names. Wear is per
 * absorbed damage rather than per hit, which makes a fully stopped hit transfer all of its energy
 * into the armour: volume fire wears plate faster than a round that punches through. {@code
 * hardness} is the brake on that, so armour can be genuinely too tough to grind down with cheap
 * ammunition.
 */
public final class ArmorResolver {

    /**
     * Share of a hit the armour must eat before the result reads as {@link ArmorOutcome#REDUCED}
     * rather than {@link ArmorOutcome#THROUGH}.
     */
    public static final double REDUCED_FRACTION = 0.15D;

    private ArmorResolver() {
    }

    public static ArmorResult resolve(List<ArmorLayer> layers, ProtectionType type, double amount) {
        return resolve(layers, type, amount, 0.0D, 0.0D);
    }

    /**
     * @param pierceDt flat threshold this hit ignores (armour-piercing ammunition)
     * @param pierceDr fraction of the resistance this hit ignores, 0..1
     */
    public static ArmorResult resolve(List<ArmorLayer> layers, ProtectionType type, double amount,
                                      double pierceDt, double pierceDr) {
        if (type == null) {
            throw new IllegalArgumentException("a hit must resolve as some protection type");
        }
        if (amount <= 0.0D || layers == null || layers.isEmpty()) {
            return ArmorResult.untouched(type, Math.max(amount, 0.0D));
        }

        int n = layers.size();
        double[] dt = new double[n];
        double[] dr = new double[n];
        double dtTotal = 0.0D;
        double drTotal = 0.0D;
        for (int i = 0; i < n; i++) {
            ArmorLayer layer = layers.get(i);
            ProtectionRow row = layer.spec().row(type);
            // Coverage is 1 whenever the caller knows where the hit landed; it is only below 1 on the
            // whole-entity door, where a piece is worth its share of a body rather than the whole of one.
            double eff = layer.effectiveness() * layer.coverage();
            dt[i] = row.dt() * eff;
            dr[i] = row.dr() * eff;
            dtTotal += dt[i];
            drTotal += dr[i];
        }

        // Piercing is applied to the totals, and the per-layer shares below are taken from the
        // unpierced values: the ratios are what attribution needs, and they are unchanged by it.
        double piercedDt = Math.max(0.0D, dtTotal - Math.max(pierceDt, 0.0D));
        double keptDr = clamp(1.0D - pierceDr, 0.0D, 1.0D);
        double effectiveDr = Math.min(drTotal, ArmorUnits.MAX_RESISTANCE) * keptDr;

        double absorbedByDt;
        double absorbedByDr;
        double through;
        if (amount <= piercedDt) {
            absorbedByDt = amount;
            absorbedByDr = 0.0D;
            through = 0.0D;
        } else {
            absorbedByDt = piercedDt;
            double afterDt = amount - piercedDt;
            absorbedByDr = afterDt * effectiveDr;
            through = afterDt - absorbedByDr;
        }
        double absorbed = amount - through;

        List<ArmorResult.LayerWear> wear = new ArrayList<>(n);
        double worst = 1.0D;
        boolean impactWear = type.takesImpact();
        for (int i = 0; i < n; i++) {
            double share = 0.0D;
            if (dtTotal > 0.0D) {
                share += absorbedByDt * (dt[i] / dtTotal);
            }
            if (drTotal > 0.0D) {
                share += absorbedByDr * (dr[i] / drTotal);
            }

            ArmorLayer layer = layers.get(i);
            int points = 0;
            if (impactWear && share > 0.0D) {
                // Hardness is not scaled by coverage: it is a property of the material, not of how much
                // of the body it happens to be in front of. A partly-covering piece therefore absorbs
                // less and is that much more likely to be merely scuffed, which is the right reading.
                double hardness = layer.spec().row(type).hardness() * layer.effectiveness();
                double excess = share - hardness;
                double wearDamage = excess <= 0.0D
                        ? share * ArmorUnits.SCUFF_RATE
                        : hardness * ArmorUnits.SCUFF_RATE + excess;
                points = ArmorUnits.toPoints(wearDamage);
                if (points > 0) {
                    wear.add(new ArmorResult.LayerWear(i, points));
                }
            }
            if (share > 0.0D || dt[i] > 0.0D || dr[i] > 0.0D) {
                worst = Math.min(worst, layer.worn(points).conditionFraction());
            }
        }

        ArmorOutcome outcome;
        if (through <= 0.0D) {
            outcome = ArmorOutcome.STOPPED;
        } else if (absorbed >= amount * REDUCED_FRACTION) {
            outcome = ArmorOutcome.REDUCED;
        } else {
            outcome = ArmorOutcome.THROUGH;
        }

        return new ArmorResult(type, amount, through, absorbed, outcome, List.copyOf(wear), worst);
    }

    /**
     * Wear from standing in the fire, the gas or the field, for one exposure interval.
     *
     * <p><b>A hazard's tier is what it deals, flat, per interval, in condition points.</b> Nothing is
     * integrated over time and nothing is converted: the number a gas cloud is authored with is the
     * number subtracted from the suit standing in it. The armour's own tier is in the same units, so
     * the two are directly comparable, and the subtraction is the same threshold shape as {@code dt} on
     * a bullet and {@code hardness} on a plate.
     *
     * <p>Subtracted rather than gated, deliberately. A suit one tier under a hazard should last a long
     * time and not forever; a cliff at the rating would make an expensive near-miss worth exactly as
     * much as nothing at all, and {@code dt} does not behave that way either.
     *
     * @param tier the hazard's rate, in condition points per interval
     */
    public static int sustainedWear(ArmorLayer layer, ProtectionType type, double tier) {
        if (layer == null || type == null || tier <= 0.0D || !type.takesExposure() || layer.broken()) {
            return 0;
        }
        double shrugged = layer.spec().row(type).tier() * layer.effectiveness() * layer.coverage();
        double excess = tier - shrugged;
        if (excess <= 0.0D) {
            return 0;
        }
        return excess >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(excess);
    }

    /**
     * Wear from a chemical splash or an ignition: a flat share of the layer's maximum, taken at the
     * moment of the hit rather than over time.
     *
     * <p>Charging a fraction of the maximum rather than flat points is deliberate. It costs steel
     * power armour and a cheap vest the same proportion, so heavy armour is not the universal
     * answer: it laughs at bullets and still dissolves. The suit's own resistance to the type is the
     * dial that makes a hazmat rating worth having, and it needs no number of its own.
     */
    public static int acuteWear(ArmorLayer layer, ProtectionType type, double fraction) {
        if (layer == null || type == null || fraction <= 0.0D || !type.takesExposure() || layer.broken()) {
            return 0;
        }
        double resisted = Math.min(layer.spec().row(type).dr() * layer.effectiveness() * layer.coverage(),
                ArmorUnits.MAX_RESISTANCE);
        double points = layer.spec().maxCondition() * fraction * (1.0D - resisted);
        return points <= 0.0D ? 0 : (int) Math.round(points);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
