package com.wf.wflib.sim;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-level store of every sim kind's records. Unknown kinds in a save (addon removed) are dropped. */
public final class SimWorld extends SavedData {

    public static final String NAME = "wflib_sims";

    private final Map<ResourceLocation, SimTier<?>> tiers = new LinkedHashMap<>();
    private ResourceKey<Level> dimension;

    private SimWorld() {
        for (SimKind<?> kind : SimKinds.all()) {
            this.tiers.put(kind.id(), new SimTier<>(kind, this));
        }
    }

    public static SimWorld get(ServerLevel level) {
        SimWorld world = level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SimWorld::new, (tag, registries) -> load(tag)), NAME);
        world.dimension = level.dimension();
        return world;
    }

    ResourceKey<Level> dimension() {
        return this.dimension;
    }

    private static SimWorld load(CompoundTag tag) {
        SimWorld world = new SimWorld();
        for (SimTier<?> tier : world.tiers.values()) {
            String key = tier.kind.id().toString();
            if (tier.kind.persistent() && tag.contains(key)) {
                tier.load(tag.getCompound(key));
            }
        }
        return world;
    }

    @SuppressWarnings("unchecked")
    public <R> SimTier<R> tier(SimKind<R> kind) {
        return (SimTier<R>) this.tiers.get(kind.id());
    }

    Collection<SimTier<?>> tiers() {
        return this.tiers.values();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (SimTier<?> tier : this.tiers.values()) {
            if (tier.kind.persistent()) {
                tag.put(tier.kind.id().toString(), tier.save());
            }
        }
        return tag;
    }
}
