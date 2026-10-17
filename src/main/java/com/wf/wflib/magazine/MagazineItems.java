package com.wf.wflib.magazine;

import com.wf.wflib.WFLib;
import com.wf.wflib.ammo.Ammo;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.wf.wflib.magazine.PodItem.at;

/** Magazine components + built-in vehicle magazines and launch pods. */
public final class MagazineItems {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, WFLib.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, WFLib.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<MagContents>> CONTENTS =
            COMPONENTS.register("mag_contents", () -> DataComponentType.<MagContents>builder()
                    .persistent(MagContents.CODEC).networkSynchronized(MagContents.STREAM_CODEC).cacheEncoding()
                    .build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PodTubes>> POD_TUBES =
            COMPONENTS.register("pod_tubes", () -> DataComponentType.<PodTubes>builder()
                    .persistent(PodTubes.CODEC).networkSynchronized(PodTubes.STREAM_CODEC).cacheEncoding().build());

    public static final DeferredHolder<Item, MagazineItem> CAN_7_62X51 = magazine("ammo_can_7_62x51", "7.62x51", 200);
    public static final DeferredHolder<Item, MagazineItem> CAN_12_7X99 = magazine("ammo_can_12_7x99", "12.7x99", 100);
    public static final DeferredHolder<Item, MagazineItem> CAN_40X53 = magazine("ammo_can_40x53", "40x53", 48);
    /** Phalanx Block 1B / M61 linkless drum. */
    public static final DeferredHolder<Item, MagazineItem> DRUM_20X102 = magazine("drum_20x102", "20x102", 1550);

    /** M142 stations, 2 x 3 cells. */
    public static final DeferredHolder<Item, PodItem> POD_GMLRS = ITEMS.register("pod_gmlrs", () -> new PodItem(List.of(
            at(0.3169f, -0.1590f), at(0, -0.1590f), at(-0.3169f, -0.1590f),
            at(0.3169f, 0.1590f), at(0, 0.1590f), at(-0.3169f, 0.1590f)),
            4.28f, rl("missile/pod"), new Item.Properties()));
    public static final DeferredHolder<Item, PodItem> POD_ATACMS = ITEMS.register("pod_atacms", () -> new PodItem(
            List.of(at(0, 0)), 4.0f, rl("missile/pod_large"), new Item.Properties()));

    private MagazineItems() {
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    private static DeferredHolder<Item, MagazineItem> magazine(String path, String calibre, int capacity) {
        return ITEMS.register(path, () -> new MagazineItem(rl(calibre), capacity, new Item.Properties()));
    }

    /** Each empty, magazines full of the calibre's default variant, pods full of their first tagged round. */
    public static List<ItemStack> creativeStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (var mag : List.of(CAN_7_62X51, CAN_12_7X99, CAN_40X53, DRUM_20X102)) {
            ItemStack empty = new ItemStack(mag.get());
            out.add(empty);
            out.add(MagFill.full(empty, Ammo.defaultVariant(mag.get().calibre()).id()));
        }
        for (var pod : List.of(POD_GMLRS, POD_ATACMS)) {
            ItemStack empty = new ItemStack(pod.get());
            out.add(empty);
            BuiltInRegistries.ITEM.getTag(pod.get().tag()).flatMap(t -> t.stream().findFirst()).ifPresent(round -> {
                ItemStack full = empty.copy();
                full.set(POD_TUBES.get(), new PodTubes(Collections.nCopies(pod.get().tubes().size(),
                        new ItemStack(round))));
                out.add(full);
            });
        }
        return out;
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        ITEMS.register(bus);
    }
}
