package com.wf.wflib.ammo.client;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;

/** Flat colours as 4 px cells, 16 per row; UV = cell centre. Unlisted colour => {@link IllegalStateException}. */
public final class AmmoPalette implements AmmoMeshes.Uv {
    static final int CELL = 4;
    static final int COLUMNS = 16;
    private final Int2IntLinkedOpenHashMap index = new Int2IntLinkedOpenHashMap();
    private final int rows;

    public AmmoPalette(Collection<Integer> rgbs) {
        rgbs.forEach(c -> index.putIfAbsent(c & 0xFFFFFF, index.size()));
        this.rows = Math.max(1, (index.size() + COLUMNS - 1) / COLUMNS);
    }

    private int cell(int rgb) {
        int i = index.getOrDefault(rgb & 0xFFFFFF, -1);
        if (i < 0) {
            throw new IllegalStateException(String.format("colour #%06x not in the ammo palette", rgb));
        }
        return i;
    }

    @Override
    public float u(int rgb) {
        return (cell(rgb) % COLUMNS + 0.5f) / COLUMNS;
    }

    @Override
    public float v(int rgb) {
        return (cell(rgb) / COLUMNS + 0.5f) / rows;
    }

    public int size() {
        return index.size();
    }

    /** Render thread. */
    void upload(ResourceLocation id) {
        NativeImage img = new NativeImage(COLUMNS * CELL, rows * CELL, false);
        index.forEach((rgb, i) -> {
            int abgr = 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
            int x0 = i % COLUMNS * CELL, y0 = i / COLUMNS * CELL;
            for (int y = 0; y < CELL; y++) {
                for (int x = 0; x < CELL; x++) {
                    img.setPixelRGBA(x0 + x, y0 + y, abgr);
                }
            }
        });
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
    }
}
