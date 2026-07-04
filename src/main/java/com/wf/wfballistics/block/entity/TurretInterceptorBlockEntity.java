package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.item.MissilePreset;
import com.wf.wfballistics.item.MissilePresetRegistry;
import com.wf.wfballistics.sim.IMissileListener;
import com.wf.wfballistics.sim.MissileListenerRegistry;
import com.wf.wfballistics.sim.MissileSimConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public abstract class TurretInterceptorBlockEntity extends BlockEntity implements IMissileListener {

    /**
     * Acquisition radius (blocks) for hostile missiles. Large enough to engage missiles during their cruise
     * (including high-altitude cruisers passing overhead), not only in their terminal dive.
     */
    public static final double RANGE = 200.0;
    /**
     * Listener radius — larger than RANGE so simulated missiles materialize before entering engagement range.
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

    // The interceptor preset this battery fires.
    private final ResourceLocation presetId;
    // Stable per-battery identity, stamped onto every interceptor it fires (its "control id").
    private UUID controlId;
    private int cooldown = 0;
    // WarForge faction claiming this battery's land (its "team"), refreshed periodically. Missiles of the same
    // or an allied faction are treated as friendly, so a base's defenses don't engage its own faction's
    // missiles regardless of which launcher fired them.
    private UUID cachedTeamId = null;
    private int teamRefresh = 0;
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
            this.cachedTeamId = WarforgeCompat.factionClaiming(sl, this.worldPosition);
            this.teamRefresh = 100;
        }
        if (this.warnCooldown > 0) {
            this.warnCooldown--;
        }
        this.tickReload();

        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }

        Vec3 muzzle = Vec3.atCenterOf(this.worldPosition).add(0.0, 2.0, 0.0);
        MissileEntity target = this.acquireTarget(sl, muzzle);
        if (target == null || this.outOfAmmo()) {
            return;
        }
        if (this.warnCooldown <= 0) {
            this.warnNearby(sl);
            this.warnCooldown = WARN_INTERVAL;
        }
        this.launch(sl, muzzle, target);
        this.cooldown = FIRE_INTERVAL;
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
     * Nearest live, hostile missile of this battery's target class within RANGE, or null.
     */
    private MissileEntity acquireTarget(ServerLevel sl, Vec3 muzzle) {
        AABB box = new AABB(this.worldPosition).inflate(RANGE);
        List<MissileEntity> nearby = sl.getEntitiesOfClass(MissileEntity.class, box, MissileEntity::isAlive);
        // Don't launch at a missile another interceptor is already committed to — saves interceptors and
        // spreads coverage across incoming threats.
        Set<UUID> claimed = MissileEntity.claimedTargets(nearby, Integer.MAX_VALUE);
        MissileEntity best = null;
        double bestSq = RANGE * RANGE;
        for (MissileEntity m : nearby) {
            if (m.isRemoved() || !this.isEngageable(m) || claimed.contains(m.getUUID())) {
                continue;
            }
            double dsq = m.getBoundingBox().getCenter().distanceToSqr(muzzle);
            // Stealth missiles are only detectable at short range and with a per-scan chance (see detectableAt).
            if (!m.detectableAt(dsq, sl.random)) {
                continue;
            }
            if (dsq <= bestSq) {
                bestSq = dsq;
                best = m;
            }
        }
        return best;
    }

    /**
     * Engageable = a hostile missile that isn't itself an interceptor. Speed is not a filter (every battery
     * tries every threat it can see); stealth is handled separately by the range/chance detection roll.
     */
    private boolean isEngageable(MissileEntity m) {
        return !m.isInterceptor() && this.isHostile(m);
    }

    /**
     * Hostile = not our own missile (shared control id) and not a friendly WarForge faction's missile (same /
     * allied / truced faction as the land this battery sits on). Without WarForge, falls back to control id.
     */
    private boolean isHostile(MissileEntity m) {
        UUID mc = m.getControlId();
        if (mc != null && mc.equals(this.controlId())) {
            return false;
        }
        return !WarforgeCompat.areFactionsFriendly(this.cachedTeamId, m.getTeamId());
    }

    private void launch(ServerLevel sl, Vec3 muzzle, MissileEntity target) {
        MissilePreset preset = MissilePresetRegistry.get(this.presetId);
        MissileEntity m = preset.build(sl, muzzle);
        m.setControlId(this.controlId());
        m.setTeamId(this.cachedTeamId);
        m.setInterceptLock(target.getUUID());
        m.moveTo(muzzle.x, muzzle.y, muzzle.z, 0.0f, 0.0f);
        sl.addFreshEntity(m);
        if (MissileSimConfig.BATTERY_MAGAZINE > 0 && this.ammo > 0) {
            this.ammo--;
            this.setChanged();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
        tag.putInt("Ammo", this.ammo);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("ControlId")) {
            this.controlId = tag.getUUID("ControlId");
        }
        if (tag.contains("Ammo")) {
            this.ammo = tag.getInt("Ammo");
        }
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
        }
        super.setRemoved();
    }
}
