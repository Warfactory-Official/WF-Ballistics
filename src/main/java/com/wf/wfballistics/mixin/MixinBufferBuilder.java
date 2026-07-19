package com.wf.wfballistics.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.wf.wfballistics.client.render.DirectBufferAccess;
import com.wf.wfballistics.unsafe.UnsafeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.Buffer;
import java.nio.ByteBuffer;

@Mixin(BufferBuilder.class)
public abstract class MixinBufferBuilder implements DirectBufferAccess {

    @Unique
    private static final long HBM$BUF_ADDR_OFFSET = UnsafeHolder.fieldOffset(Buffer.class, "address");

    @Shadow
    private ByteBuffer buffer;
    @Shadow
    private int nextElementByte;
    @Shadow
    private int vertices;
    @Unique
    private long hbm$address;

    @Shadow
    protected abstract void ensureCapacity(int pIncreaseAmount);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void hbm$afterInit(int pCapacity, CallbackInfo ci) {
        hbm$updateAddress();
    }

    @Inject(method = "ensureCapacity", at = @At("RETURN"))
    private void hbm$afterGrow(int pIncreaseAmount, CallbackInfo ci) {
        hbm$updateAddress();
    }

    @Unique
    private void hbm$updateAddress() {
        ByteBuffer buf = this.buffer;
        hbm$address = (buf != null && buf.isDirect())
                ? UnsafeHolder.U.getLong(buf, HBM$BUF_ADDR_OFFSET)
                : 0L;
    }

    @Override
    public long hbm$bufferAddress() {
        return hbm$address;
    }

    @Override
    public int hbm$nextElementByte() {
        return nextElementByte;
    }

    @Override
    public void hbm$setNextElementByte(int value) {
        nextElementByte = value;
    }

    @Override
    public int hbm$vertices() {
        return vertices;
    }

    @Override
    public void hbm$setVertices(int value) {
        vertices = value;
    }

    @Override
    public void hbm$ensureCapacity(int bytes) {
        ensureCapacity(bytes);
    }
}
