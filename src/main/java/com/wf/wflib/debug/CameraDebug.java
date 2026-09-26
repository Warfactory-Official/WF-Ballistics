package com.wf.wflib.debug;

import com.wf.wflib.block.CameraMonitorBlock;
import com.wf.wflib.block.ModBlocks;
import com.wf.wflib.drone.DroneBattery;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.cam.CameraChunkStream;
import com.wf.wflib.drone.cam.CameraNet;
import com.wf.wflib.drone.cam.CameraSpec;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/** Commands for the drone camera: what it costs, and one command that builds a working example. */
public final class CameraDebug {

    private CameraDebug() {
    }

    public static int stats(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        long encodes = CameraNet.encodeCount();
        long deliveries = CameraNet.deliveryCount();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "camera: %d live feed(s), %d monitor(s) in %s",
                        CameraNet.feedCount(level), CameraNet.monitorCount(level),
                        level.dimension().location()))
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %d encode(s) -> %d delivery(s)  = %.2f observers per encode",
                encodes, deliveries, encodes == 0 ? 0.0 : (double) deliveries / encodes)), false);
        source.sendSuccess(() -> Component.literal(
                        "  a ratio above 1.00 is the multi-observer saving; it is exactly what a per-recipient "
                                + "encode would have cost instead")
                .withStyle(ChatFormatting.DARK_GRAY), false);

        // The same claim about the terrain, where a payload is kilobytes rather than tens of bytes.
        long chunkEncodes = CameraChunkStream.chunkEncodeCount();
        long chunkDeliveries = CameraChunkStream.chunkDeliveryCount();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  terrain: %d chunk encode(s) -> %d delivery(s)  = %.2f observers per encode",
                chunkEncodes, chunkDeliveries,
                chunkEncodes == 0 ? 0.0 : (double) chunkDeliveries / chunkEncodes)), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %d chunk(s) streamed to %d viewer(s)",
                com.wf.wflib.stream.ChunkStreams.claimCount(), CameraChunkStream.streamingViewers())), false);
        return 1;
    }

    public static int reset(CommandSourceStack source) {
        CameraNet.resetCounters();
        source.sendSuccess(() -> Component.literal("camera counters reset"), false);
        return 1;
    }

    /** Set the camera fit on every drone within 128 blocks. */
    public static int fit(CommandSourceStack source, String which) {
        ServerLevel level = source.getLevel();
        CameraSpec spec = switch (which) {
            case "recon" -> CameraSpec.RECON;
            case "standard" -> CameraSpec.STANDARD;
            default -> null;
        };
        int count = 0;
        for (DroneEntity drone : level.getEntitiesOfClass(DroneEntity.class,
                new AABB(BlockPos.containing(source.getPosition())).inflate(128.0))) {
            drone.setCameraSpec(spec);
            count++;
        }
        int fitted = count;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "fitted %s to %d drone(s)", which, fitted)).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * Build the smallest thing that demonstrates the feature: one monitor, and one drone hovering in front of the
     * player with a camera on it.
     */
    public static int demo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        BlockPos base = BlockPos.containing(at);

        Direction facing = Direction.WEST;
        Direction right = facing.getCounterClockWise();
        BlockPos monitorPos = base.offset(2, 0, -1);
        BlockState monitor = ModBlocks.CAMERA_MONITOR.get().defaultBlockState()
                .setValue(CameraMonitorBlock.FACING, facing);
        for (int x = 0; x < CameraMonitorBlock.WIDTH; x++) {
            BlockPos column = monitorPos.relative(right, x);
            level.setBlockAndUpdate(column.below(), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            for (int y = 0; y < CameraMonitorBlock.HEIGHT; y++) {
                level.setBlockAndUpdate(column.above(y), monitor
                        .setValue(CameraMonitorBlock.PART_X, x)
                        .setValue(CameraMonitorBlock.PART_Y, y));
            }
        }

        // The subject: something worth pointing a camera at, so the feed is not a picture of flat ground.
        for (int i = 0; i < 6; i++) {
            level.setBlockAndUpdate(base.offset(14, i, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(base.offset(14, i, -3), Blocks.LAPIS_BLOCK.defaultBlockState());
        }
        level.setBlockAndUpdate(base.offset(14, 0, 0), Blocks.MAGMA_BLOCK.defaultBlockState());

        DroneEntity drone = new DroneEntity(level, at.add(0.0, 12.0, 0.0));
        drone.setCameraSpec(CameraSpec.RECON);
        drone.battery().setCapacity(DroneBattery.DEFAULT_CAPACITY * 50.0);
        drone.battery().setCharge(DroneBattery.DEFAULT_CAPACITY * 50.0);
        drone.flight().setState(DroneState.SURVEIL);
        drone.route().setDestination(at.add(0.0, 12.0, 0.0));
        level.addFreshEntity(drone);

        int feedId = drone.getId();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "camera demo: drone CAM-%04X at +12, monitor two blocks east",
                        feedId & 0xFFFF))
                .withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal(
                        "  use the monitor to open the feed; sneak-use to tune to another drone")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal(
                        "  in the feed: drag to slew, scroll to zoom, M cycles optical/thermal/lowlight")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /**
     * Every drone in range and what camera it is carrying, so "why is that monitor blank" has an answer that is not
     * guesswork: usually either no camera fitted or a flat battery.
     */
    public static int list(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        List<DroneEntity> drones = level.getEntitiesOfClass(DroneEntity.class,
                new AABB(BlockPos.containing(source.getPosition())).inflate(256.0));
        if (drones.isEmpty()) {
            source.sendSuccess(() -> Component.literal("no drones within 256 blocks")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (DroneEntity drone : drones) {
            CameraSpec spec = drone.cameraSpec();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  CAM-%04X  %s  %s  batt %3.0f%%  %.0f blk from you",
                    drone.getId() & 0xFFFF,
                    spec == null ? "no camera" : spec == CameraSpec.RECON ? "recon" : "standard",
                    drone.flight().getDroneState().name(),
                    drone.battery().percent() * 100.0f,
                    Math.sqrt(drone.distanceToSqr(source.getPosition())))), false);
        }
        return drones.size();
    }
}
