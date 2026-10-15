package com.wf.wflib.block.entity;

import com.wf.wflib.sim.SimTier;
import com.wf.wflib.block.ModBlockEntities;
import com.wf.wflib.drone.CrateEntity;
import com.wf.wflib.drone.ControllableDrone;
import com.wf.wflib.drone.DroneTasking;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneMission;
import com.wf.wflib.drone.DroneProgram;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.drone.dispatch.DroneDispatcher;
import com.wf.wflib.drone.dispatch.DroneDispatchers;
import com.wf.wflib.recon.ReconOwners;
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
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

public class DronePadBlockEntity extends BlockEntity implements MenuProvider, DroneDispatcher {

    /** = {@link CrateEntity#SLOTS}: full pad maps to one full crate. */
    public static final int CARGO_SLOTS = CrateEntity.SLOTS;
    /** Per tick; full charge in a couple of minutes. */
    public static final double RECHARGE_RATE = 30.0;
    private static final double PAD_RADIUS = 2.5;
    /** Another mod's bodies parked idle within this are flown before anything is spawned. */
    private static final double PARKED_RADIUS = 8.0;
    private static final int RECHARGE_INTERVAL = 10;

    private final NonNullList<ItemStack> cargo = NonNullList.withSize(CARGO_SLOTS, ItemStack.EMPTY);
    private DroneMission mission = new DroneMission();
    private boolean wasPowered;
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

    /** Closes delivery loop: idle drone recharges without rebuild. */
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
            // Sync: config screen reopens showing stored state.
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

    /** @return null on success, else refusal reason. */
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

        DroneMission.Result result = this.launch(serverLevel, this.mission, origin, !this.cargoEmpty());
        return result.ok() ? null : result.error();
    }

    /** {@code withCargo} => pad cargo rides the leader, pad emptied on success. */
    private DroneMission.Result launch(ServerLevel level, DroneMission run, Vec3 origin, boolean withCargo) {
        CompoundTag cargo = null;
        if (withCargo) {
            cargo = new CompoundTag();
            ContainerHelper.saveAllItems(cargo, this.cargo, level.registryAccess());
        }
        DroneMission.Result result = run.dispatch(level, origin, cargo);
        if (result.ok() && cargo != null) {
            this.cargo.clear();
        }
        this.setChanged();
        return result;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel serverLevel) {
            DroneDispatchers.add(serverLevel, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null) {
            DroneDispatchers.remove(this.level, this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.level != null) {
            DroneDispatchers.remove(this.level, this);
        }
    }

    @Override
    public BlockPos dispatchPos() {
        return this.worldPosition;
    }

    @Nullable
    @Override
    public UUID side() {
        return ReconOwners.owningAt((ServerLevel) this.level, this.worldPosition);
    }

    /** Nearest idle non-{@link DroneEntity} body on this pad that flies {@code kind}, or null. */
    @Nullable
    private ControllableDrone parked(ServerLevel level, DroneTask.Kind kind) {
        Vec3 centre = Vec3.atCenterOf(this.worldPosition.above());
        ControllableDrone best = null;
        double bestSq = PARKED_RADIUS * PARKED_RADIUS;
        for (ControllableDrone body : DroneTracker.bodies(level)) {
            if (body instanceof DroneEntity || !body.carrierAlive() || !body.supports(kind)
                    || body.flight().getDroneState() != DroneState.IDLE) {
                continue;
            }
            double d = body.asEntity().position().distanceToSqr(centre);
            if (d <= bestSq) {
                bestSq = d;
                best = body;
            }
        }
        return best;
    }

    /** LOITER always; DELIVER with cargo; STRIKE / MINELAY when the configured mission slings one; plus parked bodies'. */
    @Override
    public List<DroneTask.Kind> offers() {
        List<DroneTask.Kind> kinds = new ArrayList<>(4);
        if (this.level instanceof ServerLevel sl) {
            for (DroneTask.Kind kind : DroneTask.Kind.values()) {
                if (this.parked(sl, kind) != null) {
                    kinds.add(kind);
                }
            }
        }
        if (!kinds.contains(DroneTask.Kind.LOITER)) {
            kinds.add(DroneTask.Kind.LOITER);
        }
        if (!this.cargoEmpty()) {
            kinds.add(DroneTask.Kind.DELIVER);
        }
        if (this.mission.payloadId != null && !kinds.contains(DroneTask.Kind.STRIKE)) {
            kinds.add(DroneTask.Kind.STRIKE);
        }
        if (this.mission.mines != null && !kinds.contains(DroneTask.Kind.MINELAY)) {
            kinds.add(DroneTask.Kind.MINELAY);
        }
        return kinds;
    }

    /** Configured mission's airframe, flight and count; DIRECT, home = this pad. */
    @Override
    public DroneMission.Result dispatch(ServerPlayer by, DroneTask.Kind kind, Vec3 at) {
        ControllableDrone body = this.parked((ServerLevel) this.level, kind);
        if (body != null) {
            String error = DroneTasking.assign(body, by.getUUID(), kind, at);
            if (error != null) {
                return new DroneMission.Result(List.of(), 0, error);
            }
            body.route().setExfil(Vec3.atCenterOf(this.worldPosition.above()));
            return new DroneMission.Result(List.of(body), 1, null);
        }
        if (!offers().contains(kind)) {
            return new DroneMission.Result(List.of(), 0, "pad cannot fly " + kind.id());
        }
        DroneMission run = DroneMission.load(this.mission.save());
        run.mode = ExchangeMode.DIRECT;
        run.recipientCode = null;
        run.exchangeId = null;
        run.stationCode = null;
        run.collecting = false;
        run.jobId = null;
        run.operator = by.getUUID();
        run.approachLegs = List.of();
        run.egressLegs = List.of();
        run.program = DroneProgram.of(List.of(kind.at(at)));
        if (kind != DroneTask.Kind.STRIKE) {
            run.payloadId = null;
        }
        if (kind != DroneTask.Kind.MINELAY) {
            run.mines = null;
        }
        Vec3 origin = Vec3.atCenterOf(this.worldPosition.above());
        run.exfil = origin;
        return this.launch((ServerLevel) this.level, run, origin, kind == DroneTask.Kind.DELIVER);
    }

    private boolean cargoEmpty() {
        return this.cargo.stream().allMatch(ItemStack::isEmpty);
    }

    /** @return station code, registers on first use. */
    public String stationCode(ServerLevel level) {
        if (this.stationCode == null) {
            this.stationCode = StationRegistry.get(level).register(level, this.worldPosition);
            this.setChanged();
            level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        return this.stationCode;
    }

    /** Own code only: the one exchange fact a client is meant to see, to read off-screen to a partner. */
    @Nullable
    public String knownStationCode() {
        return this.stationCode;
    }

    /** @return null on success, else a reason that reveals nothing. */
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

    /** @return null on success, else why no drone went. */
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

    public int recall(ServerLevel level) {
        Vec3 home = Vec3.atCenterOf(this.worldPosition.above());
        int count = 0;
        for (ControllableDrone drone : DroneTracker.bodies(level)) {
            if (drone.recall(home)) {
                count++;
            }
        }
        for (SimDrone sd : new ArrayList<>(SimDroneManager.tier(level).view())) {
            sd.exfil = home;
            sd.destination = null;
            sd.state = DroneState.EXFIL;
            count++;
        }
        return count;
    }

    /** Test reset: wipes drones/crates/off-world records so a run can repeat from clean. */
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

    /** Dual use: drone building-materials source, demolition recovery sink. */
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
