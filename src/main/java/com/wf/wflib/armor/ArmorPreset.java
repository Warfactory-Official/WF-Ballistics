package com.wf.wflib.armor;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import org.jetbrains.annotations.Nullable;

/**
 * One authored piece: a garment on a slot, or an insert that goes into one. The same shape as the
 * mod's missile and mine presets, so armour is made the same way everything else here is made.
 *
 * <p>Durability is the single number saying how much the piece can take. Condition is that number at
 * a hundred points to one, so the spec is rebased onto it at registration rather than carrying a
 * second figure that could disagree with it.
 *
 * @param type  the slot this goes on, or null for an insert
 * @param model a rigged glTF for GemRender to draw, or null to render as a vanilla armour texture
 */
public record ArmorPreset(ResourceLocation id, @Nullable ArmorItem.Type type, int durability,
                          ArmorSpec spec, @Nullable ResourceLocation model) {

    public ArmorPreset {
        if (durability <= 0) {
            throw new IllegalArgumentException("an armour piece needs durability: " + id);
        }
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

    /** A garment worn on {@code type}. */
    public static Builder piece(String path, ArmorItem.Type type, int durability) {
        return new Builder(path, type, durability);
    }

    /** A plate, a liner or a filter that goes into one. */
    public static Builder insert(String path, int durability) {
        return new Builder(path, null, durability);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final ArmorItem.Type type;
        private final int durability;
        private final ArmorSpec.Builder spec;
        private ResourceLocation model;

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

        public ArmorPreset build() {
            return new ArmorPreset(id, type, durability, spec.build(), model);
        }
    }
}
