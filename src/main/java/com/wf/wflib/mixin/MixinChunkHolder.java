package com.wf.wflib.mixin;

import com.wf.wflib.stream.SharedChunkPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every change vanilla broadcasts to a sent chunk drops its shared packet. */
@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder {

    @Shadow
    @Final
    private LevelHeightAccessor levelHeightAccessor;

    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void wfDropSharedOnBlock(BlockPos pos, CallbackInfo ci) {
        SharedChunkPackets.invalidate(this.levelHeightAccessor, ((ChunkHolder) (Object) this).getPos().toLong());
    }

    @Inject(method = "sectionLightChanged", at = @At("HEAD"))
    private void wfDropSharedOnLight(LightLayer layer, int sectionY, CallbackInfo ci) {
        SharedChunkPackets.invalidate(this.levelHeightAccessor, ((ChunkHolder) (Object) this).getPos().toLong());
    }
}
