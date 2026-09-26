package com.wf.wflib.drone;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.block.entity.DronePadBlockEntity;
import com.wf.wflib.entity.BombletEntity;
import com.wf.wflib.exchange.ExchangeManager;
import com.wf.wflib.item.MinePreset;
import com.wf.wflib.item.MinePresetRegistry;
import com.wf.wflib.mine.MineEntity;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** What is slung under a drone: a crate and its hold, a strike payload, a mine rack. */
public final class DroneHold {

    private final DroneEntity drone;
    /** Whether a crate is gripped in the claws, and what is in it. */
    private boolean hasCrate;
    private final NonNullList<ItemStack> cargo = NonNullList.withSize(CrateEntity.SLOTS, ItemStack.EMPTY);
    /** Warhead slung under a strike drone, by registered id. */
    @Nullable
    private ResourceLocation payloadId;
    /** The rack of mines slung under a minelayer, or null on a drone that carries none. */
    @Nullable
    private MineLoad mines;

    DroneHold(DroneEntity drone) {
        this.drone = drone;
    }

    /** Crate, payload or rack aboard: what weighs on the rotors. */
    boolean laden() {
        return this.hasCrate || this.payloadId != null || this.hasMines();
    }

    /**
     * Deliver the crate: put it down on purpose, at the aim point, and count it as a delivery.
     */
    void dropCargo(ServerLevel level, Vec3 at) {
        CrateEntity crate = this.releaseCargo(level, at);
        if (crate == null) {
            return;
        }
        ExchangeManager.onCargoDropped(level, this.drone, crate);
        this.drone.route().beginEgress();
    }

    /**
     * Turn the held cargo into a real crate entity and let go of it.
     *
     * @param at where the crate is aimed. It is born where it was being drawn, in the grippers, and only
     *      then moved over {@code at}, keeping whichever height is greater, so it falls from the drone
     *      rather than materialising on the ground underneath it
     * @return the crate, or null if there was nothing aboard
     */
    @Nullable
    private CrateEntity releaseCargo(ServerLevel level, Vec3 at) {
        if (!this.hasCrate) {
            return null;
        }
        CrateEntity crate = new CrateEntity(level, this.gripPos());
        for (int i = 0; i < this.cargo.size(); i++) {
            crate.items().set(i, this.cargo.get(i));
        }
        level.addFreshEntity(crate);
        crate.release(at);
        this.setCrate(false);
        return crate;
    }

    /**
     * @return where a crate held in the grippers sits in the world, as a crate entity's position (its feet).
     *      The airframe's mount says where the load's <em>top</em> is held, so the body of it hangs below that.
     */
    private Vec3 gripPos() {
        Vec3 mount = DroneModels.mount(this.drone.getModelId());
        return this.drone.position().add(mount.x, mount.y - CrateEntity.SIZE, mount.z);
    }

    /**
     * Collect a crate somebody else left here. The other half of a handshake.
     */
    void pickUpCargo(ServerLevel level, Vec3 at) {
        if (this.hasCrate) {
            return;
        }
        Vec3 from = this.drone.position();
        AABB reach = new AABB(from, from).inflate(DroneEntity.PICKUP_RADIUS, 0.0, DroneEntity.PICKUP_RADIUS)
                .expandTowards(0.0, -DroneEntity.PICKUP_REACH_DOWN, 0.0);
        for (CrateEntity crate : level.getEntitiesOfClass(CrateEntity.class, reach)) {
            if (crate.isAlive() && !crate.isRemoved()) {
                for (int i = 0; i < this.cargo.size(); i++) {
                    this.cargo.set(i, crate.items().get(i));
                }
                this.setCrate(true);
                ExchangeManager.onCargoCollected(level, this.drone, crate);
                crate.discard();
                this.drone.route().beginEgress();
                return;
            }
        }
    }

    /** Pickle the warhead. */
    void dropPayload(ServerLevel level) {
        if (this.payloadId == null) {
            return;
        }
        BombletEntity payload = new BombletEntity(level, this.drone.position().subtract(0.0, 1.0, 0.0),
                this.drone.getDeltaMovement(), WarheadRegistry.get(this.payloadId), this.payloadId,
                DroneEntity.PAYLOAD_FUSE);
        payload.setOwner(this.drone);
        level.addFreshEntity(payload);
        this.setPayload(null);
    }

