package com.wf.wflib.armor;

/**
 * One armour layer's numbers against one {@link ProtectionType}.
 *
 * <p>Every field is a threshold or a fraction in <em>damage</em> units, never condition points; the
 * conversion lives in {@link ArmorUnits}. The same threshold shape appears three times across the
 * model, which is the main reason it stays small:
 *
 * <ul>
 *   <li>{@code dt} on incoming damage: below it, nothing gets through.</li>
 *   <li>{@code hardness} on impact wear: below it, a scuff rather than a bite.</li>
 *   <li>{@code tier} on sustained exposure: a hazard dealing at or below it costs nothing, ever.</li>
 * </ul>
 *
 * @param dt       flat damage subtracted before anything else (Factorio's decrease, HBM's threshold)
 * @param dr       fraction of the remainder removed, 0..1 (Factorio's percent, HBM's resistance)
 * @param hardness absorbed damage below which a hit only scuffs this layer, so that volume fire
 *                 cannot grind down armour it cannot defeat
 * @param tier     the hazard rate this layer shrugs off entirely, in condition points per exposure
 *                 interval. Same units as the hazard's own tier, so the two are directly comparable:
 *                 a suit rated 200 stands in a 150 cloud forever and takes 50 a interval from a 250 one
 */
public record ProtectionRow(double dt, double dr, double hardness, double tier) {

    public static final ProtectionRow NONE = new ProtectionRow(0.0D, 0.0D, 0.0D, 0.0D);

    public ProtectionRow {
        if (dt < 0.0D || dr < 0.0D || hardness < 0.0D || tier < 0.0D) {
            throw new IllegalArgumentException("armour rows are non-negative: " + dt + "/" + dr
                    + "/" + hardness + "/" + tier);
        }
    }

    /** Convenience for the common case of a layer that only resists a percentage. */
    public static ProtectionRow of(double dt, double dr) {
        return new ProtectionRow(dt, dr, 0.0D, 0.0D);
    }
}
