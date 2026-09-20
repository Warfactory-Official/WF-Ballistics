package com.wf.wflib.client.survey;

import com.mojang.blaze3d.platform.NativeImage;
import com.wf.wflib.network.SurveyRequestPacket;
import com.wf.wflib.network.SurveyTilePacket;
import com.wf.wflib.orbital.survey.SurveyTile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SurveyCache {

    public enum Mode { TERRAIN, PANCHROMATIC, ELEVATION, CHANGE }

    private static final Map<Long, Held> HELD = new HashMap<>();
    private static final Set<Long> ASKED = new HashSet<>();
    private static long netId;

    private SurveyCache() {
    }

    public static void bind(long id) {
        if (id != netId) {
            clear();
            netId = id;
        }
    }

    public static long net() {
        return netId;
    }

    public static void accept(SurveyTilePacket pkt) {
        long key = SurveyTile.key(pkt.tileX(), pkt.tileZ());
        Held held = HELD.get(key);
        if (held != null) {
            held.release();
        }
        HELD.put(key, new Held(pkt));
        ASKED.remove(key);
    }

    @Nullable
    public static Held tile(int tileX, int tileZ, Mode mode) {
        long key = SurveyTile.key(tileX, tileZ);
        Held held = HELD.get(key);
        if (held == null) {
            if (ASKED.add(key)) {
                PacketDistributor.sendToServer(new SurveyRequestPacket(netId, tileX, tileZ));
            }
            return null;
        }
        held.ensure(mode);
        return held;
    }

    public static void clear() {
        for (Held held : HELD.values()) {
            held.release();
        }
        HELD.clear();
        ASKED.clear();
    }

    public static final class Held {
        private final SurveyTilePacket data;
        private DynamicTexture texture;
        private ResourceLocation id;
        private Mode built;

        private Held(SurveyTilePacket data) {
            this.data = data;
        }

        public long stamped() {
            return data.stamped();
        }

        public int imaged() {
            return data.imaged();
        }

        @Nullable
        public ResourceLocation id() {
            return id;
        }

        private void ensure(Mode mode) {
            if (built == mode && id != null) {
                return;
            }
            release();
            NativeImage image = new NativeImage(SurveyTile.PIXELS, SurveyTile.PIXELS, true);
            for (int z = 0; z < SurveyTile.PIXELS; z++) {
                for (int x = 0; x < SurveyTile.PIXELS; x++) {
                    image.setPixelRGBA(x, z, abgr(x, z, mode));
                }
            }
            texture = new DynamicTexture(image);
            id = Minecraft.getInstance().getTextureManager()
                    .register("wf_survey_" + data.tileX() + "_" + data.tileZ(), texture);
            built = mode;
        }

        private int abgr(int x, int z, Mode mode) {
            int i = z * SurveyTile.PIXELS + x;
            int packed = data.material()[i] & 0xFF;
            if (packed == 0) {
                return 0x00000000;
            }
            int rgb = MapColor.byId(packed >> 2).calculateRGBColor(
                    MapColor.Brightness.byId(packed & 3));
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            switch (mode) {
                case PANCHROMATIC -> {
                    int lum = Mth.clamp((r * 30 + g * 59 + b * 11) / 100, 0, 255);
                    r = lum;
                    g = lum;
                    b = lum;
                }
                case ELEVATION -> {
                    int h = data.height()[i] & 0xFF;
                    r = h;
                    g = Mth.clamp(255 - Math.abs(h - 128) * 2, 0, 255);
                    b = 255 - h;
                }
                case CHANGE -> {
                    if (data.changed()[i] != 0) {
                        r = 255;
                        g = 40;
                        b = 40;
                    } else {
                        int lum = Mth.clamp((r * 30 + g * 59 + b * 11) / 300, 0, 255);
                        r = lum;
                        g = lum;
                        b = lum;
                    }
                }
                default -> {
                }
            }
            return 0xFF000000 | (b << 16) | (g << 8) | r;
        }

        private void release() {
            if (id != null) {
                Minecraft.getInstance().getTextureManager().release(id);
                id = null;
            }
            if (texture != null) {
                texture.close();
                texture = null;
            }
            built = null;
        }
    }
}