    /** Put one mine off the rack down. */
    void layMine(ServerLevel level, Vec3 aim) {
        if (this.mines == null || this.mines.empty()) {
            return;
        }
        MinePreset preset = MinePresetRegistry.get(this.mines.preset());
        if (preset == null) {
            this.drone.recordEvent(WFEventType.CARGO_DROP, "rack names an unknown mine: " + this.mines.preset());
            this.setMines(null);
            return;
        }
        MineEntity mine = preset.build(level, this.drone.getRandom().nextFloat() * 360.0f);
        Vec3 from = this.drone.position()
                .subtract(0.0, 1.0, 0.0);
        mine.moveTo(from.x, from.y, from.z, mine.getYRot(), 0.0f);
        mine.setTeamId(this.drone.getTeamId());
        mine.scatter(this.drone.getDeltaMovement(), this.drone.getRandom());
        level.addFreshEntity(mine);

        this.setMines(this.mines.afterLaying());
        this.drone.recordEvent(WFEventType.CARGO_DROP, String.format("mine away at %d %d %d, %d left",
                (int) aim.x, (int) aim.y, (int) aim.z, this.mines == null ? 0 : this.mines.remaining()));
    }

    /** Drop whatever is aboard where the drone is: used when it is shot down and when a wreck is broken up. */
    void spill() {
        if (this.drone.level() instanceof ServerLevel serverLevel && this.hasCrate) {
            this.releaseCargo(serverLevel, this.drone.position());
        }
    }

    /** Right-click recovery. @return false if nothing was aboard */
    boolean recover(Player player) {
        if (this.hasCrate) {
            this.openCargo(player);
            return true;
        }
        if (this.payloadId != null) {
            player.displayClientMessage(Component.literal("Recovered payload: " + this.payloadId.getPath()), true);
            this.setPayload(null);
            this.drone.recordEvent(WFEventType.CARGO_PICKUP, "payload recovered by hand");
            return true;
        }
        if (this.hasMines()) {
            player.displayClientMessage(Component.literal("Recovered mines: " + this.mines.label()), true);
            this.drone.recordEvent(WFEventType.CARGO_PICKUP, "rack recovered by hand: " + this.mines.label());
            this.setMines(null);
            return true;
        }
        return false;
    }

    public boolean hasCargo() {
        return this.hasCrate;
    }

    /**
     * @return true if anything is slung underneath, crate or warhead. The synced half of {@link #hasCargo},
     *      so the client can work out how hard the rotors are having to work.
     */
    public boolean isLoaded() {
        return this.drone.getEntityData().get(DroneEntity.LOAD) != 0;
    }

    /**
     * @return true if what is slung underneath is a crate, as the client sees it. Distinct from
     *      {@link #isLoaded} because a warhead weighs on the rotors the same way but is not drawn as a crate.
     */
    public boolean hasCrateAboard() {
        return (this.drone.getEntityData().get(DroneEntity.LOAD) & DroneEntity.LOAD_CRATE) != 0;
    }

    /** Grip or release a crate. */
    public void setCrate(boolean carrying) {
        this.hasCrate = carrying;
        if (!carrying) {
            this.cargo.clear();
        }
        this.sync();
    }

    /**
     * Take on cargo directly, without a crate entity ever existing: how a pad loads a drone and how one comes back
     * from the off-world sim.
     */
    public void loadCargo(CompoundTag tag) {
        this.cargo.clear();
        ContainerHelper.loadAllItems(tag, this.cargo, this.drone.registryAccess());
        this.hasCrate = true;
        this.sync();
    }

