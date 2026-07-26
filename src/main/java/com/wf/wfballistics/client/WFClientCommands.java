package com.wf.wfballistics.client;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.client.gui.ModelGalleryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = WFBallistics.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WFClientCommands {

    private WFClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wfmodels").executes(ctx -> {
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ModelGalleryScreen()));
            return 1;
        }));
    }
}
