package com.wf.wfballistics.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;

/**
 * A block chip flung from an explosion. Unlike vanilla {@link net.minecraft.client.particle.TerrainParticle},
 * which normalises away any launch velocity in the base {@code Particle} constructor and spawns at half quad
 * size, this keeps the spray velocity it is given, renders at a readable size, and lives long enough to arc
 * out and land. Textured from the surface block's atlas sprite, so it renders on the block sheet.
 */
public class BlockShrapnelParticle extends TextureSheetParticle {

    public BlockShrapnelParticle(ClientLevel level, double x, double y, double z,
                                 double xd, double yd, double zd, BlockState state) {
        super(level, x, y, z);
        this.setSprite(Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state));
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.gravity = 1.0F;
        this.friction = 0.98F;
        this.hasPhysics = true;
        this.lifetime = 20 + this.random.nextInt(20);
        this.quadSize = 0.1F + this.random.nextFloat() * 0.08F;

        this.rCol = 0.75F;
        this.gCol = 0.75F;
        this.bCol = 0.75F;
        BlockPos pos = BlockPos.containing(x, y, z);
        if (IClientBlockExtensions.of(state).areBreakingParticlesTinted(state, level, pos)) {
            int color = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
            this.rCol *= (color >> 16 & 0xFF) / 255.0F;
            this.gCol *= (color >> 8 & 0xFF) / 255.0F;
            this.bCol *= (color & 0xFF) / 255.0F;
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.TERRAIN_SHEET;
    }
}
