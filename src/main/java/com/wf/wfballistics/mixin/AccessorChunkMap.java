package com.wf.wfballistics.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the tracked-entity table so a camera feed can force a visibility re-scan for one observer. */
@Mixin(ChunkMap.class)
public interface AccessorChunkMap {

    @Accessor("entityMap")
    Int2ObjectMap<Object> wfCamEntityMap();
}
