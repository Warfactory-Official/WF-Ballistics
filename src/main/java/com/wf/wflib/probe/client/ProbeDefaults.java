package com.wf.wflib.probe.client;

import com.wf.wflib.probe.ProbeElement;
import com.wf.wflib.probe.ProbeRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** What the probe says about a thing nothing else described: its name, and its icon beside it. */
public final class ProbeDefaults {

    private ProbeDefaults() {
    }

    public static void register() {
        ProbeRegistry.addAnyBlock((info, ctx, state, pos, blockEntity) -> {
            info.titleFallback(state.getBlock().getName());
            ItemStack pick = state.getBlock().getCloneItemStack(ctx.level(), pos, state);
            if (!pick.isEmpty()) {
                info.titleIconFallback(new ProbeElement.Icon(pick, 12, false));
            }
        }, ProbeRegistry.DEFAULT_PRIORITY);

        ProbeRegistry.addAnyEntity((info, ctx, entity) -> {
            info.titleFallback(entity.getDisplayName());
            if (entity instanceof LivingEntity living) {
                float health = living.getHealth();
                float max = Math.max(1.0f, living.getMaxHealth());
                info.bar(health / max, 90, 0xFFCC3333,
                        Component.literal(round(health) + " / " + round(max)));
            }
        }, ProbeRegistry.DEFAULT_PRIORITY);
    }

    private static String round(float value) {
        return value >= 10.0f ? String.valueOf(Math.round(value)) : String.format("%.1f", value);
    }
}
