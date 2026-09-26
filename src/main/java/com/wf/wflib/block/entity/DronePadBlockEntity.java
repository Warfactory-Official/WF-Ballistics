package com.wf.wflib.block.entity;

import com.wf.wflib.sim.SimTier;
import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.drone.CrateEntity;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneMission;
import com.wf.wflib.exchange.Exchange;
import com.wf.wflib.exchange.ExchangeManager;
import com.wf.wflib.exchange.ExchangeMode;
import com.wf.wflib.exchange.StationCode;
import com.wf.wflib.exchange.StationRecord;
import com.wf.wflib.exchange.StationRegistry;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.DroneTracker;
import com.wf.wflib.drone.sim.SimDrone;
import com.wf.wflib.drone.sim.SimDroneManager;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import com.wf.wflib.menu.DronePadMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The pad's state: the cargo waiting to be flown out, the mission it will be flown on, and the recharging of drones
 * parked on top.
 */
public class DronePadBlockEntity extends BlockEntity implements MenuProvider {

    /**
     * Matches {@link CrateEntity#SLOTS} so a full pad loads exactly one full crate.
     */
    public static final int CARGO_SLOTS = CrateEntity.SLOTS;
    /**
     * Charge per tick returned to a drone idling on the pad. A full battery takes a couple of minutes.
     */
    public static final double RECHARGE_RATE = 30.0;
    /**
     * How far above the pad a drone counts as parked on it.
     */
    private static final double PAD_RADIUS = 2.5;
    private static final int RECHARGE_INTERVAL = 10;

    private final NonNullList<ItemStack> cargo = NonNullList.withSize(CARGO_SLOTS, ItemStack.EMPTY);
    private DroneMission mission = new DroneMission();
    private boolean wasPowered;
    /** This pad's own station code. */
    @Nullable
    private String stationCode;