    /**
     * @return true if there is not a free slot nor a matching stack with room in it. What tells a demolition
     *      drone to go and empty out
     */
    public boolean cargoFull() {
        for (ItemStack stack : this.cargo) {
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    public boolean hasItem(Item item) {
        for (ItemStack stack : this.cargo) {
            if (stack.is(item) && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Put a block down, paying for it out of the hold.
     *
     * @return false if the space was not free, the drone had nothing to place it with, or the world refused
     *      the block. All three are the order's problem rather than the drone's, and the caller counts them
     *      against it: the space being occupied is not going to fix itself
     */
    public boolean placeFromCargo(ServerLevel level, BlockPos at, BlockState state) {
        BlockState existing = level.getBlockState(at);
        if (existing.equals(state)) {
            return true;
        }
        if (!existing.canBeReplaced()) {
            return false;
        }
        Item item = state.getBlock().asItem();
        if (item == Items.AIR || !this.takeItem(item)) {
            return false;
        }
        if (!level.setBlock(at, state, Block.UPDATE_ALL)) {
            this.giveItem(new ItemStack(item));
            return false;
        }
        level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE, at,
                net.minecraft.world.level.gameevent.GameEvent.Context.of(this.drone, state));
        return true;
    }

    /**
     * Take a block down and put what it drops in the hold.
     *
     * @return false only if the block cannot be removed at all. An empty space counts as done: the plan was
     *      drawn against the world as it was, and somebody mining a block by hand should not leave an order that
     *      fails three times and blocks
     */
    public boolean breakIntoCargo(ServerLevel level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if (state.isAir()) {
            return true;
        }
        if (state.getDestroySpeed(level, at) < 0.0F) {
            return false;
        }
        for (ItemStack drop : Block.getDrops(state, level, at, level.getBlockEntity(at), this.drone,
                ItemStack.EMPTY)) {
            this.giveItem(drop);
        }
        return level.destroyBlock(at, false, this.drone);
    }

    /**
     * Load up from a station's own store, taking only what the job is about to need.
     *
     * @param wanted the items worth carrying, from {@code BuildPilot}. An empty set means take nothing,
     *      which is the right answer for a demolition, not a bug
     * @return how many items were taken
     */
    public int loadFromStation(ServerLevel level, Vec3 station, Set<Item> wanted) {
        DronePadBlockEntity pad = padAt(level, station);
        if (pad == null || wanted.isEmpty()) {
            return 0;
        }
        int taken = 0;
        NonNullList<ItemStack> store = pad.cargoItems();
        for (int i = 0; i < store.size() && !this.cargoFull(); i++) {
            ItemStack stack = store.get(i);
            if (stack.isEmpty() || !wanted.contains(stack.getItem())) {
                continue;
            }
            ItemStack moving = stack.copy();
            int left = this.insert(moving);
            taken += stack.getCount() - left;
            stack.setCount(left);
            if (left == 0) {
                store.set(i, ItemStack.EMPTY);
            }
        }
        if (taken > 0) {
            pad.setChanged();
            this.setCrate(true);
        }
        return taken;
    }

    /**
     * Hand everything in the hold over to a station.
     *
     * @return how many items were handed over. Anything that does not fit stays aboard, so a full depot
     *      means the drone comes back still loaded rather than the material vanishing
     */
    public int unloadToStation(ServerLevel level, Vec3 station) {
        DronePadBlockEntity pad = padAt(level, station);
        if (pad == null) {
            return 0;
        }
        NonNullList<ItemStack> store = pad.cargoItems();
        int given = 0;
        for (int i = 0; i < this.cargo.size(); i++) {
            ItemStack stack = this.cargo.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            int left = insertInto(store, stack.copy());
            given += before - left;
            if (left == 0) {
                this.cargo.set(i, ItemStack.EMPTY);
            } else {
                stack.setCount(left);
            }
        }
        if (given > 0) {
            pad.setChanged();
        }
        if (this.cargoEmpty()) {
            this.setCrate(false);
        }
        return given;
    }

    private boolean cargoEmpty() {
        for (ItemStack stack : this.cargo) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Spend one of {@code item} from the hold.
     */
    private boolean takeItem(Item item) {
        for (int i = 0; i < this.cargo.size(); i++) {
            ItemStack stack = this.cargo.get(i);
            if (stack.is(item) && !stack.isEmpty()) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    this.cargo.set(i, ItemStack.EMPTY);
                }
                if (this.cargoEmpty()) {
                    this.setCrate(false);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Put items in the hold, spilling on the ground whatever will not fit.
     */
    private void giveItem(ItemStack stack) {
        int left = this.insert(stack);
        if (left > 0) {
            ItemStack overflow = stack.copy();
            overflow.setCount(left);
            Containers.dropItemStack(this.drone.level(), this.drone.getX(), this.drone.getY(), this.drone.getZ(),
                    overflow);
        } else {
            this.setCrate(true);
        }
    }

    /**
     * @return how much of {@code stack} would not fit.
     */
    private int insert(ItemStack stack) {
        return insertInto(this.cargo, stack);
    }

    private static int insertInto(NonNullList<ItemStack> into, ItemStack stack) {
        int left = stack.getCount();
        for (int i = 0; i < into.size() && left > 0; i++) {
            ItemStack slot = into.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) {
                continue;
            }
            int moved = Math.min(slot.getMaxStackSize() - slot.getCount(), left);
            slot.grow(moved);
            left -= moved;
        }
        for (int i = 0; i < into.size() && left > 0; i++) {
            if (!into.get(i).isEmpty()) {
                continue;
            }
            ItemStack fresh = stack.copy();
            int moved = Math.min(fresh.getMaxStackSize(), left);
            fresh.setCount(moved);
            into.set(i, fresh);
            left -= moved;
        }
        return left;
    }

    /**
     * @return the pad under this station point, or null if there is not one: the pad was broken, or the
     *      chunk is not loaded and nothing can be moved into it anyway
     */
    @Nullable
    private static DronePadBlockEntity padAt(ServerLevel level, Vec3 station) {
        BlockPos below = BlockPos.containing(station).below();
        if (!level.isLoaded(below)) {
            return null;
        }
        return level.getBlockEntity(below) instanceof DronePadBlockEntity pad ? pad : null;
    }

    /**
     * @return the cargo as a tag, for handing across an offload to the drone sim.
     */
    public CompoundTag saveCargo() {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, this.cargo, this.drone.registryAccess());
        return tag;
    }

    /** Open the crate's contents for a player. */
    public void openCargo(Player player) {
        Container container = new SimpleContainer(this.cargo.toArray(new ItemStack[0])) {
            @Override
            public void setChanged() {
                for (int i = 0; i < DroneHold.this.cargo.size(); i++) {
                    DroneHold.this.cargo.set(i, this.getItem(i));
                }
            }
        };
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, container),
                Component.translatable("entity.wflib.crate")));
    }

    private void sync() {
        byte flags = 0;
        if (this.hasCrate) {
            flags |= DroneEntity.LOAD_CRATE;
        }
        if (this.payloadId != null) {
            flags |= DroneEntity.LOAD_PAYLOAD;
        }
        if (this.hasMines()) {
            flags |= DroneEntity.LOAD_MINES;
        }
        this.drone.getEntityData().set(DroneEntity.LOAD, flags);

        MinePreset preset = this.mines == null ? null : MinePresetRegistry.get(this.mines.preset());
        this.drone.getEntityData().set(DroneEntity.LOAD_ID, preset == null ? "" : preset.modelId()
                .toString());
        this.drone.getEntityData().set(DroneEntity.LOAD_COUNT, (byte) (this.mines == null
                ? 0 : Math.min(Byte.MAX_VALUE, this.mines.remaining())));
    }

    /**
     * @return the model id of the mine on the rack, or null when there is no rack: as the client sees it.
     *      The counterpart of {@link #hasCrateAboard} for a minelayer.
     */
    @Nullable
    public ResourceLocation slungMineModel() {
        String raw = this.drone.getEntityData().get(DroneEntity.LOAD_ID);
        return raw.isEmpty() ? null : ResourceLocation.tryParse(raw);
    }

    /** @return how many mines are still on the rack, as the client sees it. */
    public int slungMineCount() {
        return Math.max(0, this.drone.getEntityData().get(DroneEntity.LOAD_COUNT));
    }

    /** @return true if a warhead is slung underneath, as the client sees it. */
    public boolean hasPayloadAboard() {
        return (this.drone.getEntityData().get(DroneEntity.LOAD) & DroneEntity.LOAD_PAYLOAD) != 0;
    }

    public boolean hasPayload() {
        return this.payloadId != null;
    }

    @Nullable
    public ResourceLocation getPayloadId() {
        return this.payloadId;
    }

    public void setPayload(@Nullable ResourceLocation payloadId) {
        this.payloadId = payloadId;
        this.sync();
    }

    /** @return true while there is a mine left on the rack. An empty rack is the same as no rack. */
    public boolean hasMines() {
        return this.mines != null && !this.mines.empty();
    }

    @Nullable
    public MineLoad getMines() {
        return this.mines;
    }

    /** Loads, replaces or clears the mine rack. An emptied rack is dropped outright so nothing carries 0/8. */
    public void setMines(@Nullable MineLoad mines) {
        this.mines = mines == null || mines.empty() ? null : mines;
        this.sync();
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (this.hasCrate) {
            tag.put("Cargo", this.saveCargo());
        }
        if (this.payloadId != null) {
            tag.putString("Payload", this.payloadId.toString());
        }
        if (this.mines != null) {
            tag.put("Mines", this.mines.save());
        }
        return tag;
    }

    void load(CompoundTag tag) {
        if (tag.contains("Cargo")) {
            this.loadCargo(tag.getCompound("Cargo"));
        }
        this.setPayload(tag.contains("Payload") ? WarheadRegistry.parse(tag.getString("Payload")) : null);
        this.setMines(tag.contains("Mines") ? MineLoad.load(tag.getCompound("Mines")) : null);
    }
}
