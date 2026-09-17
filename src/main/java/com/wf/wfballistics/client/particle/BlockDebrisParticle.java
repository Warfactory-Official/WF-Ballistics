package com.wf.wfballistics.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A chunk of flying debris: a small tumbling copy of the block it came from, built each frame on the shared terrain
 * particle buffer (a tiny per-particle "VBO").
 */
public class BlockDebrisParticle extends Particle {

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final Direction[] FACES_AND_NULL = { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null };

    private final BlockState state;
    // Pre-baked, pre-scaled quads relative to the chunk centre. Each entry is 4 verts * (x,y,z,u,v,r,g,b).
    private final List<float[]> quads;
    private final float axisX;
    private final float axisY;
    private final float axisZ;
    private float spin;

    protected BlockDebrisParticle(ClientLevel level, double x, double y, double z,
                                  double dx, double dy, double dz, BlockState state) {
        super(level, x, y, z);
        this.state = state;

        this.xd = dx * 1.4D;
        this.yd = dy + 0.3D + this.random.nextFloat() * 0.2D;
        this.zd = dz * 1.4D;
        this.gravity = 1.3F;
        this.lifetime = 32 + this.random.nextInt(34);
        // Near full-block-sized chunks.
        float halfSize = 0.4F + this.random.nextFloat() * 0.12F;
        this.setSize(halfSize, halfSize);
        this.roll = this.random.nextFloat() * TWO_PI;
        this.oRoll = this.roll;
        this.spin = (this.random.nextFloat() - 0.5F) * 1.4F;

        float ax = this.random.nextFloat() * 2.0F - 1.0F;
        float ay = this.random.nextFloat() * 2.0F - 1.0F;
        float az = this.random.nextFloat() * 2.0F - 1.0F;
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1.0e-4F) {
            ax = 0.0F;
            ay = 1.0F;
            az = 0.0F;
            len = 1.0F;
        }
        this.axisX = ax / len;
        this.axisY = ay / len;
        this.axisZ = az / len;