    public DronePadBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_PAD.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.wflib.drone_pad");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new DronePadMenu(id, inventory, ContainerLevelAccess.create(this.level, this.worldPosition),
                this.worldPosition);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DronePadBlockEntity pad) {
        if (level.getGameTime() % RECHARGE_INTERVAL != 0 || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        pad.rechargeParked(serverLevel, pos);
    }

    /**
     * Top up any drone sitting idle on the pad, which is what closes the delivery loop: a drone that comes home
     * flat can fly again without being rebuilt.
     */
    private void rechargeParked(ServerLevel level, BlockPos pos) {
        Vec3 centre = Vec3.atCenterOf(pos.above());
        for (DroneEntity drone : DroneTracker.drones(level)) {
            if (drone.flight().getDroneState() != DroneState.IDLE) {
                continue;
            }
            if (drone.position().distanceToSqr(centre) <= PAD_RADIUS * PAD_RADIUS
                    && drone.battery().charge() < drone.battery().capacity()) {
                drone.battery().recharge(RECHARGE_RATE * RECHARGE_INTERVAL);
            }
        }
    }

    public DroneMission mission() {
        return this.mission;
    }

    public void setMission(DroneMission mission) {
        this.mission = mission;
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            // Push it to the client so the config screen reopens showing what is actually stored.
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public void initPowered(boolean powered) {
        this.wasPowered = powered;
        this.setChanged();
    }

    public void onRedstone(boolean powered) {
        if (powered && !this.wasPowered) {
            this.dispatch();
        }
        this.wasPowered = powered;
        this.setChanged();
    }

    /**
     * Send the stored mission.
     *
     * @return null on success, or why the dispatch was refused
     */
    @Nullable
    public String dispatch() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return "no level";
        }
        Vec3 origin = Vec3.atCenterOf(this.worldPosition.above());
        this.mission.exfil = origin;
        this.mission.stationCode = this.stationCode(serverLevel);
        this.mission.collecting = false;
        this.mission.exchangeId = null;

        if (this.mission.mode.resolvesDestination()) {
            String refusal = this.arrangeHandshake(serverLevel);
            if (refusal != null) {
                return refusal;
            }
        } else if (this.mission.program.isEmpty() && this.mission.destination.equals(Vec3.ZERO)) {
            return "no destination set";
        }

        CompoundTag cargo = null;
        if (!this.cargoEmpty()) {
            cargo = new CompoundTag();
            ContainerHelper.saveAllItems(cargo, this.cargo, this.level.registryAccess());
        }

        DroneMission.Result result = this.mission.dispatch(serverLevel, origin, cargo);
        if (!result.ok()) {
            return result.error();
        }
        if (cargo != null) {
            this.cargo.clear();
        }
        this.setChanged();
        return null;
    }

    private boolean cargoEmpty() {
        return this.cargo.stream().allMatch(ItemStack::isEmpty);
    }

    // --- exchange ---

    /**
     * @return this pad's station code, registering it on first use.
     */
    public String stationCode(ServerLevel level) {
        if (this.stationCode == null) {
            this.stationCode = StationRegistry.get(level).register(level, this.worldPosition);
            this.setChanged();
            level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        return this.stationCode;
    }

    /**
     * @return the code as the client knows it. Its own code is the one thing about the exchange system a
     *      client is meant to see: you have to be able to read it off the screen to give it to a trading partner.
     */
    @Nullable
    public String knownStationCode() {
        return this.stationCode;
    }

    /**
     * Arrange the meeting and point the mission at it.
     *
     * @return null on success, or a reason that reveals nothing
     */
    @Nullable
    private String arrangeHandshake(ServerLevel level) {
        String recipient = this.mission.recipientCode;
        if (!StationCode.valid(recipient)) {
            return "no recipient station code set";
        }
        if (this.cargoEmpty()) {
            return "nothing loaded to trade";
        }
        String own = this.stationCode(level);
        if (own == null) {
            return "this pad could not be registered";
        }
        ExchangeManager.Result result = ExchangeManager.arrange(level.getServer(), own, recipient, true, false);
        if (!result.ok()) {
            return result.error();
        }
        Exchange exchange = result.exchange();
        this.mission.destination = exchange.rendezvous();
        this.mission.exchangeId = exchange.id();
        exchange.party(own).markDispatched();
        return null;
    }

    /**
     * Send a drone out to collect what the far side left at the rendezvous.
     *
     * @return null on success, or why no drone went
     */
    @Nullable
    public String dispatchCollection(Exchange exchange, StationRecord station) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return "no level";
        }
        Vec3 origin = Vec3.atCenterOf(this.worldPosition.above());
        DroneMission run = new DroneMission();
        run.modelId = this.mission.modelId;
        run.mode = ExchangeMode.HANDSHAKE;
        run.destination = exchange.rendezvous();
        run.exfil = origin;
        run.count = 1;
        run.cruiseSpeed = this.mission.cruiseSpeed;
        run.cruiseAltitude = this.mission.cruiseAltitude;
        run.formationSpacing = this.mission.formationSpacing;
        run.batteryCapacity = this.mission.batteryCapacity;
        run.collecting = true;
        run.exchangeId = exchange.id();
        run.stationCode = station.code();

        DroneMission.Result result = run.dispatch(serverLevel, origin, null);
        return result.ok() ? null : result.error();
    }

    /**
     * Order every drone this pad can see home.
     *
     * @return how many drones were recalled
     */
    public int recall(ServerLevel level) {
        Vec3 home = Vec3.atCenterOf(this.worldPosition.above());
        int count = 0;
        for (DroneEntity drone : DroneTracker.drones(level)) {
            if (!drone.flight().getDroneState().powered()) {
                continue;
            }
            drone.route().setExfil(home);
            drone.route().setDestination(null);
            drone.flight().setState(DroneState.EXFIL);
            count++;
        }
        for (SimDrone sd : new ArrayList<>(SimDroneManager.tier(level).view())) {
            sd.exfil = home;
            sd.destination = null;
            sd.state = DroneState.EXFIL;
            count++;
        }
        return count;
    }

    /**
     * Wipe this dimension's drones, crates and off-world records, so a test can be re-run from clean.
     *
     * @return how many entities were removed
     */
    public int clearDrones(ServerLevel level) {
        int count = 0;
        for (DroneEntity drone : new ArrayList<>(DroneTracker.drones(level))) {
            drone.discard();
            count++;
        }
        for (CrateEntity crate : level.getEntitiesOfClass(CrateEntity.class,
                new AABB(this.worldPosition).inflate(4096.0))) {
            crate.discard();
            count++;
        }
        SimTier<SimDrone> registry = SimDroneManager.tier(level);
        for (SimDrone sd : new ArrayList<>(registry.view())) {
            registry.remove(sd);
            count++;
        }
        return count;
    }

    public void openCargo(Player player) {
        Container container = new SimpleContainer(this.cargo.toArray(new ItemStack[0])) {
            @Override
            public void setChanged() {
                for (int i = 0; i < CARGO_SLOTS; i++) {
                    DronePadBlockEntity.this.cargo.set(i, this.getItem(i));
                }
                DronePadBlockEntity.this.setChanged();
            }
        };
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, container),
                Component.translatable("block.wflib.drone_pad")));
    }

    /** The pad's own store, for a drone to draw building materials from or hand a demolition's recovery into. */
    public NonNullList<ItemStack> cargoItems() {
        return this.cargo;
    }

    public void spillCargo() {
        if (this.level != null) {
            Containers.dropContents(this.level, this.worldPosition, this.cargo);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        this.saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.cargo.clear();
        ContainerHelper.loadAllItems(tag, this.cargo, registries);
        if (tag.contains("Mission")) {
            this.mission = DroneMission.load(tag.getCompound("Mission"));
        }
        this.stationCode = tag.contains("StationCode") ? tag.getString("StationCode") : null;
        this.wasPowered = tag.getBoolean("WasPowered");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.cargo, registries);
        tag.put("Mission", this.mission.save());
        if (this.stationCode != null) {
            tag.putString("StationCode", this.stationCode);
        }
        tag.putBoolean("WasPowered", this.wasPowered);
    }
}
