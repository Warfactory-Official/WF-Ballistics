package com.wf.wflib.tv.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.wflib.WFLib;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class TvKeys {

    private static final String CATEGORY = "key.categories.wflib";

    /** Connect to the newest own TV round / let go of it. */
    public static final KeyMapping LINK = new KeyMapping(
            "key.wflib.tv_link", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.KEY_Y, CATEGORY);

    /** Full view <-> picture-in-picture. */
    public static final KeyMapping VIEW = new KeyMapping(
            "key.wflib.tv_view", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.KEY_M, CATEGORY);

    private TvKeys() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(LINK);
        event.register(VIEW);
    }
}
