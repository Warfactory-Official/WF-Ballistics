package com.wf.wfballistics.fire;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Public entry point for the custom-fire system: the capability token plus the convenience methods other
 * systems use to set things alight ({@code WFFire.ignite(entity, FireType.PHOSPHORUS, 200)}).
 *
 * <p>Registered on the mod event bus to declare the capability during startup. The per-entity attachment
 * and the burning logic live in {@link FireHandler}.
 */
@Mod.EventBusSubscriber(modid = WFBallistics.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WFFire {

    public static final Capability<WFFireData> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {
    });
    public static final ResourceLocation ID = new ResourceLocation(WFBallistics.MODID, "fire");

    private WFFire() {
    }

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(WFFireData.class);
    }

    /**
     * @return the entity's fire state, or {@code null} if the capability is absent (e.g. wrong side)
     */
    public static WFFireData get(LivingEntity entity) {
        return entity.getCapability(CAPABILITY).orElse(null);
    }

    /**
     * Sets an entity alight with custom fire. Server-side; no-op if the capability is missing.
     */
    public static void ignite(LivingEntity entity, FireType type, int ticks) {
        WFFireData data = get(entity);
        if (data != null) {
            data.ignite(type, ticks);
        }
    }
}
