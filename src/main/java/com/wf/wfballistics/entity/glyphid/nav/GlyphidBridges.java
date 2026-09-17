package com.wf.wfballistics.entity.glyphid.nav;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GlyphidBridges {

    /** Bridges a level may have at once. A swarm converges on one route, so it wants very few. */
    private static final int MAX_BRIDGES = 4;
    /** How near the bank a glyphid has to be before it is asked to anchor. */
    private static final double RECRUIT_RADIUS = 4.0;
    /** How far above or below the deck a recruit may be. Stops one on a ledge claiming a slot it cannot reach. */
    private static final double RECRUIT_HEIGHT = 3.0;

    private static final Map<ResourceKey<Level>, List<GlyphidBridge>> BY_LEVEL = new HashMap<>();
    /** Bridges standing across every level. */
    private static int live;

    private GlyphidBridges() {
    }

    /** Take a site the flood turned up. */
    static void propose(ServerLevel level, int bankX, int bankZ, int deckY, int landingX, int landingZ,
                        GlyphidBridge.Slot[] slots) {
        List<GlyphidBridge> bridges = BY_LEVEL.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
        if (bridges.size() >= MAX_BRIDGES) {
            return;
        }
        for (GlyphidBridge existing : bridges) {
            if (existing.bankX == bankX && existing.bankZ == bankZ) {
                return;
            }
            for (GlyphidBridge.Slot slot : slots) {
                if (existing.spans(slot.x, slot.z)) {
                    return;
                }
            }
        }
        bridges.add(new GlyphidBridge(bankX, bankZ, deckY, landingX, landingZ, slots, level.getGameTime()));
        live++;
    }

    public static boolean recruit(ServerLevel level, EntityGlyphid glyphid) {
        if (live == 0 || !SwarmBench.bridges) {
            return false;
        }
        List<GlyphidBridge> bridges = BY_LEVEL.get(level.dimension());
        if (bridges == null || bridges.isEmpty()) {
            return false;
        }
        if (glyphid.getCurrentTask() != GlyphidTasks.TASK_FOLLOW || glyphid.getTarget() != null
                || glyphid.isAirborne() || glyphid.getBbHeight() > GlyphidBridge.MAX_ANCHOR_HEIGHT) {
            return false;
        }
        long now = level.getGameTime();
        for (GlyphidBridge bridge : bridges) {
            if (Math.abs(glyphid.getY() - bridge.deckY) > RECRUIT_HEIGHT) {
                continue;
            }
            if (bridge.distanceToEndSq(glyphid.getX(), glyphid.getZ())
                    > RECRUIT_RADIUS * RECRUIT_RADIUS) {
                continue;
            }
            bridge.orient(glyphid.getX(), glyphid.getZ());
            GlyphidBridge.Slot slot = bridge.offer(now);
            if (slot == null) {
                continue;
            }
            bridge.claim(slot, glyphid.getId(), now);
            glyphid.takeBridgeSlot(bridge, slot);
            return true;
        }
        return false;
    }

    /** Note that an anchor is in place, and re-flood the fields if that finished the crossing. */
    public static void seat(ServerLevel level, GlyphidBridge bridge, GlyphidBridge.Slot slot, AABB box) {
        if (bridge.seat(slot, box, level.getGameTime())) {
            GlyphidFlowFields.invalidate(level, new BlockPos(slot.x, bridge.deckY, slot.z));
        }
    }

    /** Let go of a slot this glyphid was holding, along with every slot beyond it. */
    public static void release(ServerLevel level, EntityGlyphid glyphid) {
        GlyphidBridge bridge = glyphid.bridge();
        if (bridge == null) {
            return;
        }
        boolean wasComplete = bridge.complete();
        int[] freed = bridge.release(glyphid.getId());
        for (int id : freed) {
            if (level.getEntity(id) instanceof EntityGlyphid other) {
                other.dropBridgeSlot();
            }
        }
        glyphid.dropBridgeSlot();
        if (wasComplete) {
            // The deck is stamped into every field covering it; those have to forget it.
            GlyphidFlowFields.invalidate(level, new BlockPos(bridge.bankX, bridge.deckY, bridge.bankZ));
        }
    }

    public static List<VoxelShape> deckShapes(Level level, AABB box) {
        if (live == 0 || !(level instanceof ServerLevel server)) {
            return List.of();
        }
        List<GlyphidBridge> bridges = BY_LEVEL.get(server.dimension());
        if (bridges == null || bridges.isEmpty()) {
            return List.of();
        }
        List<VoxelShape> shapes = null;
        for (GlyphidBridge bridge : bridges) {
            if (!bridge.bounds().intersects(box)) {
                continue;
            }
            for (GlyphidBridge.Slot slot : bridge.slots()) {
                AABB anchor = slot.box;
                if (anchor == null) {
                    continue;
                }
                if (shapes == null) {
                    shapes = new ArrayList<>(8);
                }
                shapes.add(Shapes.create(anchor));
            }
            if (shapes != null) {
                bridge.used(server.getGameTime());
            }
        }
        return shapes == null ? List.of() : shapes;
    }

    /** Write every finished deck into a field being built, as ordinary floor. */
    static void stampInto(ServerLevel level, GlyphidFlowField field) {
        if (live == 0) {
            return;
        }
        List<GlyphidBridge> bridges = BY_LEVEL.get(level.dimension());
        if (bridges == null) {
            return;
        }
        for (GlyphidBridge bridge : bridges) {
            if (!bridge.complete()) {
                continue;
            }
            for (GlyphidBridge.Slot slot : bridge.slots()) {
                field.stampDeck(slot.x, slot.z, bridge.deckY);
            }
        }
    }

    /** Dissolve the bridges nobody is using. Cheap enough to run every tick: a level holds at most four. */
    public static void tick(ServerLevel level) {
        if (live == 0) {
            return;
        }
        List<GlyphidBridge> bridges = BY_LEVEL.get(level.dimension());
        if (bridges == null || bridges.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (int i = bridges.size() - 1; i >= 0; i--) {
            GlyphidBridge bridge = bridges.get(i);
            if (!bridge.expired(now) && SwarmBench.bridges) {
                continue;
            }
            boolean wasComplete = bridge.complete();
            for (int id : bridge.releaseAll()) {
                if (level.getEntity(id) instanceof EntityGlyphid anchor) {
                    anchor.dropBridgeSlot();
                }
            }
            bridges.remove(i);
            live--;
            if (wasComplete) {
                GlyphidFlowFields.invalidate(level, new BlockPos(bridge.bankX, bridge.deckY, bridge.bankZ));
            }
        }
    }

    /** @return one line per bridge, for {@code swarmbench bridges}. */
    public static List<String> report(ServerLevel level) {
        List<GlyphidBridge> bridges = BY_LEVEL.get(level.dimension());
        List<String> lines = new ArrayList<>();
        if (bridges == null || bridges.isEmpty()) {
            lines.add("No bridges." + (SwarmBench.bridges ? "" : " (switched off)"));
            return lines;
        }
        for (GlyphidBridge bridge : bridges) {
            lines.add(String.format(Locale.ROOT, "  (%d, %d, %d) -> (%d, %d): %d/%d anchored%s",
                    bridge.bankX, bridge.deckY, bridge.bankZ, bridge.landingX, bridge.landingZ,
                    bridge.seatedCount(), bridge.slots().length, bridge.complete() ? ", carrying" : ""));
        }
        return lines;
    }

    public static @Nullable List<GlyphidBridge> of(ServerLevel level) {
        return BY_LEVEL.get(level.dimension());
    }

    public static void clear() {
        BY_LEVEL.clear();
        live = 0;
    }
}
