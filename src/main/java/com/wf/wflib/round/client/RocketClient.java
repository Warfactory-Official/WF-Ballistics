package com.wf.wflib.round.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wflib.round.RocketEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** Rocket look and exhaust through {@link RoundRenderers}; unregistered preset => nothing drawn. */
public final class RocketClient extends EntityRenderer<RocketEntity> {

    public RocketClient(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    public static void tick(RocketEntity rocket) {
        RoundRenderer renderer = rocket.preset() == null ? null : RoundRenderers.get(rocket.preset().id());
        if (renderer == null) {
            return;
        }
        if (rocket.tickCount == 1) {
            renderer.launched(rocket);
        }
        if (rocket.burning()) {
            renderer.exhaust((ClientLevel) rocket.level(), rocket);
        }
    }

    @Override
    public void render(RocketEntity rocket, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light) {
        RoundRenderer renderer = rocket.preset() == null ? null : RoundRenderers.get(rocket.preset().id());
        if (renderer != null) {
            renderer.render(pose, buffers, light, rocket.getViewVector(partialTick),
                    rocket.burning());
        }
    }

    @Override
    public ResourceLocation getTextureLocation(RocketEntity rocket) {
        return null;
    }
}
