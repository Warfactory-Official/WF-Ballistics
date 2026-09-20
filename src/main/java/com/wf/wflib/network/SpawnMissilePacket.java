package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.block.entity.LaunchConfig;
import com.wf.wflib.block.entity.MissileDispenserBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public record SpawnMissilePacket(BlockPos pos, LaunchConfig config, boolean launch) implements CustomPacketPayload {
    // A generous cap so the debug launcher stays a launcher, not a remote artillery exploit.
    private static final double MAX_USE_DISTANCE_SQR = 64.0 * 64.0;

    public static final Type<SpawnMissilePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "spawn_missile"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpawnMissilePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, m) -> {
                        buf.writeBlockPos(m.pos);
                        buf.writeBoolean(m.launch);
                        m.config.write(buf);
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        boolean launch = buf.readBoolean();
                        return new SpawnMissilePacket(pos, LaunchConfig.read(buf), launch);
                    });

    @Override
    public Type<SpawnMissilePacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.getAbilities().instabuild && !player.hasPermissions(2)) {
                return;
            }
            ServerLevel level = player.serverLevel();

            // Re-validate: the block must still exist and the player must be next to it.
            if (!level.isLoaded(pos)) {
                return;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof MissileDispenserBlockEntity dispenser)) {
                return;
            }
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_USE_DISTANCE_SQR) {
                return;
            }

            dispenser.setConfig(config);
            if (launch) {
                config.spawn(level, pos, dispenser);
            }
        });
    }
}
