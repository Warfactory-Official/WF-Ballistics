package com.wf.wflib.block.entity;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconNetwork;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.SensorSpec;
import com.wf.wflib.recon.fc.FireControl;
import com.wf.wflib.recon.track.IffState;
import com.wf.wflib.recon.track.Track;
import com.wf.wflib.recon.track.TrackQuality;
import com.wf.wflib.sim.IMissileListener;
import com.wf.wflib.sim.MissileListenerRegistry;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public abstract class TurretInterceptorBlockEntity extends BlockEntity implements IMissileListener, ReconBound {

    /** Acquisition radius (blocks) for hostile missiles. */
    public static final double RANGE = 200.0;
    /** Contact classes this battery will spend an interceptor on. */
    private static final java.util.Set<ContactClass> ENGAGED_CLASSES =
            java.util.EnumSet.of(ContactClass.MISSILE, ContactClass.DRONE);
    /**
     * Listener radius: larger than RANGE so simulated missiles materialize before entering engagement range.
     */
    public static final double LISTENER_RANGE = 280.0;
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
    /** Ticks a battery holds its claim on a track after launching. */
    public static final int CLAIM_TICKS = 120;
    /** Widest track error this battery will launch on. */
    public static final double MAX_TRACK_ERROR = 32.0;
    /** Sweep interval. */
    private static final int SWEEP_TICKS = 4;

    // The interceptor preset this battery fires.
    private final ResourceLocation presetId;
    // Stable per-battery identity, stamped onto every interceptor it fires (its "control id").
    private UUID controlId;
    private int cooldown = 0;
    private UUID cachedTeamId = null;
    private int teamRefresh = 0;
    @Nullable
    private UUID bound = null;
    private int warnCooldown = 0;
    // Ammo (only used when MissileSimConfig.BATTERY_MAGAZINE > 0). -1 = not yet initialised to a full magazine.
    private int ammo = -1;
    private int reloadTimer = 0;

    protected TurretInterceptorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                           String presetId) {
        super(type, pos, state);
        this.presetId = MissilePresetRegistry.parse(presetId);
    }

    private UUID controlId() {
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
        // Pull simulated missiles back into the real world as they approach, so we can actually engage them.
        MissileListenerRegistry.get(sl).register(this.worldPosition, this);

        // Refresh our faction (the one claiming this chunk) occasionally rather than every tick.
        if (--this.teamRefresh <= 0) {
            this.resolve(sl);
            this.teamRefresh = 100;
        }
        ReconNet.registerSensor(sl, this.worldPosition,
                SensorSpec.surveillanceRadar(this.netId(), RANGE).withMast(2.0).withSweep(SWEEP_TICKS));
        if (this.warnCooldown > 0) {
            this.warnCooldown--;
        }
        this.tickReload();

        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }

        if (this.outOfAmmo()) {
            return;
        }
        Vec3 muzzle = Vec3.atCenterOf(this.worldPosition).add(0.0, 2.0, 0.0);
        Engagement engagement = this.acquire(sl, muzzle);
        if (engagement == null) {
            return;
        }
        if (this.warnCooldown <= 0) {
            this.warnNearby(sl);
            this.warnCooldown = WARN_INTERVAL;
        }
        this.launch(sl, muzzle, engagement.target());
        engagement.net().claim(engagement.track().id(), this.sensorId(), sl.getGameTime(), CLAIM_TICKS);
        this.cooldown = FIRE_INTERVAL;
    }

    /**
     * A track this battery is prepared to shoot at, and the missile behind it.
     */
    private record Engagement(ReconNetwork net, Track track, InterceptTarget target) {
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

    /** The closest confirmed missile contact this battery may engage, resolved to something it can lock onto. */
    private Engagement acquire(ServerLevel sl, Vec3 muzzle) {
        ReconNetwork net = ReconNet.existing(sl, this.netId());
        if (net == null) {
            return null;
        }
        long now = sl.getGameTime();
        long self = this.sensorId();
        Track track = net.picture().nearest(muzzle.x, muzzle.y, muzzle.z, RANGE,
                t -> ENGAGED_CLASSES.contains(t.guess())
                        && t.quality().atLeast(TrackQuality.CONFIRMED)
                        && t.iff() != IffState.FRIENDLY
                        && !net.claimedByOther(t.id(), self, now));
        if (track == null) {
            return null;
        }
        Class<? extends Entity> type = track.guess() == ContactClass.DRONE ? DroneEntity.class : MissileEntity.class;
        Entity resolved = FireControl.resolve(sl, type, track, MAX_TRACK_ERROR,
                e -> e instanceof InterceptTarget t && this.isEngageable(t));
        return resolved == null ? null : new Engagement(net, track, (InterceptTarget) resolved);
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

    /**
     * @return this battery's identity on the claim board. Its position, because that is what the network keys
     *      its sensor by and what survives the block entity being reloaded.
     */
    private long sensorId() {
        return this.worldPosition.asLong();
    }

    /** Engageable = a hostile target still worth a shot. */
    private boolean isEngageable(InterceptTarget t) {
        return t.interceptEngageable() && this.isHostile(t);
    }

    /**
     * Hostile = not ours (shared control id) and not a friendly WarForge faction's (same / allied / truced faction
     * as the land this battery sits on).
     */
    private boolean isHostile(InterceptTarget t) {
        UUID mc = t.interceptControlId();
        if (mc != null && mc.equals(this.controlId())) {
            return false;
        }
        return !WarforgeCompat.areFactionsFriendly(this.cachedTeamId, t.interceptTeamId());
    }

    private void launch(ServerLevel sl, Vec3 muzzle, InterceptTarget target) {
        MissilePreset preset = MissilePresetRegistry.get(this.presetId);
        MissileEntity m = preset.build(sl, muzzle);
        m.setControlId(this.controlId());
        m.setTeamId(this.cachedTeamId);
        m.setInterceptLock(target.interceptEntity().getUUID());
        m.moveTo(muzzle.x, muzzle.y, muzzle.z, 0.0f, 0.0f);
        sl.addFreshEntity(m);
        if (MissileSimConfig.BATTERY_MAGAZINE > 0 && this.ammo > 0) {
            this.ammo--;
            this.setChanged();
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
    public Vec3 listenerCenter() {
        return Vec3.atCenterOf(this.worldPosition);
    }

    @Override
    public double listenerRange() {
        return LISTENER_RANGE;
    }

    @Override
    public boolean listenerValid() {
        return !this.isRemoved() && this.level instanceof ServerLevel sl && sl.isLoaded(this.worldPosition);
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel sl) {
            MissileListenerRegistry.get(sl).deregister(this.worldPosition);
            ReconNet.unregisterSensor(sl, this.worldPosition, this.netId());
        }
        super.setRemoved();
    }
}
