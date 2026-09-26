package com.wf.wflib.armor;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The authored, immutable half of a piece of armour: what it protects against, how much it can take
 * before it stops doing so, and what it will accept as an insert. Shared by every instance of that
 * item; the per-instance half is {@link ArmorLayer}.
 *
 * <p>A garment and an insert are both specs. Composition is summing them, which is what lets a vest
 * good at impact and sharp take a steel plate that adds kinetic.
 */
public final class ArmorSpec {

    private final String id;
    private final int maxCondition;
    private final double wornFloor;
    private final Map<ProtectionType, ProtectionRow> rows;
    private final int insertSlots;
    private final Set<ProtectionType> insertFilter;
    private final Set<ProtectionType> covers;

    private ArmorSpec(String id, int maxCondition, double wornFloor, Map<ProtectionType, ProtectionRow> rows,
                      int insertSlots, Set<ProtectionType> insertFilter) {
        this.id = id;
        this.maxCondition = maxCondition;
        this.wornFloor = wornFloor;
        this.rows = rows;
        this.insertSlots = insertSlots;
        this.insertFilter = insertFilter;
        EnumSet<ProtectionType> covered = EnumSet.noneOf(ProtectionType.class);
        for (Map.Entry<ProtectionType, ProtectionRow> entry : rows.entrySet()) {
            ProtectionRow row = entry.getValue();
            if (row.dt() > 0.0D || row.dr() > 0.0D || row.tier() > 0.0D) {
                covered.add(entry.getKey());
            }
        }
        this.covers = Collections.unmodifiableSet(covered);
    }

    public static Builder builder(String id, int maxCondition) {
        return new Builder(id, maxCondition);
    }

    public String id() {
        return id;
    }

    public int maxCondition() {
        return maxCondition;
    }

    /**
     * Share of its rating a fully-worn layer still supplies. Zero for something that shatters
     * (ceramic); above zero for integral plate, which is still steel after it has stopped being
     * good steel. Mirrors {@code wear_floor} on the vehicle armour model.
     */
    public double wornFloor() {
        return wornFloor;
    }

    public ProtectionRow row(ProtectionType type) {
        return rows.getOrDefault(type, ProtectionRow.NONE);
    }

    /** How many inserts this garment takes. Zero for an insert itself, and for plain kit. */
    public int insertSlots() {
        return insertSlots;
    }

    /** What this garment will take as an insert. Empty means anything. */
    public Set<ProtectionType> insertFilter() {
        return insertFilter;
    }

    /** The types this spec actually does something about. An insert's advertisement of itself. */
    public Set<ProtectionType> covers() {
        return covers;
    }

    /**
     * Whether {@code insert} is the sort of thing this garment takes. A filter is a whitelist of
     * protection types rather than a named slot, so an insert describes itself by what it defends
     * against rather than by where it goes.
     */
    public boolean acceptsInsert(ArmorSpec insert) {
        if (insert == null || insertSlots <= 0) {
            return false;
        }
        if (insertFilter.isEmpty()) {
            return true;
        }
        for (ProtectionType type : insert.covers()) {
            if (insertFilter.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same armour on a different condition scale. Used at registration: an item's durability is
     * the authority on how much it can take, so an authored spec is rebased onto it rather than
     * carrying a second, contradictory number.
     */
    public ArmorSpec withMaxCondition(int newMax) {
        return newMax == maxCondition
                ? this
                : new ArmorSpec(id, newMax, wornFloor, rows, insertSlots, insertFilter);
    }

    public static final class Builder {
        private final String id;
        private final int maxCondition;
        private final Map<ProtectionType, ProtectionRow> rows = new EnumMap<>(ProtectionType.class);
        private final EnumSet<ProtectionType> insertFilter = EnumSet.noneOf(ProtectionType.class);
        private double wornFloor;
        private int insertSlots;

        private Builder(String id, int maxCondition) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("armour spec needs an id");
            }
            if (maxCondition <= 0) {
                throw new IllegalArgumentException("armour spec needs a positive max condition: " + maxCondition);
            }
            this.id = id;
            this.maxCondition = maxCondition;
        }

        public Builder row(ProtectionType type, ProtectionRow row) {
            rows.put(type, row);
            return this;
        }

        public Builder row(ProtectionType type, double dt, double dr, double hardness, double tier) {
            return row(type, new ProtectionRow(dt, dr, hardness, tier));
        }

        public Builder wornFloor(double floor) {
            if (floor < 0.0D || floor > 1.0D) {
                throw new IllegalArgumentException("worn floor is a fraction: " + floor);
            }
            this.wornFloor = floor;
            return this;
        }

        /** Takes {@code slots} inserts, of any type. */
        public Builder inserts(int slots) {
            if (slots < 0) {
                throw new IllegalArgumentException("insert slots are non-negative: " + slots);
            }
            this.insertSlots = slots;
            return this;
        }

        /** Takes {@code slots} inserts, but only ones that defend against one of {@code accepts}. */
        public Builder inserts(int slots, ProtectionType... accepts) {
            inserts(slots);
            Collections.addAll(insertFilter, accepts);
            return this;
        }

        public ArmorSpec build() {
            return new ArmorSpec(id, maxCondition, wornFloor,
                    Collections.unmodifiableMap(new EnumMap<>(rows)),
                    insertSlots,
                    insertFilter.isEmpty()
                            ? Collections.emptySet()
                            : Collections.unmodifiableSet(EnumSet.copyOf(insertFilter)));
        }
    }
}
