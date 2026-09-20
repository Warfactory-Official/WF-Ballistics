package com.wf.wflib.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.gui.ModelGalleryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
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
