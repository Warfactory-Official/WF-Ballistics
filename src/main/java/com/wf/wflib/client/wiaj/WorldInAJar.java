package com.wf.wflib.client.wiaj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

public class WorldInAJar implements BlockAndTintGetter {

    private static final int BAKED_SKY_LIGHT = 15;
    private static final int BAKED_BLOCK_LIGHT = 0;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    private final BlockState[][][] blocks;
    private final BlockState air = Blocks.AIR.defaultBlockState();
    private int tint = 0xFFFFFF;

    public WorldInAJar(int x, int y, int z) {
        this.sizeX = x;
        this.sizeY = y;
        this.sizeZ = z;
        this.blocks = new BlockState[x][y][z];
    }

    /** Whole-jar tint; {@link #getBlockTint} ignores its colorResolver and returns this. */
    public void setTint(int rgb) {
        this.tint = rgb;
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
