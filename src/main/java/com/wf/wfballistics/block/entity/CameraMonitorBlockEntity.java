package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.drone.DroneRecall;
import com.wf.wfballistics.drone.DroneTracker;
import com.wf.wfballistics.drone.cam.CameraChannels;
import com.wf.wfballistics.drone.cam.CameraNet;
import com.wf.wfballistics.drone.cam.FeedTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** A monitor showing one of several bound cameras. */
public class CameraMonitorBlockEntity extends BlockEntity {

    /** How far a monitor will look for a drone to tune to when nothing is bound. */
    public static final double BIND_RANGE = 1600.0;
    /** Ticks between re-resolving. Cheap, and a monitor one second behind is invisible. */
    private static final int RESOLVE_INTERVAL = 20;

    private CameraChannels channels = CameraChannels.EMPTY;
    /** The auto-tune fallback's pick. Only meaningful while nothing is bound. */
    @Nullable
    private UUID bound;

    private int feedId;
    /** One id per channel, for the strip the screen draws. Zero where a camera is unreachable. */
    private int[] feedIds = new int[0];
    /** Synced for display; the server reads it off {@link #channels} instead. */
    private int selectedChannel;
    private int resolveIn;

    public CameraMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CAMERA_MONITOR.get(), pos, state);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        if (--this.resolveIn > 0) {
            CameraNet.registerMonitor(sl, this.worldPosition, this.feedId, this.feedIds);
            return;
        }
        refresh(sl);
    }

    /** Re-resolve every binding to a live feed id, sync if anything moved, and renew the registration. */
    private void refresh(ServerLevel level) {
        this.resolveIn = RESOLVE_INTERVAL;
        int resolved;
        int[] all;
        if (this.channels.isEmpty()) {
            DroneEntity drone = resolveDrone(level);
            this.bound = drone == null ? null : drone.getUUID();
            resolved = drone == null ? 0 : drone.getId();
            all = resolved == 0 ? new int[0] : new int[]{resolved};
        } else {
            all = this.channels.resolveAll(level);
            resolved = this.channels.selectedFeed(level);
            if (resolved == 0 && recall(level)) {
                all = this.channels.resolveAll(level);
                resolved = this.channels.selectedFeed(level);
            }
        }
        if (resolved != this.feedId || this.selectedChannel != this.channels.selected()
                || !Arrays.equals(all, this.feedIds)) {
            this.feedId = resolved;
            this.feedIds = all;
            this.selectedChannel = this.channels.selected();
            this.setChanged();
            level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
        CameraNet.registerMonitor(level, this.worldPosition, this.feedId, this.feedIds);
    }

    /**
     * The selected channel is a drone that is not in the world.
     *
     * @return true if it is worth resolving the channel again immediately.
     */
    private boolean recall(ServerLevel level) {
        FeedTarget target = this.channels.current();
        if (target == null || target.drone().isEmpty()) {
            return false;
        }
        if (!level.hasNearbyAlivePlayer(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 0.5,
                this.worldPosition.getZ() + 0.5, CameraNet.MONITOR_AUDIENCE)) {
            return false;
        }
        return DroneRecall.recall(level, target.drone().get());
    }

    /** Show the next channel, or (with nothing bound) tune to the next camera drone in range. */
    public void cycle(ServerLevel level) {
        if (this.channels.isEmpty()) {
            List<DroneEntity> candidates = candidates(level);
            if (candidates.isEmpty()) {
                this.bound = null;
            } else {
                int at = -1;
                for (int i = 0; i < candidates.size(); i++) {
                    if (candidates.get(i).getUUID().equals(this.bound)) {
                        at = i;
                        break;
                    }
                }
                this.bound = candidates.get((at + 1) % candidates.size()).getUUID();
            }
        } else {
            this.channels = this.channels.cycle(1);
        }
        this.setChanged();
        refresh(level);
    }

    public void setChannels(CameraChannels next, ServerLevel level) {
        this.channels = next;
        this.setChanged();
        refresh(level);
    }

    public CameraChannels channels() {
        return this.channels;
    }

    public int feedId() {
        return this.feedId;
    }

    public int[] feedIds() {
        return this.feedIds;
    }

    public int selectedChannel() {
        return this.selectedChannel;
    }

    @Nullable
    private DroneEntity resolveDrone(ServerLevel level) {
        List<DroneEntity> candidates = candidates(level);
        if (candidates.isEmpty()) {
            return null;
        }
        if (this.bound != null) {
            for (DroneEntity drone : candidates) {
                if (drone.getUUID().equals(this.bound)) {
                    return drone;
                }
            }
        }
        return candidates.get(0);
    }

    /** Camera-equipped drones in range, nearest first. */
    private List<DroneEntity> candidates(ServerLevel level) {
        double x = this.worldPosition.getX() + 0.5;
        double y = this.worldPosition.getY() + 0.5;
        double z = this.worldPosition.getZ() + 0.5;
        double rangeSqr = BIND_RANGE * BIND_RANGE;
        List<DroneEntity> found = new ArrayList<>();
        for (DroneEntity drone : DroneTracker.drones(level)) {
            if (drone.isAlive() && drone.cameraSpec() != null && drone.distanceToSqr(x, y, z) <= rangeSqr) {
                found.add(drone);
            }
        }
        found.sort(Comparator.comparingDouble(d -> d.distanceToSqr(x, y, z)));
        return found;
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            CameraNet.unregisterMonitor(sl, this.worldPosition);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.bound != null) {
            tag.putUUID("Bound", this.bound);
        }
        if (!this.channels.isEmpty()) {
            CameraChannels.CODEC.encodeStart(NbtOps.INSTANCE, this.channels).result()
                    .ifPresent(encoded -> tag.put("Channels", encoded));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.bound = tag.hasUUID("Bound") ? tag.getUUID("Bound") : null;
        this.channels = tag.contains("Channels")
                ? CameraChannels.CODEC.parse(NbtOps.INSTANCE, tag.get("Channels"))
                .result().orElse(CameraChannels.EMPTY)
                : CameraChannels.EMPTY;
        this.feedId = tag.getInt("Feed");
        this.feedIds = tag.getIntArray("Feeds");
        this.selectedChannel = tag.getInt("Selected");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("Feed", this.feedId);
        tag.putIntArray("Feeds", this.feedIds);
        tag.putInt("Selected", this.selectedChannel);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
