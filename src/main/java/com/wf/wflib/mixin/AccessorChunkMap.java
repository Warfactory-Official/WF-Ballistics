package com.wf.wflib.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;


@Mixin(ChunkMap.class)
public interface AccessorChunkMap {

    @Accessor("entityMap")
    Int2ObjectMap<Object> wfEntityMap();

    @Invoker("updateChunkTracking")
    void wfUpdateChunkTracking(ServerPlayer player);

    @Invoker("getPlayerViewDistance")
    int wfPlayerViewDistance(ServerPlayer player);
}
