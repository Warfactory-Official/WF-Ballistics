package com.wf.wfballistics.entity.glyphid.sim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-dimension store of the glyphids that are records rather than entities, mirroring
 * {@code SimDroneRegistry}. Data only: the decisions live in {@link SimGlyphidManager}.
 *
 * <p><b>Persisted, and it has to be.</b> A swarm three hundred strong is mostly records for most of its
 * march, and a shutdown that dropped them would delete an attack in transit without saying so — the class of
 * failure where the simulation quietly does less than it claims. Vanilla saves entities; this saves what
 * replaced them.
 */
public final class SimGlyphidRegistry extends SavedData {

    public static final String NAME = "wfballistics_sim_glyphids";

    private final List<SimGlyphid> glyphids = new ArrayList<>();
    /**
     * Next identity to hand out, counting down. Saved so a reload cannot reissue an id a live record still
     * holds, which on the client would be two glyphids sharing one slot in the draw list.
     */
    private int nextId = -1;

    public static SimGlyphidRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SimGlyphidRegistry::new, (tag, reg) -> SimGlyphidRegistry.load(tag)),
                NAME);
    }

    public static SimGlyphidRegistry load(CompoundTag tag) {
        SimGlyphidRegistry registry = new SimGlyphidRegistry();
        ListTag list = tag.getList("Glyphids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            registry.glyphids.add(SimGlyphid.load(list.getCompound(i)));
        }
        registry.nextId = tag.contains("nextId") ? tag.getInt("nextId") : -1;
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (SimGlyphid glyphid : glyphids) {
            list.add(glyphid.save());
        }
        tag.put("Glyphids", list);
        tag.putInt("nextId", nextId);
        return tag;
    }

    /**
     * The live list. Mutated in place by the manager's passes, so callers that add or remove while iterating
     * must copy first.
     */
    public List<SimGlyphid> view() {
        return glyphids;
    }

    public int count() {
        return glyphids.size();
    }

    public int claimId() {
        setDirty();
        return nextId--;
    }

    public void add(SimGlyphid glyphid) {
        glyphids.add(glyphid);
        setDirty();
    }

    public void remove(SimGlyphid glyphid) {
        glyphids.remove(glyphid);
        setDirty();
    }
}
