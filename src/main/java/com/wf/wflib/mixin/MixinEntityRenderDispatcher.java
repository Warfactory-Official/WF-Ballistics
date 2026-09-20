package com.wf.wflib.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.client.render.OBBRenderer;
import com.wf.wflib.entity.OBBEntity;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws OBB hitbox wireframes alongside the vanilla AABB box when debug hitboxes are shown (F3+B). */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @Inject(method = "renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;FFFF)V",
            at = @At("RETURN"))
    private static void wflib$renderHitbox(PoseStack pMatrixStack, VertexConsumer pBuffer, Entity pEntity,
                                                  float pPartialTicks, float green, float blue, float alpha, CallbackInfo ci) {
        if (pEntity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
            OBBRenderer.render(pEntity, obbEntity.getOBBs(), pMatrixStack, pBuffer, 0, 1, 0, 1, pPartialTicks);
            // Swept-collision debug overlay: corridor + substep body ghosts (see OBBRenderer#renderSweep).
            OBBRenderer.renderSweep(pEntity, obbEntity.getOBBs(), pMatrixStack, pBuffer);
        }
    }
}
