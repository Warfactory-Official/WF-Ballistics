package com.wf.wfballistics.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WFDynamicLight {

    private record Source(int x, int y, int z, int lum) {
    }

    private static final Map<Integer, Source> BY_ENTITY = new ConcurrentHashMap<>();
    private static final Map<Long, CopyOnWriteArrayList<Source>> BY_SECTION = new ConcurrentHashMap<>();

    private static volatile int active = 0;

    private WFDynamicLight() {
    }

    public static void add(int entityId, BlockPos pos, int lum) {
        if (BY_ENTITY.containsKey(entityId)) {
            return;
        }
        Source source = new Source(pos.getX(), pos.getY(), pos.getZ(), Math.min(15, Math.max(0, lum)));
        if (BY_ENTITY.putIfAbsent(entityId, source) != null) {
            return;
        }
        BY_SECTION.computeIfAbsent(sectionKey(source.x, source.y, source.z), k -> new CopyOnWriteArrayList<>()).add(source);
        active = BY_ENTITY.size();
        markDirty(source);
    }

    public static void remove(int entityId) {
        Source source = BY_ENTITY.remove(entityId);
        if (source == null) {
            return;
        }
        CopyOnWriteArrayList<Source> list = BY_SECTION.get(sectionKey(source.x, source.y, source.z));
        if (list != null) {
            list.remove(source);
        }
        active = BY_ENTITY.size();
        markDirty(source);
    }

    public static void clear() {
        BY_ENTITY.clear();
        BY_SECTION.clear();
        active = 0;
    }

    public static int boost(int packed, BlockPos pos) {
        if (active == 0) {
            return packed;
        }
        int add = compute(pos.getX(), pos.getY(), pos.getZ());
        int block = LightTexture.block(packed);
        if (add <= block) {
            return packed;
        }
        return LightTexture.pack(add, LightTexture.sky(packed));
    }

    private static int compute(int px, int py, int pz) {
        int scx = SectionPos.blockToSectionCoord(px);
        int scy = SectionPos.blockToSectionCoord(py);
        int scz = SectionPos.blockToSectionCoord(pz);
        int best = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Source> list = BY_SECTION.get(SectionPos.asLong(scx + dx, scy + dy, scz + dz));
                    if (list == null) {
                        continue;
                    }
                    for (Source source : list) {
                        int ddx = source.x - px;
                        int ddy = source.y - py;
                        int ddz = source.z - pz;
                        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                        int contribution = (int) (source.lum - dist);
                        if (contribution > best) {
                            best = contribution;
                        }
                    }
                }
            }
        }
        return Math.min(15, best);
    }

    private static void markDirty(Source source) {
        Minecraft mc = Minecraft.getInstance();
        LevelRenderer renderer = mc.levelRenderer;
        if (renderer == null) {
            return;
        }
        int radius = source.lum;
        int minSx = SectionPos.blockToSectionCoord(source.x - radius);
        int maxSx = SectionPos.blockToSectionCoord(source.x + radius);
        int minSy = SectionPos.blockToSectionCoord(source.y - radius);
        int maxSy = SectionPos.blockToSectionCoord(source.y + radius);
        int minSz = SectionPos.blockToSectionCoord(source.z - radius);
        int maxSz = SectionPos.blockToSectionCoord(source.z + radius);
        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sy = minSy; sy <= maxSy; sy++) {
                for (int sz = minSz; sz <= maxSz; sz++) {
                    renderer.setSectionDirtyWithNeighbors(sx, sy, sz);
                }
            }
        }
    }

    private static long sectionKey(int x, int y, int z) {
        return SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z));
    }
}
