package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraChannels;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The channel list behind a handheld receiver, resolved to live feed ids, on its way to the screen. */
public record CameraPanelPacket(int[] feedIds, int selected) implements CustomPacketPayload {

    public static final Type<CameraPanelPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "camera_panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraPanelPacket> STREAM_CODEC =
            StreamCodec.of(CameraPanelPacket::write, CameraPanelPacket::read);

    @Override
    public Type<CameraPanelPacket> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, CameraPanelPacket pkt) {
        buf.writeVarInt(pkt.feedIds.length);
        for (int id : pkt.feedIds) {
            buf.writeVarInt(id);
        }
        buf.writeVarInt(pkt.selected);
    }

    private static CameraPanelPacket read(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), CameraChannels.HARD_MAX);
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = buf.readVarInt();
        }
        return new CameraPanelPacket(ids, buf.readVarInt());
    }
}
