package com.wf.wflib.mixin;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PacketEncoder.class)
public interface AccessorPacketEncoder {

    @Accessor("protocolInfo")
    ProtocolInfo<?> wfProtocolInfo();
}
