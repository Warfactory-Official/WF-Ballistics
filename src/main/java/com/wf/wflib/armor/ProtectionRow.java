package com.wf.wflib.armor;

/**
 * One armour layer's numbers against one {@link ProtectionType}. Values are damage, never condition
 * points; see {@link ArmorUnits} for the conversion.
 *
 * @param dt       flat damage removed before the rest (Factorio's decrease, HBM's threshold)
 * @param dr       fraction of the remainder removed, 0..1 (Factorio's percent, HBM's resistance)
 * @param hardness damage below which a hit only scuffs the layer instead of wearing it
 * @param tier     hazard rate shrugged off per exposure interval; same units as the hazard's own tier
 */
public record ProtectionRow(double dt, double dr, double hardness, double tier) {

    public static final ProtectionRow NONE = new ProtectionRow(0.0D, 0.0D, 0.0D, 0.0D);

    public ProtectionRow {
        if (dt < 0.0D || dr < 0.0D || hardness < 0.0D || tier < 0.0D) {
            throw new IllegalArgumentException("armour rows are non-negative: " + dt + "/" + dr
                    + "/" + hardness + "/" + tier);
        }
    }

    public static ProtectionRow of(double dt, double dr) {
        return new ProtectionRow(dt, dr, 0.0D, 0.0D);
    }
}
