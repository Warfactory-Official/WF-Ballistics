package com.wf.wfballistics.fire;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Public entry point for the custom-fire system: the per-entity {@link WFFireData} data attachment plus the
 * convenience methods other systems use to set things alight ({@code WFFire.ignite(entity, FireType.PHOSPHORUS, 200)}).
 *
 * <p>On NeoForge the state is a serializable {@link AttachmentType} (registered on the mod event bus) that
 * copies across death; there is no explicit per-entity attach step. The burning logic lives in
 * {@link FireHandler}.
 */
public final class WFFire {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, WFBallistics.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<WFFireData>> FIRE_DATA =
            ATTACHMENT_TYPES.register("fire", () ->
                    AttachmentType.serializable(WFFireData::new).copyOnDeath().build());

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "fire");

    private WFFire() {
    }

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }

    /**
     * @return the entity's fire state (data attachments are created lazily, so this is never {@code null})
     */
    public static WFFireData get(LivingEntity entity) {
        return entity.getData(FIRE_DATA);
    }

    /**
     * Sets an entity alight with custom fire. Server-side.
     */
    public static void ignite(LivingEntity entity, FireType type, int ticks) {
        get(entity).ignite(type, ticks);
    }
}
