package com.wf.wflib.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.gui.ModelGalleryScreen;
import com.wf.wflib.client.cam.FeedProbe;
import com.wf.wflib.stream.client.ClientChunkStreamAudit;
import com.wf.wflib.stream.client.ClientStreamDebug;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.Locale;

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
        event.getDispatcher().register(Commands.literal("wffeed").then(Commands.literal("cull")
                .then(Commands.literal("skip").executes(ctx -> probe(ctx.getSource(), FeedProbe.Mode.SKIP)))
                .then(Commands.literal("evict").executes(ctx -> probe(ctx.getSource(), FeedProbe.Mode.EVICT)))));
        event.getDispatcher().register(Commands.literal("wfstream")
                .then(Commands.literal("audit").executes(ctx -> {
                    int[] counts = ClientChunkStreamAudit.run(Minecraft.getInstance().level, "wflib-stream-audit-client.txt");
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "held=%d offView=%d undrawn=%d", counts[0], counts[1], counts[2])), false);
                    return 1;
                }))
                .then(Commands.literal("map")
                        .executes(ctx -> map(ctx.getSource(), 12))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> map(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static int map(CommandSourceStack source, int radius) {
        if (Minecraft.getInstance().level == null) {
            return 0;
        }
        String summary = ClientStreamDebug.map(radius);
        source.sendSuccess(() -> Component.literal("chunk map -> logs (" + summary + ")"), false);
        return 1;
    }

    private static int probe(CommandSourceStack source, FeedProbe.Mode mode) {
        FeedProbe.arm(mode);
        source.sendSuccess(() -> Component.literal("feed probe armed: " + mode), false);
        return 1;
    }
}
