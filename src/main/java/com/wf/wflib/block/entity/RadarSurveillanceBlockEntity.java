package com.wf.wflib.block.entity;

import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.block.RadarSurveillanceBlock;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.network.ScopeFramePacket;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconNetwork;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.SensorHandle;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.alert.Alert;
import com.wf.wflib.recon.alert.AlertTier;
import com.wf.wflib.recon.alert.EngagementAuthority;
import com.wf.wflib.recon.scope.ScopeFrame;
import com.wf.wflib.recon.track.Track;
import com.wf.wflib.recon.track.TrackPicture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class RadarSurveillanceBlockEntity extends BlockEntity implements ReconBound {

    public static final double RANGE = 512.0;
    public static final double MAST = 12.0;
    public static final int SWEEP_TICKS = 20;
    public static final int ALERT_HOLD_TICKS = 60;

    private UUID cachedTeamId;
    private int teamRefresh;
    @Nullable
    private UUID bound;
    private int signal;
    private int signalTicks;
    private int trackCount;
    private long sentAt = Long.MIN_VALUE;

    public RadarSurveillanceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADAR_SURVEILLANCE.get(), pos, state);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        if (--this.teamRefresh <= 0) {
            this.resolve(sl);
            this.teamRefresh = 100;
        }
        ReconNet.registerSensor(sl, this.worldPosition,
                SensorSpec.surveillanceRadar(ReconNet.netId(this.cachedTeamId), RANGE)
                        .withMast(MAST).withSweep(SWEEP_TICKS));

        TrackPicture picture = ReconNet.picture(sl, ReconNet.netId(this.cachedTeamId));
        this.trackCount = picture.tracks().size();
        this.consumeAlerts(sl, picture.alerts());
        this.broadcast(sl, picture);
        if (this.signalTicks > 0 && --this.signalTicks == 0) {
            this.setSignal(sl, 0);
        }
    }

    private void broadcast(ServerLevel sl, TrackPicture picture) {
        if (picture.gameTime() == this.sentAt || !this.isFrameSender(sl)) {
            return;
        }
        this.sentAt = picture.gameTime();
        ScopeFrame frame = ScopeFrame.of(ReconNet.netId(this.cachedTeamId),
                this.worldPosition.getX() + 0.5, this.worldPosition.getY() + MAST,
                this.worldPosition.getZ() + 0.5, RANGE, picture);
        PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(this.worldPosition),
                new ScopeFramePacket(frame));
    }

    /** @return true if lowest-positioned sensor on its net (speaks for it). */
    private boolean isFrameSender(ServerLevel sl) {
        ReconNetwork net = ReconNet.existing(sl, ReconNet.netId(this.cachedTeamId));
        if (net == null) {
            return false;
        }
        if (net.grid().rootCount() > 0) {
            return false;
        }
        long mine = this.worldPosition.asLong();
        for (SensorHandle handle : net.sensorHandles()) {
            if (handle.id() < mine) {
                return false;
            }
        }
        return true;
    }

    private void consumeAlerts(ServerLevel sl, List<Alert> alerts) {
        int strongest = 0;
        for (int i = 0; i < alerts.size(); i++) {
            Alert alert = alerts.get(i);
            int strength = alert.tier() == AlertTier.CRITICAL ? 15 : 7;
            if (strength > strongest) {
                strongest = strength;
            }
        }
        if (strongest > 0) {
            this.signalTicks = ALERT_HOLD_TICKS;
            if (strongest > this.signal) {
                this.setSignal(sl, strongest);
            }
        }
    }

    private void setSignal(ServerLevel sl, int strength) {
        if (this.signal == strength) {
            return;
        }
        this.signal = strength;
        BlockState state = this.getBlockState();
        if (state.hasProperty(RadarSurveillanceBlock.ALERTING)) {
            sl.setBlock(this.worldPosition, state.setValue(RadarSurveillanceBlock.ALERTING, strength > 0), 3);
        }
        sl.updateNeighborsAt(this.worldPosition, state.getBlock());
    }

    public int signal() {
        return this.signal;
    }

    /** @return this set's picture; {@link #nearest()} draws from it for the right-click report. */
    public TrackPicture picture() {
        return this.level instanceof ServerLevel sl
                ? ReconNet.picture(sl, ReconNet.netId(this.cachedTeamId))
                : TrackPicture.EMPTY;
    }

    private void resolve(ServerLevel sl) {
        this.cachedTeamId = this.bound != null ? this.bound
                : ReconOwners.owningAt(sl, this.worldPosition);
    }

    @Override
    public long netId() {
        return ReconNet.netId(this.cachedTeamId);
    }

    @Nullable
    @Override
    public UUID boundNet() {
        return this.bound;
    }

    @Override
    public void bindNet(@Nullable UUID id) {
        ServerLevel sl = this.level instanceof ServerLevel s ? s : null;
        if (sl != null) {
            ReconNet.unregisterSensor(sl, this.worldPosition, this.netId());
        }
        this.bound = id;
        this.setChanged();
        if (sl != null) {
            this.resolve(sl);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.bound != null) {
            tag.putUUID("BoundNet", this.bound);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.bound = tag.hasUUID("BoundNet") ? tag.getUUID("BoundNet") : null;
    }

    public int trackCount() {
        return this.trackCount;
    }

    /** @return nearest track, or null; spares callers from querying {@link #picture()} directly. */
    public Track nearest() {
        return this.picture().nearest(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + MAST,
                this.worldPosition.getZ() + 0.5, RANGE, t -> true);
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            ReconNet.unregisterSensor(sl, this.worldPosition, this.netId());
        }
        super.setRemoved();
    }
}
