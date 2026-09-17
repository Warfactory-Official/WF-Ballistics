package com.wf.wfballistics.client.cam;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** Draws a feed texture as a quad, right way up. */
public final class FeedBlit {

    private FeedBlit() {
    }

    /**
     * @param x0 left, {@code y0} top, in whatever space the matrix is in
     */
    public static void draw(ResourceLocation texture, Matrix4f matrix,
                            float x0, float y0, float x1, float y1) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Top of the picture is v=1, because that is where the framebuffer put it.
        buffer.addVertex(matrix, x0, y1, 0.0f).setUv(0.0f, 0.0f);
        buffer.addVertex(matrix, x1, y1, 0.0f).setUv(1.0f, 0.0f);
        buffer.addVertex(matrix, x1, y0, 0.0f).setUv(1.0f, 1.0f);
        buffer.addVertex(matrix, x0, y0, 0.0f).setUv(0.0f, 1.0f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.disableBlend();
    }
}
