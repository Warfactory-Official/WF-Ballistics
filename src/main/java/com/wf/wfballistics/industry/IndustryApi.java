package com.wf.wfballistics.industry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The public face of industry tracking, and the seam other mods in the stack integrate through.
 *
 * <p>It exists because the dependency runs the wrong way for the obvious approach. wfcore already depends
 * on this mod, so this mod cannot depend on wfcore to ask what a GregTech machine is worth — that is a
 * cycle. Instead the model lives here and wfcore <em>reports into</em> it, which also puts the judgement in
 * the mod that actually knows GregTech. With no reporter present the config whitelist in
 * {@link IndustryValues} is the fallback, so this works standalone.
 */
public final class IndustryApi {

    private IndustryApi() {
    }

    /**
     * Declare what a block is worth as a provocation, overriding the config whitelist.
     *
     * <p>Call during setup, or whenever the reporting mod's own registries are ready. Values are additive
     * per placed block; a value of 0 or less removes the block from tracking.
     */
    public static void registerValue(ResourceLocation blockId, int value) {
        IndustryValues.put(blockId, value);
    }

    /**
     * @return accumulated provocation in the region cell containing this position.
     */
    public static int pressureAt(ServerLevel level, int blockX, int blockZ) {
        return IndustryRegistry.get(level).pressureAt(blockX, blockZ);
    }

    /**
     * @return every base found by the last scan, worst offender first. Empty until the first scan lands.
     */
    public static List<IndustryCluster> clusters(ServerLevel level) {
        return IndustryClusters.clusters(level);
    }

    /**
     * @return the base nearest a position, or null if none is known yet.
     */
    public static @Nullable IndustryCluster nearestCluster(ServerLevel level, double x, double z) {
        return IndustryClusters.nearest(level, x, z);
    }

    /**
     * @return total tracked provocation in this level.
     */
    public static int totalPressure(ServerLevel level) {
        return IndustryRegistry.get(level).totalValue();
    }
}
