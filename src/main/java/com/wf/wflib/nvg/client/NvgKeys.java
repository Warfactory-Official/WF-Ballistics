package com.wf.wflib.nvg.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.wflib.WFLib;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class NvgKeys {
    private static final String CATEGORY = "key.categories.wflib";

    public static final KeyMapping FLIP = key("nvg_flip", GLFW.GLFW_KEY_L);
    public static final KeyMapping IR = key("nvg_ir", GLFW.GLFW_KEY_GRAVE_ACCENT);
    public static final KeyMapping GAIN_UP = key("nvg_gain_up", GLFW.GLFW_KEY_EQUAL);
    public static final KeyMapping GAIN_DOWN = key("nvg_gain_down", GLFW.GLFW_KEY_MINUS);

    private NvgKeys() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.wflib." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code,
                CATEGORY);
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(FLIP);
        event.register(IR);
        event.register(GAIN_UP);
        event.register(GAIN_DOWN);
    }
}
