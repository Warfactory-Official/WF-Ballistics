package com.wf.wfballistics.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/** Which pixels of a feed are a warm body. */
final class CameraHeatMask {

    /** Must match the target named in {@code wf_cam_thermal.json}. */
    static final String TARGET = "wf_heat";

    /** Sections either side of the lens searched for thermal occluders. Five cubed, so 80 blocks. */
    private static final int OCCLUDER_SECTIONS = 2;
    /** Ticks a gathered occluder set is reused for before being rebuilt. */
    private static final int OCCLUDER_TTL = 20;

    private static final List<AABB> OCCLUDERS = new ArrayList<>();
    private static long occludersAtSection = Long.MIN_VALUE;
    private static long occludersAtTick = Long.MIN_VALUE;

    private CameraHeatMask() {
    }

    /**
     * Draw every visible entity into the chain's stencil target, then hand the feed's target back.
     *
     * @return false if the chain has no stencil target, which means the chain in force is not the thermal one
     */
    static boolean render(PostChain chain, RenderTarget feed, Camera camera,
                          Matrix4f frustumMatrix, Matrix4f projection, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mask = chain.getTempTarget(TARGET);
        if (mask == null || mc.level == null) {
            return false;
        }

        mask.bindWrite(true);
        borrowDepthFrom(feed);
        GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GlStateManager._clear(GL30.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL30.GL_LEQUAL);
        // Read the feed's depth, never write to it: the stencil must not disturb the picture it describes.
        RenderSystem.depthMask(false);
        RenderSystem.polygonOffset(-1.0f, -10.0f);
        RenderSystem.enablePolygonOffset();

        Frustum frustum = new Frustum(frustumMatrix, projection);
        Vec3 eye = camera.getPosition();
        frustum.prepare(eye.x, eye.y, eye.z);

        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Glass first, into the depth buffer, before anything is tested against it.
        occlude(mc, pose, eye);

        mc.getEntityRenderDispatcher().setRenderShadow(false);
        try {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity == camera.getEntity()
                        || !mc.getEntityRenderDispatcher().shouldRender(entity, frustum, eye.x, eye.y, eye.z)) {
                    continue;
                }
                double x = Mth.lerp((double) partialTick, entity.xOld, entity.getX());
                double y = Mth.lerp((double) partialTick, entity.yOld, entity.getY());
                double z = Mth.lerp((double) partialTick, entity.zOld, entity.getZ());
                float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
                mc.getEntityRenderDispatcher().render(entity,
                        x - eye.x, y - eye.y, z - eye.z,
                        yaw, partialTick, pose, buffers, LightTexture.FULL_BRIGHT);
            }
            buffers.endBatch();
        } finally {
            mc.getEntityRenderDispatcher().setRenderShadow(true);
            RenderSystem.polygonOffset(0.0f, 0.0f);
            RenderSystem.disablePolygonOffset();
            RenderSystem.depthMask(true);
            feed.bindWrite(true);
        }
        return true;
    }

    /** Is this block transparent to the eye and opaque to a thermal sensor? */
    private static boolean opaqueToThermal(BlockState state) {
        return state.getBlock() instanceof HalfTransparentBlock || state.getBlock() instanceof IronBarsBlock;
    }

    /** Write the thermal occluders around the lens into the depth buffer, as depth only. */
    private static void occlude(Minecraft mc, PoseStack pose, Vec3 eye) {
        gather(mc, eye);
        if (OCCLUDERS.isEmpty()) {
            return;
        }
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        BufferBuilder builder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        Matrix4f matrix = pose.last().pose();
        for (int i = 0; i < OCCLUDERS.size(); i++) {
            box(builder, matrix, OCCLUDERS.get(i), eye);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
    }

    /** Collect the occluders near the lens, reusing the last set while it is still fresh. */
    private static void gather(Minecraft mc, Vec3 eye) {
        int sx = SectionPos.blockToSectionCoord(Mth.floor(eye.x));
        int sy = SectionPos.blockToSectionCoord(Mth.floor(eye.y));
        int sz = SectionPos.blockToSectionCoord(Mth.floor(eye.z));
        long here = SectionPos.asLong(sx, sy, sz);
        long now = mc.level.getGameTime();
        if (here == occludersAtSection && now - occludersAtTick < OCCLUDER_TTL) {
            return;
        }
        occludersAtSection = here;
        occludersAtTick = now;
        OCCLUDERS.clear();

        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -OCCLUDER_SECTIONS; dx <= OCCLUDER_SECTIONS; dx++) {
            for (int dz = -OCCLUDER_SECTIONS; dz <= OCCLUDER_SECTIONS; dz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(sx + dx, sz + dz, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int dy = -OCCLUDER_SECTIONS; dy <= OCCLUDER_SECTIONS; dy++) {
                    int index = chunk.getSectionIndexFromSectionY(sy + dy);
                    if (index < 0 || index >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[index];
                    if (section.hasOnlyAir() || !section.maybeHas(CameraHeatMask::opaqueToThermal)) {
                        continue;
                    }
                    int ox = (sx + dx) << 4;
                    int oy = (sy + dy) << 4;
                    int oz = (sz + dz) << 4;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (!opaqueToThermal(state)) {
                                    continue;
                                }
                                at.set(ox + x, oy + y, oz + z);
                                VoxelShape shape = state.getShape(mc.level, at);
                                if (shape.isEmpty()) {
                                    continue;
                                }
                                // The block's own shape, so a pane occludes a pane's worth and not a cube's.
                                OCCLUDERS.add(shape.bounds().move(at));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void box(BufferBuilder builder, Matrix4f matrix, AABB b, Vec3 eye) {
        float x0 = (float) (b.minX - eye.x);
        float y0 = (float) (b.minY - eye.y);
        float z0 = (float) (b.minZ - eye.z);
        float x1 = (float) (b.maxX - eye.x);
        float y1 = (float) (b.maxY - eye.y);
        float z1 = (float) (b.maxZ - eye.z);
        quad(builder, matrix, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(builder, matrix, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1);
        quad(builder, matrix, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
        quad(builder, matrix, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        quad(builder, matrix, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1);
        quad(builder, matrix, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
    }

    private static void quad(BufferBuilder b, Matrix4f m, float ax, float ay, float az, float bx, float by,
                             float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
        b.addVertex(m, ax, ay, az);
        b.addVertex(m, bx, by, bz);
        b.addVertex(m, cx, cy, cz);
        b.addVertex(m, dx, dy, dz);
    }

    private static void borrowDepthFrom(RenderTarget feed) {
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_TEXTURE_2D, feed.getDepthTextureId(), 0);
    }
}
