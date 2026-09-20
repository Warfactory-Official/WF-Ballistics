package com.wf.wflib.probe;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;

/**
 * The registry route into the probe: four capabilities, so anything at all can be given a panel without
 * implementing anything or being known to this mod.
 */
public final class ProbeCapabilities {

    /** What a block says about itself in a panel. Asked on the client. */
    public static final BlockCapability<ProbeProvider.Blocks, Void> BLOCK =
            BlockCapability.createVoid(id("block"), ProbeProvider.Blocks.class);

    /** What an entity says about itself in a panel. Asked on the client. */
    public static final EntityCapability<ProbeProvider.Entities, Void> ENTITY =
            EntityCapability.createVoid(id("entity"), ProbeProvider.Entities.class);

    /** What a block sends from the server for its panel. Asked on the server, on demand. */
    public static final BlockCapability<ProbeDataProvider.Blocks, Void> BLOCK_DATA =
            BlockCapability.createVoid(id("block_data"), ProbeDataProvider.Blocks.class);

    /** What an entity sends from the server for its panel. */
    public static final EntityCapability<ProbeDataProvider.Entities, Void> ENTITY_DATA =
            EntityCapability.createVoid(id("entity_data"), ProbeDataProvider.Entities.class);

    private ProbeCapabilities() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "probe/" + path);
    }
}
