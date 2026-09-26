package com.wf.wflib.mixin;

import com.wf.wflib.stream.StreamDebug;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium drops a chunk from its graph here, keyed by position, whoever calls; log the caller. */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevelStreamLog {

    @Unique
    private static String wfcaller() {
        return StackWalker.getInstance().walk(frames -> frames
                .skip(2)
                .filter(frame -> !frame.getClassName().equals(ClientLevel.class.getName()))
                .findFirst()
                .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                        + "." + frame.getMethodName())
                .orElse("?"));
    }

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("HEAD"))
    private void wftrackUnload(LevelChunk chunk, CallbackInfo ci) {
        if (!StreamDebug.on(StreamDebug.Category.CACHE)) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        StreamDebug.log(StreamDebug.Category.CACHE, "unload {} {} from {}",
                pos.x, pos.z, wfcaller());
    }

    @Inject(method = "onChunkLoaded(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"))
    private void wftrackLoad(ChunkPos pos, CallbackInfo ci) {
        if (!StreamDebug.on(StreamDebug.Category.CACHE)) {
            return;
        }
        StreamDebug.log(StreamDebug.Category.CACHE, "load {} {} from {}",
                pos.x, pos.z, wfcaller());
    }

}
