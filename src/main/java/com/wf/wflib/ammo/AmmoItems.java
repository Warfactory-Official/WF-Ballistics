package com.wf.wflib.ammo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.wf.wflib.WFLib;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code wflib:ammo_variant} component + one {@link CartridgeItem} per calibre, registered at {@link Ammo#freeze}. */
public final class AmmoItems {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, WFLib.MODID);

    /** Unknown variant id => decode error. */
    public static final Codec<ResourceLocation> VARIANT_CODEC = ResourceLocation.CODEC.validate(id ->
            Ammo.variant(id) != null ? DataResult.success(id) : DataResult.error(() -> "unknown ammo variant " + id));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> VARIANT =
            COMPONENTS.register("ammo_variant", () -> DataComponentType.<ResourceLocation>builder()
                    .persistent(VARIANT_CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC)
                    .cacheEncoding()
                    .build());

    private static volatile Map<ResourceLocation, CartridgeItem> items = Map.of();

    private AmmoItems() {
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        bus.addListener(AmmoItems::onRegister);
    }

    private static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.ITEM)) {
            return;
        }
        Ammo.freeze();
        Map<ResourceLocation, CartridgeItem> out = new LinkedHashMap<>();
        event.register(Registries.ITEM, helper -> Ammo.calibres().forEach(c -> {
            CartridgeItem item = new CartridgeItem(c, new Item.Properties().stacksTo(c.stackSize())
                    .component(VARIANT.get(), Ammo.defaultVariant(c.id()).id()));
            helper.register(c.itemId(), item);
            out.put(c.id(), item);
        }));
        items = Collections.unmodifiableMap(out);
    }

    /** Calibre id -> item, registration order. */
    public static Map<ResourceLocation, CartridgeItem> items() {
        return items;
    }

    public static CartridgeItem item(ResourceLocation calibre) {
        CartridgeItem i = items.get(calibre);
        if (i == null) {
            throw new IllegalArgumentException("no cartridge item for calibre " + calibre);
        }
        return i;
    }

    public static ItemStack stack(AmmoVariant v, int count) {
        ItemStack s = new ItemStack(item(v.calibre()), count);
        s.set(VARIANT.get(), v.id());
        return s;
    }

    public static ItemStack stack(ResourceLocation variant, int count) {
        AmmoVariant v = Ammo.variant(variant);
        if (v == null) {
            throw new IllegalArgumentException("unknown ammo variant " + variant);
        }
        return stack(v, count);
    }

    /** Null unless a cartridge; the variant is the stack's, calibre mismatch => item's default. */
    @Nullable
    public static AmmoVariant variantOf(ItemStack stack) {
        return stack.getItem() instanceof CartridgeItem c ? c.variant(stack) : null;
    }
}
