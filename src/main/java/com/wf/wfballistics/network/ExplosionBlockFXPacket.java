package com.wf.wfballistics.network;

import com.wf.wfballistics.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The data the client needs to reproduce the standard explosion's visuals: the epicentre, the radius, and
 * the positions of the blocks that were broken (so debris and smoke can radiate outward from each one).
 */
public record ExplosionBlockFXPacket(double x, double y, double z, float size, List<BlockPos> blocks) {

    public static void encode(ExplosionBlockFXPacket pkt, FriendlyByteBuf buf) {
        buf.writeDouble(pkt.x);
        buf.writeDouble(pkt.y);
        buf.writeDouble(pkt.z);
        buf.writeFloat(pkt.size);

        int cx = (int) Math.floor(pkt.x);
        int cy = (int) Math.floor(pkt.y);
        int cz = (int) Math.floor(pkt.z);
        buf.writeVarInt(pkt.blocks.size());
        for (BlockPos pos : pkt.blocks) {
            buf.writeVarInt(zigzag(pos.getX() - cx));
            buf.writeVarInt(zigzag(pos.getY() - cy));
            buf.writeVarInt(zigzag(pos.getZ() - cz));
        }
    }

    public static ExplosionBlockFXPacket decode(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float size = buf.readFloat();

        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        int count = buf.readVarInt();
        List<BlockPos> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int dx = unzigzag(buf.readVarInt());
            int dy = unzigzag(buf.readVarInt());
            int dz = unzigzag(buf.readVarInt());
            blocks.add(new BlockPos(cx + dx, cy + dy, cz + dz));
        }
        return new ExplosionBlockFXPacket(x, y, z, size, blocks);
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    public static void handle(ExplosionBlockFXPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleBlockFX(pkt)));
        ctx.get().setPacketHandled(true);
    }
}
