package com.wf.wfballistics.probe.client;

import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.block.entity.LaunchConfig;
import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import com.wf.wfballistics.probe.ProbeCapabilities;
import com.wf.wfballistics.probe.ProbeElement;
import com.wf.wfballistics.probe.ProbeProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.List;

/** Probe panels for this mod's own blocks. */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ProbeBuiltins {

    private ProbeBuiltins() {
    }

    @SubscribeEvent
    static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(ProbeCapabilities.BLOCK, ModBlockEntities.MISSILE_DISPENSER.get(),
                (dispenser, context) -> DISPENSER);
    }

    private static final ProbeProvider.Blocks DISPENSER = (info, ctx, state, pos, blockEntity) -> {
            if (!(blockEntity instanceof MissileDispenserBlockEntity dispenser)) {
                return;
            }
            LaunchConfig config = dispenser.getConfig();
            if (config == null) {
                info.text(Component.translatable("probe.wfballistics.dispenser.empty"),
                        ChatFormatting.DARK_GRAY);
                return;
            }
            ResourceLocation model = MissileModels.model(config.modelId);
            info.add(new ProbeElement.Row(List.of(
                    ProbeElement.Model.spinning(model, 40),
                    new ProbeElement.Column(List.of(
                            ProbeElement.Text.of(Component.translatable(
                                    "probe.wfballistics.dispenser.loaded",
                                    Component.literal(config.modelId.getPath())), info.accent()),
                            ProbeElement.Text.of(Component.translatable(
                                    "probe.wfballistics.dispenser.warhead",
                                    Component.literal(config.warheadId.getPath())), 0xFFAAAAAA),
                            ProbeElement.Text.of(Component.translatable(
                                    "probe.wfballistics.dispenser.target",
                                    (int) config.targetX, (int) config.targetY, (int) config.targetZ),
                                    0xFFAAAAAA)), 2)), 6, ProbeElement.Align.CENTRE));
    };
}