        this.quads = bake(state, level, BlockPos.containing(x, y, z), halfSize * 2.0F);
    }

    /** Extract the block model's quads into camera-space-ready, tinted, scaled geometry. */
    private static List<float[]> bake(BlockState state, ClientLevel level, BlockPos pos, float scale) {
        Minecraft mc = Minecraft.getInstance();
        BlockColors blockColors = mc.getBlockColors();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        RandomSource rand = RandomSource.create();
        List<float[]> out = new ArrayList<>();

        // Walk every render layer so split base/overlay models (e.g. solid stone + cutout ore) are fully covered.
        List<RenderType> layers = new ArrayList<>();
        ChunkRenderTypeSet renderTypes = model.getRenderTypes(state, RandomSource.create(0L), ModelData.EMPTY);
        for (RenderType rt : renderTypes) {
            layers.add(rt);
        }
        if (layers.isEmpty()) {
            layers.add(null); // fall back to the model's full quad list
        }

        for (RenderType layer : layers) {
            for (Direction face : FACES_AND_NULL) {
                rand.setSeed(42L);
                for (BakedQuad quad : model.getQuads(state, face, rand, ModelData.EMPTY, layer)) {
                    int tint = quad.getTintIndex();
                    int color = tint == -1 ? 0xFFFFFFFF : blockColors.getColor(state, level, pos, tint);
                    float tr = (color >> 16 & 0xFF) / 255.0F;
                    float tg = (color >> 8 & 0xFF) / 255.0F;
                    float tb = (color & 0xFF) / 255.0F;
                    Direction dir = quad.getDirection();
                    float shade = shadeFor(dir);

                    // Push tinted overlay layers a hair outward along their normal to avoid z-fighting the base.
                    float eps = tint != -1 && dir != null ? 0.0016F : 0.0F;
                    float nx = dir == null ? 0.0F : eps * dir.getStepX();
                    float ny = dir == null ? 0.0F : eps * dir.getStepY();
                    float nz = dir == null ? 0.0F : eps * dir.getStepZ();

                    int[] v = quad.getVertices();
                    int stride = v.length / 4;
                    float[] data = new float[32];
                    for (int i = 0; i < 4; i++) {
                        int b = i * stride;
                        float px = Float.intBitsToFloat(v[b]);
                        float py = Float.intBitsToFloat(v[b + 1]);
                        float pz = Float.intBitsToFloat(v[b + 2]);
                        int vc = v[b + 3]; // baked vertex colour, RGBA little-endian
                        float vr = (vc & 0xFF) / 255.0F;
                        float vg = (vc >> 8 & 0xFF) / 255.0F;
                        float vb = (vc >> 16 & 0xFF) / 255.0F;
                        float u = Float.intBitsToFloat(v[b + 4]);
                        float w = Float.intBitsToFloat(v[b + 5]);

                        int o = i * 8;
                        data[o] = (px - 0.5F + nx) * scale;
                        data[o + 1] = (py - 0.5F + ny) * scale;
                        data[o + 2] = (pz - 0.5F + nz) * scale;
                        data[o + 3] = u;
                        data[o + 4] = w;
                        data[o + 5] = shade * tr * vr;
                        data[o + 6] = shade * tg * vg;
                        data[o + 7] = shade * tb * vb;
                    }
                    out.add(data);
                }
            }
        }
        return out;
    }

    /** True when an axis tried to move but collision swallowed most of it. */
    private static boolean blocked(double intended, double actual) {
        return Math.abs(intended) > 1.0e-5D && Math.abs(actual - intended) > 1.0e-4D;
    }

    private static float shadeFor(Direction dir) {
        if (dir == null) {
            return 0.9F;
        }
        return switch (dir) {
            case UP -> 1.0F;
            case DOWN -> 0.5F;
            case NORTH, SOUTH -> 0.8F;
            default -> 0.6F; // EAST / WEST
        };
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.TERRAIN_SHEET;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        this.yd -= 0.04D * this.gravity;
        double intendedX = this.xd;
        double intendedY = this.yd;
        double intendedZ = this.zd;
        this.move(this.xd, this.yd, this.zd);
        // If collision clamped the movement on any axis, the chunk struck something: shatter, don't bounce.
        boolean hit = this.onGround || blocked(intendedX, this.x - this.xo) || blocked(intendedY, this.y - this.yo) ||
                blocked(intendedZ, this.z - this.zo);
        if (hit) {
            shatter();
            return;
        }
        this.xd *= 0.985D;
        this.yd *= 0.985D;
        this.zd *= 0.985D;
        this.roll += this.spin;
    }

    /** Vanish and leave behind the vanilla block-break crack particles for this block. */
    private void shatter() {
        if (!this.state.isAir()) {
            BlockParticleOption crack = new BlockParticleOption(ParticleTypes.BLOCK, this.state);
            for (int i = 0; i < 16; i++) {
                double vx = (this.random.nextDouble() - 0.5D) * 0.2D;
                double vy = this.random.nextDouble() * 0.2D;
                double vz = (this.random.nextDouble() - 0.5D) * 0.2D;
                this.level.addParticle(crack, this.x, this.y, this.z, vx, vy, vz);
            }
        }
        this.remove();
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        Vec3 cam = camera.getPosition();
        float cx = (float) (Mth.lerp(partialTicks, this.xo, this.x) - cam.x);
        float cy = (float) (Mth.lerp(partialTicks, this.yo, this.y) - cam.y);
        float cz = (float) (Mth.lerp(partialTicks, this.zo, this.z) - cam.z);
        float angle = Mth.lerp(partialTicks, this.oRoll, this.roll);
        Quaternionf rotation = new Quaternionf().rotateAxis(angle, this.axisX, this.axisY, this.axisZ);
        int light = this.getLightColor(partialTicks);
        Vector3f tmp = new Vector3f();

        for (float[] quad : this.quads) {
            for (int i = 0; i < 4; i++) {
                int o = i * 8;
                tmp.set(quad[o], quad[o + 1], quad[o + 2]);
                rotation.transform(tmp);
                buffer.addVertex(tmp.x + cx, tmp.y + cy, tmp.z + cz)
                        .setUv(quad[o + 3], quad[o + 4])
                        .setColor(quad[o + 5], quad[o + 6], quad[o + 7], 1.0F)
                        .setLight(light)
                        ;
            }
        }
    }

    public static class Provider implements ParticleProvider<BlockParticleOption> {

        @Override
        public Particle createParticle(BlockParticleOption type, ClientLevel level, double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new BlockDebrisParticle(level, x, y, z, dx, dy, dz, type.getState());
        }
    }
}
