package com.wf.wflib.grenade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wflib.grenade.ThrownCharge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/** Draws the charge's look stack in {@link ItemDisplayContext#GROUND}, turned by {@link ThrownCharge#spin}. */
public final class ChargeRenderer extends EntityRenderer<ThrownCharge> {

    private final Quaternionf turn = new Quaternionf();

    public ChargeRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.08f;
    }

    @Override
    public void render(ThrownCharge charge, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light) {
        ItemStack look = charge.look();
        if (look.isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.mulPose(turn.set(charge.spinO).slerp(charge.spin, partialTick));
        Minecraft.getInstance().getItemRenderer().renderStatic(look, ItemDisplayContext.GROUND, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, charge.level(), charge.getId());
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ThrownCharge charge) {
        return null;
    }
}
