package com.wf.wflib.block.entity;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.intercept.InterceptBattery;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.sim.MissileSimConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public abstract class TurretInterceptorBlockEntity extends BlockEntity implements InterceptBattery.Mount, ReconBound {

    /** Acquisition radius (blocks) for hostile missiles. */
    public static final double RANGE = 200.0;
    /**
     * Ticks between interceptor launches.
     */
    public static final int FIRE_INTERVAL = 40;
    /**
     * Minimum ticks between "incoming missile" warnings broadcast to nearby players.
     */
    public static final int WARN_INTERVAL = 200;
    /**
     * Radius (blocks) within which players are warned of an incoming missile.
     */
    public static final double WARN_RADIUS = 160.0;
    private static final InterceptBattery.Settings SETTINGS =
            new InterceptBattery.Settings(RANGE, true, 2.0, 1, 0, FIRE_INTERVAL);

    // The interceptor preset this battery fires.
    private final ResourceLocation presetId;
    // Stable per-battery identity, stamped onto every interceptor it fires (its "control id").
    private UUID controlId;
    private UUID cachedTeamId = null;
    private int teamRefresh = 0;
    @Nullable
    private UUID bound = null;
    private int warnCooldown = 0;
    // Ammo (only used when MissileSimConfig.BATTERY_MAGAZINE > 0). -1 = not yet initialised to a full magazine.
    private int ammo = -1;
    private int reloadTimer = 0;
    @Nullable
    private InterceptBattery battery;

    protected TurretInterceptorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                           String presetId) {
        super(type, pos, state);
        this.presetId = MissilePresetRegistry.parse(presetId);
    }

    @Override
    public UUID controlId() {
        if (this.controlId == null) {
            this.controlId = UUID.randomUUID();
            this.setChanged();
        }
        return this.controlId;
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        // Refresh our faction (the one claiming this chunk) occasionally rather than every tick.
        if (--this.teamRefresh <= 0) {
            this.resolve(sl);
            this.teamRefresh = 100;
        }
        if (this.warnCooldown > 0) {
            this.warnCooldown--;
        }
        this.tickReload();
        if (this.battery == null) {
            // Claimant = position: what the network keys this sensor by, and what survives a reload.
            this.battery = new InterceptBattery(this, this.worldPosition.asLong(), this.worldPosition);
        }
        this.battery.tick(sl, SETTINGS);
    }

    @Override
    public Vec3 muzzle() {
        return Vec3.atCenterOf(this.worldPosition).add(0.0, 2.0, 0.0);
    }

    @Nullable
    @Override
    public UUID team() {
        return this.cachedTeamId;
    }

    @Override
    public double interceptorSpeed() {
        return this.outOfAmmo() ? Double.NaN : MissilePresetRegistry.get(this.presetId).cruiseSpeed();
    }

    /** Vertical launch: nothing to lay. */
    @Override
    public boolean lay(Vec3 aimPoint) {
        return true;
    }

    @Override
    public boolean fire(InterceptTarget target) {
        ServerLevel sl = (ServerLevel) this.level;
        if (this.warnCooldown <= 0) {
            this.warnNearby(sl);
            this.warnCooldown = WARN_INTERVAL;
        }
        Vec3 muzzle = this.muzzle();
        MissilePreset preset = MissilePresetRegistry.get(this.presetId);
        MissileEntity m = preset.build(sl, muzzle);
        InterceptBattery.arm(m, this.controlId(), this.cachedTeamId, target.interceptEntity());
        m.moveTo(muzzle.x, muzzle.y, muzzle.z, 0.0f, 0.0f);
        sl.addFreshEntity(m);
        if (MissileSimConfig.BATTERY_MAGAZINE > 0 && this.ammo > 0) {
            this.ammo--;
            this.setChanged();
        }
        return true;
    }

    @Override
    public boolean valid() {
        return !this.isRemoved() && this.level instanceof ServerLevel sl && sl.isLoaded(this.worldPosition);
    }

    /**
     * Broadcasts an "incoming missile" warning + alert sound to players within {@link #WARN_RADIUS}.
     */
    private void warnNearby(ServerLevel sl) {
        Vec3 c = Vec3.atCenterOf(this.worldPosition);
        Component msg = Component.literal("⚠ Incoming missile - interceptor battery engaging")
                .withStyle(ChatFormatting.RED);
        double r2 = WARN_RADIUS * WARN_RADIUS;
        for (ServerPlayer p : sl.players()) {
            if (p.position().distanceToSqr(c) <= r2) {
                p.displayClientMessage(msg, true);
                sl.playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.0f, 0.5f);
            }
        }
    }

    /**
     * Regenerates one interceptor toward the magazine over time (only when a finite magazine is configured).
     */
    private void tickReload() {
        int mag = MissileSimConfig.BATTERY_MAGAZINE;
        if (mag <= 0) {
            return; // unlimited ammo
        }
        if (this.ammo < 0) {
            this.ammo = mag; // first run under a finite magazine: start full
        }
        if (this.ammo < mag && --this.reloadTimer <= 0) {
            this.ammo++;
            this.reloadTimer = MissileSimConfig.BATTERY_RELOAD_TICKS;
            this.setChanged();
        }
    }

    private boolean outOfAmmo() {
        return MissileSimConfig.BATTERY_MAGAZINE > 0 && this.ammo == 0;
    }

    /**
     * Work out whose battery this is: the binding if it has one, and otherwise whoever claims the ground under it.
     */
    private void resolve(ServerLevel sl) {
        this.cachedTeamId = this.bound != null ? this.bound
                : ReconOwners.owningAt(sl, this.worldPosition);
    }

    /**
     * @return which network this battery feeds, and the transponder code it reads as friendly.
     */
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
        this.bound = id;
        this.setChanged();
        if (this.level instanceof ServerLevel sl) {
            this.resolve(sl);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
        tag.putInt("Ammo", this.ammo);
        if (this.bound != null) {
            tag.putUUID("BoundNet", this.bound);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("ControlId")) {
            this.controlId = tag.getUUID("ControlId");
        }
        if (tag.contains("Ammo")) {
            this.ammo = tag.getInt("Ammo");
        }
        this.bound = tag.hasUUID("BoundNet") ? tag.getUUID("BoundNet") : null;
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl && this.battery != null) {
            this.battery.release(sl);
        }
        super.setRemoved();
    }
}
