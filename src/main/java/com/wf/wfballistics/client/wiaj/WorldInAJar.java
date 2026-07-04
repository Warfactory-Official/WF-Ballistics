package com.wf.wfballistics.client.wiaj;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;


public class WorldInAJar implements BlockAndTintGetter {

    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

    private final BlockState[][][] blocks;
    private final BlockState air = Blocks.AIR.defaultBlockState();

    // Debris takes no light from the world it was chipped out of: a block dug from deep underground carries
    // zero light and would bake in pitch black. Every face uses full sky exposure instead, so the render-time
    // lightmap (time of day) is the only thing that shades it.
    private static final int BAKED_SKY_LIGHT = 15;
    private static final int BAKED_BLOCK_LIGHT = 0;

    private int tint = 0xFFFFFF;

    public WorldInAJar(int x, int y, int z) {
        this.sizeX = x;
        this.sizeY = y;
        this.sizeZ = z;
        this.blocks = new BlockState[x][y][z];
    }

    public static WorldInAJar fromLevel(Level level, int cX, int cY, int cZ, int size, int retry, RandomSource rand) {
        WorldInAJar jar = new WorldInAJar(size, size, size);
        if (size <= 0) {
            return jar;
        }
        int middle = size / 2 - 1;

        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 2; k++) {
                    jar.setBlock(middle + i, middle + j, middle + k,
                            level.getBlockState(new BlockPos(cX + i, cY + j, cZ + k)));
                }
            }
        }

        for (int layer = 2; layer <= size / 2; layer++) {
            for (int t = 0; t < retry; t++) {
                int jx = -layer + rand.nextInt(layer * 2 + 1);
                int jy = -layer + rand.nextInt(layer * 2 + 1);
                int jz = -layer + rand.nextInt(layer * 2 + 1);
                if (jar.neighborNonAir(middle + jx, middle + jy, middle + jz)) {
                    jar.setBlock(middle + jx, middle + jy, middle + jz,
                            level.getBlockState(new BlockPos(cX + jx, cY + jy, cZ + jz)));
                }
            }
        }

        jar.tint = level.getBlockTint(new BlockPos(cX, cY, cZ), BiomeColors.GRASS_COLOR_RESOLVER);
        return jar;
    }

    public void setBlock(int x, int y, int z, BlockState state) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            return;
        }
        this.blocks[x][y][z] = state;
    }

    public BlockState getBlockState(int x, int y, int z) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            return air;
        }
        BlockState s = this.blocks[x][y][z];
        return s != null ? s : air;
    }

    private boolean neighborNonAir(int x, int y, int z) {
        return !getBlockState(x + 1, y, z).isAir() || !getBlockState(x - 1, y, z).isAir()
                || !getBlockState(x, y + 1, z).isAir() || !getBlockState(x, y - 1, z).isAir()
                || !getBlockState(x, y, z + 1).isAir() || !getBlockState(x, y, z - 1).isAir();
    }


    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return this.sizeY;
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }


    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0F;
        }
        return switch (direction) {
            case DOWN -> 0.5F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
            default -> 1.0F; // UP
        };
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        return lightLayer == LightLayer.SKY ? BAKED_SKY_LIGHT : BAKED_BLOCK_LIGHT;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return null;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return this.tint;
    }
}
