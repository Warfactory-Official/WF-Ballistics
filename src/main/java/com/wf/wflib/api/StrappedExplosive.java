package com.wf.wflib.api;

import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.ammo.AmmoPresets;
import com.wf.wflib.ammo.AmmoVariant;
import com.wf.wflib.ammo.Calibre;
import com.wf.wflib.ammo.CartridgeItem;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** One explosive cartridge strapped to a drone as a contact charge; warhead = its round's. */
public record StrappedExplosive(ItemStack stack, float massKg) {

    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("wflib", "drone_explosive"));

    /** @return null unless tagged and its variant explosive */
    @Nullable
    public static StrappedExplosive of(ItemStack stack) {
        AmmoVariant variant = stack.is(TAG) ? AmmoItems.variantOf(stack) : null;
        if (variant == null || variant.explosive() == null) {
            return null;
        }
        // Calibre mass = case/booster + default projectile; swap in this variant's.
        Calibre calibre = ((CartridgeItem) stack.getItem()).calibre();
        return new StrappedExplosive(stack.copyWithCount(1), calibre.massKg()
                - Ammo.defaultVariant(calibre.id()).projectileMassKg() + variant.projectileMassKg());
    }

    /** Unpowered free fall from {@code at}: round's flight, warhead on impact along the velocity. */
    public void release(ServerLevel level, Vec3 at, Vec3 velocity, @Nullable Entity shooter, @Nullable UUID faction) {
        Rounds.launch(level, KineticPresetRegistry.get(AmmoItems.variantOf(this.stack).id()), at, velocity, shooter,
                faction);
    }

    /** {@code direction}: jet axis for shaped charges. */
    public void detonate(ServerLevel level, Vec3 at, Vec3 direction, @Nullable Entity exploder,
                         @Nullable UUID faction) {
        KineticPreset preset = AmmoPresets.preset(((CartridgeItem) this.stack.getItem()).calibre(),
                AmmoItems.variantOf(this.stack));
        WarheadRegistry.get(preset.warheadId()).detonate(new WarheadCarrier() {
            @Override
            public Level level() {
                return level;
            }

            @Override
            public int getFragmentCount() {
                return preset.fragmentCount();
            }

            @Override
            public Vec3 angle() {
                return direction;
            }

            @Override
            public float blastHalfAngleDeg() {
                return preset.blastHalfAngleDeg();
            }

            @Override
            public float blastSize() {
                return preset.blastSize();
            }

            @Override
            @Nullable
            public Entity exploder() {
                return exploder;
            }

            @Override
            @Nullable
            public UUID igniterFactionId() {
                return faction;
            }
        }, at);
    }
}
