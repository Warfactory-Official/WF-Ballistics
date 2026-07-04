package com.wf.wfballistics.network;

import com.wf.wfballistics.block.entity.LaunchConfig;
import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;


public class SpawnMissilePacket {
    // A generous cap so the debug launcher stays a launcher, not a remote artillery exploit.
    private static final double MAX_USE_DISTANCE_SQR = 64.0 * 64.0;

    private final BlockPos pos;
    private final LaunchConfig config;
    private final boolean launch;

    public SpawnMissilePacket(BlockPos pos, LaunchConfig config, boolean launch) {
        this.pos = pos;
        this.config = config;
        this.launch = launch;
    }

    public static void encode(SpawnMissilePacket m, FriendlyByteBuf b) {
        b.writeBlockPos(m.pos);
        b.writeBoolean(m.launch);
        m.config.write(b);
    }

    public static SpawnMissilePacket decode(FriendlyByteBuf b) {
        BlockPos pos = b.readBlockPos();
        boolean launch = b.readBoolean();
        return new SpawnMissilePacket(pos, LaunchConfig.read(b), launch);
    }

    public static void handle(SpawnMissilePacket m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!player.getAbilities().instabuild && !player.hasPermissions(2)) {
                return;
            }
            ServerLevel level = player.serverLevel();

            // Re-validate: the block must still exist and the player must be next to it.
            if (!level.isLoaded(m.pos)) {
                return;
            }
            BlockEntity be = level.getBlockEntity(m.pos);
            if (!(be instanceof MissileDispenserBlockEntity dispenser)) {
                return;
            }
            if (player.distanceToSqr(Vec3.atCenterOf(m.pos)) > MAX_USE_DISTANCE_SQR) {
                return;
            }

            dispenser.setConfig(m.config);
            if (m.launch) {
                m.config.spawn(level, m.pos, dispenser);
            }
        });
        context.setPacketHandled(true);
    }
}
