package com.wf.wflib.ammo.gametest;

import com.mojang.serialization.JsonOps;
import com.wf.wflib.WFLib;
import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.ammo.AmmoVariant;
import com.wf.wflib.ammo.Calibre;
import com.wf.wflib.ammo.CartridgeItem;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registry frozen at item registration: items, presets, stack round trip, late registration refused. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class AmmoGameTest {
    private static final String TEMPLATE = "empty";

    private static ResourceLocation rl(String p) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, p);
    }

    @GameTest(template = TEMPLATE)
    public static void oneItemPerCalibreOnePresetPerVariant(GameTestHelper h) {
        h.assertTrue(Ammo.frozen(), "frozen");
        for (Calibre c : Ammo.calibres()) {
            h.assertTrue(BuiltInRegistries.ITEM.get(c.itemId()) instanceof CartridgeItem ci && ci.calibre() == c,
                    "item " + c.itemId());
        }
        for (AmmoVariant v : Ammo.variants()) {
            KineticPreset p = KineticPresetRegistry.get(v.id());
            h.assertTrue(p != null && Math.abs(p.muzzleSpeed() - v.speed() / 20f) < 1e-6f
                    && p.damagePerKJ() == v.damagePerKJ() && p.blockPen() == v.blockPen()
                    && p.tracerColor() == (v.tracer() == AmmoVariant.NONE ? 0 : v.tracer())
                    && p.loadsChunks() == (Ammo.calibre(v.calibre()).kind() == com.wf.wflib.ammo.CalibreKind.TANK),
                    "preset " + v.id());
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void variantsShareTheCalibreItem(GameTestHelper h) {
        ItemStack ball = AmmoItems.stack(rl("5.56x45/ball"), 30);
        ItemStack ap = AmmoItems.stack(rl("5.56x45/ap"), 30);
        h.assertTrue(ball.getItem() == ap.getItem(), "one item");
        h.assertFalse(ItemStack.isSameItemSameComponents(ball, ap), "variants stack apart");
        h.assertTrue(AmmoItems.variantOf(ap).id().equals(rl("5.56x45/ap")), "variant read back");
        h.assertTrue(AmmoItems.variantOf(new ItemStack(ball.getItem())).id().equals(rl("5.56x45/ball")),
                "default component = first variant");
        var json = ItemStack.CODEC.encodeStart(h.getLevel().registryAccess().createSerializationContext(
                JsonOps.INSTANCE), ap).getOrThrow();
        ItemStack back = ItemStack.CODEC.parse(h.getLevel().registryAccess().createSerializationContext(
                JsonOps.INSTANCE), json).getOrThrow();
        h.assertTrue(ItemStack.isSameItemSameComponents(ap, back), "codec round trip " + json);
        ItemStack foreign = new ItemStack(ball.getItem());
        foreign.set(AmmoItems.VARIANT.get(), rl("9x19/ball"));
        h.assertTrue(AmmoItems.variantOf(foreign).id().equals(rl("5.56x45/ball")), "other calibre's variant ignored");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void registrationAfterFreezeRefused(GameTestHelper h) {
        AmmoVariant fresh = AmmoVariant.builder(ResourceLocation.fromNamespaceAndPath("late", "x"), rl("9x19"), "x")
                .flight(300, 0.008f, 0).build();
        try {
            Ammo.register(fresh, "late-mod");
            h.fail("late variant accepted");
        } catch (IllegalStateException e) {
            h.assertTrue(e.getMessage().contains("frozen") && e.getMessage().contains("late-mod"), e.getMessage());
        }
        try {
            Ammo.register(Ammo.variant(rl("9x19/ball")), "other-mod");
            h.fail("duplicate accepted");
        } catch (IllegalStateException e) {
            h.assertTrue(e.getMessage().contains("other-mod") && e.getMessage().contains("wflib"), e.getMessage());
        }
        h.succeed();
    }
}
