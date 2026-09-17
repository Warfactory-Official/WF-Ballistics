package com.wf.wfballistics.mine.client;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.item.MinePreset;
import com.wf.wfballistics.item.MinePresetRegistry;
import com.wf.wfballistics.demolition.DetonatorItem;
import com.wf.wfballistics.mine.DefuseMethod;
import com.wf.wfballistics.mine.MineEntity;
import com.wf.wfballistics.mine.MineProbeActions;
import com.wf.wfballistics.probe.ProbeAction;
import com.wf.wfballistics.probe.ProbeCapabilities;
import com.wf.wfballistics.probe.ProbeElement;
import com.wf.wfballistics.probe.ProbeInfo;
import com.wf.wfballistics.probe.ProbeProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** What the probe says about a mine, and what you can do to it from where you are standing. */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MineProbe {

    private MineProbe() {
    }

    private static final ProbeProvider.Entities PANEL = (info, ctx, entity) -> {
        if (!(entity instanceof MineEntity mine)) {
            return;
        }
        MinePreset preset = MinePresetRegistry.get(mine.getPresetId());
        append(info, mine, preset);
        actions(info, ctx.player(), mine, preset);
    };

    @SubscribeEvent
    static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerEntity(ProbeCapabilities.ENTITY, ModEntities.MINE.get(),
                (entity, ctx) -> PANEL);
    }

    private static void append(ProbeInfo info, MineEntity mine, @Nullable MinePreset preset) {
        ItemStack stack = MinePresetRegistry.stackFor(mine.getPresetId());
        if (!stack.isEmpty()) {
            info.titleFallback(stack.getHoverName());
            info.titleIconFallback(new ProbeElement.Icon(stack, 12, false));
        }

        MineEntity.State state = mine.getState();
        info.text(Component.translatable("probe.wfballistics.mine.state." + state.name()
                .toLowerCase(Locale.ROOT)), colour(state));

        info.entry(Component.translatable("probe.wfballistics.mine.finish"),
                Component.translatable("probe.wfballistics.mine.finish." + mine.getCamo()
                        .id()));

        if (mine.isRack()) {
            info.entry(Component.translatable("probe.wfballistics.mine.canisters"),
                    Component.literal(mine.canisters() + " / " + mine.canisterCapacity())
                            .withStyle(mine.canisters() > 0 ? ChatFormatting.WHITE : ChatFormatting.RED));
        }

        if (mine.isBuried()) {
            info.text(Component.translatable("probe.wfballistics.mine.buried"), ChatFormatting.DARK_GREEN);
        } else if (mine.isBuriable()) {
            info.text(Component.translatable("probe.wfballistics.mine.on_surface"), ChatFormatting.GRAY);
        }

        if (preset == null) {
            return;
        }
        if (preset.sownOnly()) {
            info.text(Component.translatable("probe.wfballistics.mine.sown"), ChatFormatting.GRAY);
        }
        if (preset.arc() < 360.0) {
            info.entry(Component.translatable("probe.wfballistics.mine.arc"),
                    Component.translatable("probe.wfballistics.mine.arc.value",
                            (int) preset.arc(), compass(mine.getYRot())));
        }
        if (preset.defuseMethod() == DefuseMethod.NONE) {
            info.text(Component.translatable("probe.wfballistics.mine.no_defuse"), ChatFormatting.RED);
        } else if (preset.defuseMethod() == DefuseMethod.TOOL && preset.defuseTool() != null) {
            info.entry(Component.translatable("probe.wfballistics.mine.defuse_with"),
                    Component.translatable("tag." + preset.defuseTool()
                            .location()
                            .toLanguageKey()));
        }
        if (preset.armsOnlyInWater()) {
            info.text(Component.translatable("probe.wfballistics.mine.water_only"), ChatFormatting.AQUA);
        }
        if (preset.selfDestructTicks() > 0) {
            info.entry(Component.translatable("probe.wfballistics.mine.self_destruct"),
                    Component.literal(clock(preset.selfDestructTicks())));
        }
    }

    private static final String[] POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    /** A mine's yaw as a compass point. */
    private static String compass(float yaw) {
        int index = Math.floorMod(Math.round(yaw / 45.0f), POINTS.length);
        return POINTS[index];
    }

    /** Ticks as {@code m:ss}, which is how long a field lasts rather than how many ticks it lasts. */
    private static String clock(int ticks) {
        int seconds = ticks / 20;
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    /** What you can do to this mine from here. */
    private static void actions(ProbeInfo info, Player player, MineEntity mine, @Nullable MinePreset preset) {
        arming(info, player, mine, preset);
        wiring(info, player, mine);
        reloading(info, player, mine);
        defusing(info, player, mine, preset);
        burying(info, player, mine);
    }

    /** Wiring the mine to the detonator in hand, or taking it off again. */
    private static void wiring(ProbeInfo info, Player player, MineEntity mine) {
        ItemStack clacker = DetonatorItem.inHand(player);
        if (clacker.isEmpty()) {
            return;
        }
        boolean wired = DetonatorItem.isWired(clacker, mine);
        info.action(ProbeAction.of(MineProbeActions.LINK, wired ? 0 : 1,
                Component.translatable(wired ? "probe.wfballistics.mine.action.unlink"
                        : "probe.wfballistics.mine.action.link")));
    }

    /** Putting a canister back on a rack. Only ever shown for a rack, which is the only thing that has any. */
    private static void reloading(ProbeInfo info, Player player, MineEntity mine) {
        if (!mine.isRack()) {
            return;
        }
        ProbeAction reload = ProbeAction.of(MineProbeActions.RELOAD,
                Component.translatable("probe.wfballistics.mine.action.reload"));
        Component refusal = mine.reloadRefusal(player);
        info.action(refusal == null ? reload : reload.unavailable(refusal));
    }

    private static void arming(ProbeInfo info, Player player, MineEntity mine, @Nullable MinePreset preset) {
        boolean canEverBeSafe = mine.getState() == MineEntity.State.SAFE
                || (preset != null && preset.requiresActivation());
        if (!canEverBeSafe) {
            return;
        }
        ProbeAction arm = ProbeAction.of(MineProbeActions.ARM,
                Component.translatable("probe.wfballistics.mine.action.arm"));
        Component refusal = mine.armRefusal(player);
        info.action(refusal == null ? arm : arm.unavailable(refusal));
    }

    /** The defusal row, offered on every mine including the ones that refuse it. */
    private static void defusing(ProbeInfo info, Player player, MineEntity mine,
                                 @Nullable MinePreset preset) {
        if (preset == null) {
            return;
        }
        ProbeAction defuse = ProbeAction.of(MineProbeActions.DEFUSE,
                Component.translatable(mine.isRack() ? "probe.wfballistics.mine.action.pack_up"
                        : "probe.wfballistics.mine.action.defuse"));
        Component refusal = MineEntity.defuseRefusal(player, preset.defuseMethod(), preset.defuseTool());
        info.action(refusal == null ? defuse : defuse.unavailable(refusal));
    }

    private static void burying(ProbeInfo info, Player player, MineEntity mine) {
        if (!mine.isBuriable()) {
            return;
        }
        boolean digIn = !mine.isBuried();
        ProbeAction bury = ProbeAction.of(MineProbeActions.BURY, digIn ? 1 : 0,
                Component.translatable(digIn ? "probe.wfballistics.mine.action.bury"
                        : "probe.wfballistics.mine.action.unbury"));
        Component refusal = mine.buryRefusal(player, digIn);
        info.action(refusal == null ? bury : bury.unavailable(refusal));
    }

    private static ChatFormatting colour(MineEntity.State state) {
        return switch (state) {
            case ARMED -> ChatFormatting.RED;
            case ARMING -> ChatFormatting.GOLD;
            case TRIPPED -> ChatFormatting.LIGHT_PURPLE;
            case SAFE -> ChatFormatting.GREEN;
        };
    }
}
