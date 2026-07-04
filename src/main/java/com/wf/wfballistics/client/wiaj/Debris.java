package com.wf.wfballistics.client.wiaj;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.wf.wfballistics.client.particle.RocketFlameParticle;
import com.wf.wfballistics.client.particle.WFParticleSprites;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;


public class Debris {

    private static final double MOTION_MULT = 3.0;
    private static final double GRAVITY = 0.15;
    private static final int MAX_AGE = 100;

    private final WorldInAJar jar;
    private final float pitchSpeed, yawSpeed;
    private final boolean trail;
    private final float trailScale;
    private final double vx;
    private final double vz;
    private double x, y, z, prevX, prevY, prevZ;
    private double vy;
    private float pitch, yaw, prevPitch, prevYaw;
    private int age = 0;
    private boolean canCollide = false;
    private boolean expired = false;

    private boolean baked = false;
    private VertexBuffer buffer; // null when baked-but-empty (all air)

    public Debris(RandomSource rand, double x, double y, double z, double vx, double vy, double vz, WorldInAJar jar) {
        this.x = this.prevX = x;
        this.y = this.prevY = y;
        this.z = this.prevZ = z;
        this.vx = vx * MOTION_MULT;
        this.vy = vy * MOTION_MULT;
        this.vz = vz * MOTION_MULT;
        this.jar = jar;
        this.pitchSpeed = rand.nextFloat() * 10.0F;
        this.yawSpeed = rand.nextFloat() * 10.0F;
        this.trail = rand.nextInt(3) == 0;
        this.trailScale = Math.max(jar.sizeY, 6) / 16.0F;
    }

    public boolean isExpired() {
        return this.expired;
    }

    public boolean isBaked() {
        return this.baked;
    }

    public void tick(ClientLevel level) {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevPitch = this.pitch;
        this.prevYaw = this.yaw;

        this.age++;
        if (this.age > 5) {
            this.canCollide = true;
        }

        this.pitch += this.pitchSpeed;
        this.yaw += this.yawSpeed;

        if (this.trail && WFParticleSprites.rocketFlame != null) {
            RocketFlameParticle flame = new RocketFlameParticle(level, this.x, this.y, this.z, this.trailScale);
            flame.setLifetime(50);
            flame.setCollision(false);
            flame.pickSprite(WFParticleSprites.rocketFlame);
            Minecraft.getInstance().particleEngine.add(flame);
        }

        this.vy -= GRAVITY;

        if (this.canCollide) {
            Vec3 from = new Vec3(this.x, this.y, this.z);
            Vec3 to = new Vec3(this.x + this.vx, this.y + this.vy, this.z + this.vz);
            BlockHitResult hit = level.clip(new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3 loc = hit.getLocation();
                this.x = loc.x;
                this.y = loc.y;
                this.z = loc.z;
                this.expired = true;
            } else {
                this.x += this.vx;
                this.y += this.vy;
                this.z += this.vz;
            }
        } else {
            this.x += this.vx;
            this.y += this.vy;
            this.z += this.vz;
        }

        if (this.age >= MAX_AGE) {
            this.expired = true;
        }
    }

    public void bake() {
        this.baked = true;

        BufferBuilder builder = new BufferBuilder(2048);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        PoseStack local = new PoseStack();
        boolean any = false;

        for (int lx = 0; lx < this.jar.sizeX; lx++) {
            for (int ly = 0; ly < this.jar.sizeY; ly++) {
                for (int lz = 0; lz < this.jar.sizeZ; lz++) {
                    BlockState state = this.jar.getBlockState(lx, ly, lz);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    local.pushPose();
                    local.translate(lx, ly, lz);
                    blockRenderer.renderBatched(state, new BlockPos(lx, ly, lz), this.jar, local, builder, true, random);
                    local.popPose();
                    any = true;
                }
            }
        }

        BufferBuilder.RenderedBuffer rendered = builder.end();
        if (!any) {
            rendered.release();
            this.buffer = null;
            return;
        }
        this.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        this.buffer.bind();
        this.buffer.upload(rendered);
        VertexBuffer.unbind();
    }


    public void render(PoseStack poseStack, Matrix4f projectionMatrix, Vec3 cam, float partialTick) {
        if (this.buffer == null) {
            return;
        }
        double ix = Mth.lerp(partialTick, this.prevX, this.x);
        double iy = Mth.lerp(partialTick, this.prevY, this.y);
        double iz = Mth.lerp(partialTick, this.prevZ, this.z);
        float rp = Mth.lerp(partialTick, this.prevPitch, this.pitch);
        float ry = Mth.lerp(partialTick, this.prevYaw, this.yaw);

        poseStack.pushPose();
        poseStack.translate(ix - cam.x, iy - cam.y, iz - cam.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(rp));
        poseStack.mulPose(Axis.ZP.rotationDegrees(ry));
        poseStack.translate(-this.jar.sizeX / 2.0, -this.jar.sizeY / 2.0, -this.jar.sizeZ / 2.0);

        this.buffer.bind();
        this.buffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());

        poseStack.popPose();
    }

    public void close() {
        if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
        }
    }
}
