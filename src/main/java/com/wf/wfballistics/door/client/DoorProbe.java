package com.wf.wfballistics.door.client;

import com.wf.wfballistics.door.DoorBlock;
import com.wf.wfballistics.door.DoorBlockEntity;
import com.wf.wfballistics.door.DoorFrame;
import com.wf.wfballistics.door.DoorProbeActions;
import com.wf.wfballistics.door.DoorProbeData;
import com.wf.wfballistics.door.DoorState;
import com.wf.wfballistics.door.DoorType;
import com.wf.wfballistics.door.ModDoors;
import com.wf.wfballistics.probe.ProbeAction;
import com.wf.wfballistics.probe.ProbeElement;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.probe.ProbeCapabilities;
import com.wf.wfballistics.probe.ProbeInfo;
import com.wf.wfballistics.probe.ProbeProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.List;

/** What the probe says about a door. */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DoorProbe {

    private static final ResourceLocation REDSTONE =
            ResourceLocation.withDefaultNamespace("block/redstone_dust_dot");

    private DoorProbe() {
    }

    private static final ProbeProvider.Blocks PANEL = (info, ctx, state, pos, blockEntity) -> {
        if (!(state.getBlock() instanceof DoorBlock block)) {
            return;
        }
        BlockPos core = DoorFrame.findCore(ctx.level(), pos, block);
        DoorBlockEntity door = core != null
                && ctx.level().getBlockEntity(core) instanceof DoorBlockEntity found ? found : null;
        append(info, block.type(), door, ctx.mainHand(), ctx.offHand(),
                DoorProbeData.powered(ctx.data()));
        if (door != null) {
            actions(info, ctx.player(), block.type(), door);
        }
    };

    @SubscribeEvent
    static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(ProbeCapabilities.BLOCK, (level, pos, state, be, ctx) -> PANEL, doorBlocks());
    }

    private static Block[] doorBlocks() {
        return ModDoors.DOORS.values().stream().map(holder -> (Block) holder.get()).toArray(Block[]::new);
    }

    /** What you can do to this door from here. */
    private static void actions(ProbeInfo info, Player player, DoorType type, DoorBlockEntity door) {
        ItemStack key = DoorProbeActions.keyFor(player, door);
        boolean keyed = !key.isEmpty();
        Component needKey = Component.translatable("probe.wfballistics.door.need_key");

        ProbeAction toggle = ProbeAction.of(DoorProbeActions.TOGGLE, Component.translatable(
                        door.shut() ? "probe.wfballistics.door.action.open"
                                : "probe.wfballistics.door.action.close"))
                .withIcon(new ProbeElement.Icon(new ItemStack(ModDoors.DOOR_KEY.get()), 10, false));
        if (!keyed) {
            toggle = toggle.unavailable(needKey);
        } else if (door.heldByRedstone()) {
            toggle = toggle.unavailable(Component.translatable("probe.wfballistics.door.held"));
        } else if (door.state().moving()) {
            toggle = toggle.unavailable(Component.translatable("probe.wfballistics.door.moving"));
        }
        info.action(toggle);

        ItemStack padlock = DoorProbeActions.cutPadlock(player);
        if (!door.locked()) {
            ProbeAction fit = ProbeAction.of(DoorProbeActions.FIT_LOCK,
                            Component.translatable("probe.wfballistics.door.action.lock"))
                    .withIcon(new ProbeElement.Icon(new ItemStack(ModDoors.DOOR_LOCK.get()), 10, false));
            info.action(padlock.isEmpty()
                    ? fit.unavailable(Component.translatable("probe.wfballistics.door.need_lock"))
                    : fit);
        } else {
            ProbeAction take = ProbeAction.of(DoorProbeActions.TAKE_LOCK,
                            Component.translatable("probe.wfballistics.door.action.unlock"))
                    .withIcon(new ProbeElement.Icon(new ItemStack(ModDoors.DOOR_LOCK.get()), 10, false));
            info.action(keyed ? take : take.unavailable(needKey));
        }

        for (int skin = 0; skin < type.skins(); skin++) {
            if (skin == door.skin()) {
                continue;
            }
            ProbeAction pick = ProbeAction.of(DoorProbeActions.SKIN, skin,
                    Component.translatable("probe.wfballistics.door.action.skin", skin + 1));
            info.action(keyed ? pick : pick.unavailable(needKey));
        }
    }

    /** The readout. */
    private static void append(ProbeInfo info, DoorType type, DoorBlockEntity door, ItemStack mainHand,
                               ItemStack offHand, int powered) {
        info.title(Component.translatable("block.wfballistics." + type.id()));

        ItemStack item = new ItemStack(ModDoors.DOOR_ITEMS.get(type).get());
        info.titleIcon(new ProbeElement.Icon(item, 12, false));
        if (door == null) {
            info.text(Component.translatable("probe.wfballistics.door.orphan"), ChatFormatting.RED);
            return;
        }

        DoorState state = door.state();
        float open = door.openFraction(0.0f);
        int colour = switch (state) {
            case OPEN -> 0xFF55FF55;
            case CLOSED -> 0xFFFF5555;
            default -> 0xFFFFAA00;
        };
        info.text(Component.translatable(
                "probe.wfballistics.door.state." + state.name().toLowerCase(java.util.Locale.ROOT)), colour);

        if (state.moving()) {
            info.bar(open, 90, colour, Component.literal(Math.round(open * 100) + "%"));
        }

        if (door.locked()) {
            ItemStack key = new ItemStack(ModDoors.DOOR_KEY.get());
            boolean opens = com.wf.wfballistics.door.DoorKeyItem.opens(mainHand, door.pins())
                    || com.wf.wfballistics.door.DoorKeyItem.opens(offHand, door.pins());
            info.add(new ProbeElement.Row(List.of(
                    ProbeElement.Icon.of(key),
                    ProbeElement.Text.of(Component.translatable(opens
                                    ? "probe.wfballistics.door.key_fits"
                                    : "probe.wfballistics.door.locked"),
                            opens ? 0xFF55FF55 : 0xFFFFAA00)), 4, ProbeElement.Align.CENTRE));
        }

        if (powered > 0) {
            info.add(new ProbeElement.Row(List.of(
                    new ProbeElement.AtlasSprite(
                            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS,
                            REDSTONE, 10, 10, 0xFFFF4040),
                    ProbeElement.Text.of(
                            Component.translatable("probe.wfballistics.door.powered", powered),
                            0xFFFF6666)), 4, ProbeElement.Align.CENTRE));
        }

        if (type.skins() > 1) {
            info.text(Component.translatable("tooltip.wfballistics.door.skin",
                    door.skin() + 1, type.skins()), 0xFF888888);
        }
    }
}
