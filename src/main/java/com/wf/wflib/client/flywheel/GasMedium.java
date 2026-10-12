package com.wf.wflib.client.flywheel;

import com.mojang.blaze3d.platform.NativeImage;
import com.wf.gemrender.medium.CameraMedium;
import com.wf.gemrender.particle.ParticleBuffer;
import com.wf.wflib.WFLib;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class GasMedium {
    private static final float CULL = 6.0F;

    private static float[] spriteAlpha;

    private GasMedium() {
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        Vec3 eye = event.getCamera()
                .getPosition();
        GasVolumeCluster cluster = MistCloudHandler.clusterContaining(eye);
        if (cluster != null) {
            CameraMedium.getInstance()
                    .inside(cluster.volume(), cluster.bounds());
            return;
        }
        InstancedMistEffect in = InstancedMistEffect.containing(eye);
        if (in == null) {
            CameraMedium.getInstance()
                    .outside();
            return;
        }
        AABB near = new AABB(eye, eye).inflate(CULL + ParticleBuffer.CULL_RAMP);
        AABB cloud = InstancedMistEffect.cloud(new AABB(eye, eye), in.style(), near);
        CameraMedium.getInstance()
                .inside(in.style(), in.extinction(spriteAlpha()), cloud, CULL);
    }

    private static float[] spriteAlpha() {
        if (spriteAlpha == null) {
            try (InputStream in = Minecraft.getInstance()
                    .getResourceManager()
                    .open(WFParticleTextures.MIST_SOFT);
                 NativeImage image = NativeImage.read(in)) {
                float[] alpha = new float[image.getWidth() * image.getHeight()];
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        alpha[y * image.getWidth() + x] = (image.getPixelRGBA(x, y) >>> 24) / 255.0F;
                    }
                }
                spriteAlpha = alpha;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return spriteAlpha;
    }
}
