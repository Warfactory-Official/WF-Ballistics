package com.wf.wflib.magazine.gametest;

import com.mojang.serialization.JsonOps;
import com.wf.wflib.WFLib;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.magazine.HandlerRounds;
import com.wf.wflib.magazine.LoadPreset;
import com.wf.wflib.magazine.MagContents;
import com.wf.wflib.magazine.MagFill;
import com.wf.wflib.magazine.MagazineItems;
import com.wf.wflib.magazine.PodItem;
import com.wf.wflib.magazine.PodTubes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class MagazineGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation BALL = rl("12.7x99/ball");
    private static final ResourceLocation TRACER = rl("12.7x99/tracer");

    private static ResourceLocation rl(String p) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, p);
    }

    @GameTest(template = TEMPLATE)
    public static void aCanFillsFromStoresByPresetAndUnloadsBack(GameTestHelper h) {
        ItemStackHandler stores = new ItemStackHandler(4);
        stores.setStackInSlot(0, AmmoItems.stack(BALL, 64));
        stores.setStackInSlot(1, AmmoItems.stack(BALL, 64));
        stores.setStackInSlot(2, AmmoItems.stack(TRACER, 30));
        List<ItemStack> dropped = new ArrayList<>();
        HandlerRounds store = new HandlerRounds(stores, dropped::add);
        ItemStack can = new ItemStack(MagazineItems.CAN_12_7X99.get());
        int loaded = MagFill.fill(store, can, new LoadPreset(BALL,
                new LoadPreset.Mix(TRACER, LoadPreset.Pattern.EVERY_NTH, 5)));
        MagContents c = MagFill.contents(can);
        h.assertTrue(loaded == 100 && c.total() == 100, "can full: " + loaded);
        h.assertTrue(store.count(BALL) == 128 - 80 && store.count(TRACER) == 10, "80 ball + 20 tracer taken");
        h.assertTrue(c.at(0).equals(TRACER) && c.at(1).equals(BALL), "position 1 tracer, 2 ball");
        h.assertTrue(MagFill.unload(store, can) == 100 && MagFill.contents(can).isEmpty(), "unloaded");
        h.assertTrue(store.count(BALL) == 128 && store.count(TRACER) == 30 && dropped.isEmpty(), "all back");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void aFullStoreDropsTheUnloadedOverflow(GameTestHelper h) {
        ItemStackHandler stores = new ItemStackHandler(1);
        stores.setStackInSlot(0, new ItemStack(Items.DIRT));
        List<ItemStack> dropped = new ArrayList<>();
        ItemStack drum = MagFill.full(new ItemStack(MagazineItems.DRUM_20X102.get()), rl("20x102/he"));
        MagFill.unload(new HandlerRounds(stores, dropped::add), drum);
        h.assertTrue(dropped.stream().mapToInt(ItemStack::getCount).sum() == 1550, "1550 dropped");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void podTubesRoundTripAndCompareByContent(GameTestHelper h) {
        PodItem item = MagazineItems.POD_GMLRS.get();
        ItemStack gmlrs = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl("missile_gmlrs")));
        h.assertTrue(item.accepts(gmlrs), "GMLRS fits the pod");
        h.assertFalse(MagazineItems.POD_ATACMS.get().accepts(gmlrs), "GMLRS != ATACMS tube");
        ItemStack a = new ItemStack(item);
        a.set(MagazineItems.POD_TUBES.get(), PodItem.contents(a).with(2, gmlrs));
        ItemStack b = new ItemStack(item);
        b.set(MagazineItems.POD_TUBES.get(), PodItem.contents(b).with(2, gmlrs.copy()));
        h.assertTrue(ItemStack.isSameItemSameComponents(a, b), "same contents => same stack");
        var ops = h.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        ItemStack back = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, a).getOrThrow()).getOrThrow();
        PodTubes t = PodItem.contents(back);
        h.assertTrue(t.tubes().size() == 6 && t.loaded() == 1 && t.tubes().get(2).is(gmlrs.getItem()), "round trip");
        h.succeed();
    }
}
