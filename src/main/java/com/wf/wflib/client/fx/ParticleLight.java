package com.wf.wflib.client.fx;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

public final class ParticleLight {

    private ParticleLight() {
    }

    /** Max with light at {@code pos.above()}: avoids black particles sitting on a block's top face. */
    public static int surface(BlockAndTintGetter level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        int here = LevelRenderer.getLightColor(level, pos);
        int above = LevelRenderer.getLightColor(level, pos.above());
        int block = Math.max(LightTexture.block(here), LightTexture.block(above));
        int sky = Math.max(LightTexture.sky(here), LightTexture.sky(above));
        return LightTexture.pack(block, sky);
    }
}
