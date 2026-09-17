package com.wf.wfballistics.client.scope;

import com.wf.wfballistics.recon.scope.ScopeFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** The latest frame per network, and the one texture rasterised from it. */
public final class ScopeCache {

    /** Ticks a net may go without a frame before its texture is released. */
    private static final long STALE_TICKS = 200;

    private static final Map<Long, ScopeFrame> FRAMES = new HashMap<>();
    private static final Map<Long, ScopeRaster> RASTERS = new HashMap<>();
    private static long lastPrune;

    private ScopeCache() {
    }

    public static void accept(ScopeFrame frame) {
        FRAMES.put(frame.netId(), frame);
    }

    @Nullable
    public static ScopeFrame frame(long netId) {
        return FRAMES.get(netId);
    }

    /**
     * @return the texture for this network's current frame, rasterising it if the frame has moved on, or null
     *      if nothing has been received for this net yet.
     */
    @Nullable
    public static ResourceLocation texture(long netId) {
        ScopeFrame frame = FRAMES.get(netId);
        if (frame == null) {
            return null;
        }
        ScopeRaster raster = RASTERS.computeIfAbsent(netId, ScopeRaster::new);
        raster.ensureDrawn(frame);
        return raster.texture();
    }

    /** Drop textures for networks that have gone quiet. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        long now = mc.level.getGameTime();
        if (now - lastPrune < 40) {
            return;
        }
        lastPrune = now;
        for (Iterator<Map.Entry<Long, ScopeFrame>> it = FRAMES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, ScopeFrame> entry = it.next();
            if (now - entry.getValue().gameTime() > STALE_TICKS) {
                ScopeRaster raster = RASTERS.remove(entry.getKey());
                if (raster != null) {
                    raster.close();
                }
                it.remove();
            }
        }
    }

    /** Release everything, on disconnect. */
    public static void clear() {
        for (ScopeRaster raster : RASTERS.values()) {
            raster.close();
        }
        RASTERS.clear();
        FRAMES.clear();
    }
}
