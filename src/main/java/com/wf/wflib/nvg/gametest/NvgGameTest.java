package com.wf.wflib.nvg.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.armor.ArmorPreset;
import com.wf.wflib.armor.ArmorPresets;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.nvg.NvgActionPacket;
import com.wf.wflib.nvg.NvgComponents;
import com.wf.wflib.nvg.NvgDismountRecipe;
import com.wf.wflib.nvg.NvgMount;
import com.wf.wflib.nvg.NvgRegistry;
import com.wf.wflib.nvg.NvgServer;
import com.wf.wflib.nvg.NvgState;
import com.wf.wflib.nvg.NvgWorn;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class NvgGameTest {
    private static final String TEMPLATE = "empty";

    private static ArmorPreset helmetPreset() {
        return ArmorPresets.get(ArmorPreset.rl("combat_helmet"));
    }

    private static ItemStack helmet() {
        return new ItemStack(ModItems.armorItem(ArmorPreset.rl("combat_helmet")));
    }

    private static ItemStack goggle(NvgState state) {
        ItemStack nvg = new ItemStack(NvgRegistry.ITEMS.get(2).get());
        nvg.set(NvgComponents.STATE.get(), state);
        return nvg;
    }

    /** Click-mount flips the goggle up; dismount recipe returns it and leaves the bare helmet in the grid. */
    @GameTest(template = TEMPLATE)
    public static void mountThenDismount(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack helmet = helmet();
        ItemStack cursor = goggle(new NvgState(true, true, 0.5f));
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);
        helper.assertTrue(NvgMount.fit(helmetPreset(), helmet, cursor, slot, player), "mount refused");
        helper.assertTrue(cursor.isEmpty(), "cursor goggle not consumed");
        ItemStack mounted = NvgWorn.mounted(helmet);
        helper.assertTrue(!mounted.isEmpty() && !NvgWorn.state(mounted).down() && NvgWorn.state(mounted).ir(),
                "mounted state " + NvgWorn.state(mounted));
        helper.assertFalse(NvgMount.fit(helmetPreset(), helmet, goggle(NvgState.UP), slot, player), "second goggle mounted");

        NvgDismountRecipe recipe = new NvgDismountRecipe(CraftingBookCategory.EQUIPMENT);
        CraftingInput input = CraftingInput.of(1, 1, List.of(helmet));
        helper.assertTrue(recipe.matches(input, helper.getLevel()), "dismount recipe did not match");
        ItemStack out = recipe.assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(out.getItem() == NvgRegistry.ITEMS.get(2).get(), "result " + out);
        ItemStack left = recipe.getRemainingItems(input).get(0);
        helper.assertTrue(left.getItem() == helmet.getItem() && NvgWorn.mounted(left).isEmpty(), "remainder " + left);
        helper.assertFalse(recipe.matches(CraftingInput.of(1, 1, List.of(helmet())), helper.getLevel()), "bare helmet matched");
        helper.assertFalse(recipe.matches(CraftingInput.of(2, 1, List.of(helmet, helmet())), helper.getLevel()),
                "matched with a second item");
        helper.succeed();
    }

    /** FLIP: down at the swap tick, not before; IR and gain actions write the mounted goggle. */
    @GameTest(template = TEMPLATE)
    public static void flipIrGain(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "nvg"));
        ItemStack helmet = helmet();
        Slot slot = new Slot(new SimpleContainer(1), 0, 0, 0);
        NvgMount.fit(helmetPreset(), helmet, goggle(NvgState.UP), slot, player);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
        NvgServer.act(player, NvgActionPacket.Action.IR);
        NvgServer.act(player, NvgActionPacket.Action.GAIN_UP);
        helper.assertTrue(NvgWorn.state(player).ir() && Math.abs(NvgWorn.state(player).gain() - 0.8f) < 1e-4f,
                "ir/gain " + NvgWorn.state(player));
        NvgServer.act(player, NvgActionPacket.Action.FLIP);
        helper.onEachTick(() -> NvgServer.onTick(new PlayerTickEvent.Post(player)));
        helper.runAtTickTime(NvgServer.SWAP_TICK - 2, () -> helper.assertFalse(NvgWorn.down(player), "down early"));
        helper.runAtTickTime(NvgServer.SWAP_TICK + 2, () -> {
            helper.assertTrue(NvgWorn.down(player), "not down after the swap tick");
            helper.succeed();
        });
    }
}
