package com.wf.wflib.armor;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Authored piece (garment or insert); same preset shape as this mod's missile/mine presets.
 * {@code durability} is the single source of truth; the spec is rebased onto it (100x) at
 * registration rather than carrying an independent figure.
 *
 * @param type  the slot this goes on, or null for an insert
 * @param model    a rigged glTF for GemRender to draw, or null to render as a vanilla armour texture
 * @param nvgMount accepts a {@link com.wf.wflib.nvg.NvgItem}; drawn at the model's {@code socket_nvg}
 * @param occupies other armour slots a suit fills: kept empty while worn, protected by this piece
 */
public record ArmorPreset(ResourceLocation id, @Nullable ArmorItem.Type type, int durability,
                          ArmorSpec spec, @Nullable ResourceLocation model, boolean nvgMount,
                          Set<EquipmentSlot> occupies) {

    public ArmorPreset {
        if (durability <= 0) {
            throw new IllegalArgumentException("an armour piece needs durability: " + id);
        }
        if (!occupies.isEmpty() && (type == null || occupies.contains(type.getSlot())
                || !occupies.stream().allMatch(s -> s.getType() == EquipmentSlot.Type.HUMANOID_ARMOR))) {
            throw new IllegalArgumentException("occupies = other armour slots of a garment: " + id + " " + occupies);
        }
        occupies = occupies.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(occupies));
    }

    public boolean isInsert() {
        return type == null;
    }

    public String path() {
        return id.getPath();
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    public static Builder piece(String path, ArmorItem.Type type, int durability) {
        return new Builder(path, type, durability);
    }

    public static Builder insert(String path, int durability) {
        return new Builder(path, null, durability);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final ArmorItem.Type type;
        private final int durability;
        private final ArmorSpec.Builder spec;
        private ResourceLocation model;
        private boolean nvgMount;
        private final Set<EquipmentSlot> occupies = EnumSet.noneOf(EquipmentSlot.class);

        private Builder(String path, @Nullable ArmorItem.Type type, int durability) {
            this.id = rl(path);
            this.type = type;
            this.durability = durability;
            this.spec = ArmorSpec.builder(id.toString(), durability * ArmorUnits.POINTS_PER_DAMAGE);
        }

        public Builder row(ProtectionType protection, double dt, double dr, double hardness, double tier) {
            spec.row(protection, dt, dr, hardness, tier);
            return this;
        }

        public Builder wornFloor(double floor) {
            spec.wornFloor(floor);
            return this;
        }

        public Builder inserts(int slots, ProtectionType... accepts) {
            spec.inserts(slots, accepts);
            return this;
        }

        /** Draw this piece from a rigged glTF through GemRender instead of a vanilla armour texture. */
        public Builder model(ResourceLocation gltf) {
            this.model = gltf;
            return this;
        }

        public Builder nvgMount() {
            this.nvgMount = true;
            return this;
        }

        public Builder occupies(EquipmentSlot... slots) {
            occupies.addAll(java.util.List.of(slots));
            return this;
        }

        public ArmorPreset build() {
            return new ArmorPreset(id, type, durability, spec.build(), model, nvgMount, occupies);
        }
    }
}
