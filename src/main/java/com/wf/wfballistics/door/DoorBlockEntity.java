package com.wf.wfballistics.door;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * The one block of a door that thinks: its state machine, the blocks it clears as it swings, its lock, and the
 * sounds it makes.
 */
public class DoorBlockEntity extends BlockEntity {

    private DoorState state = DoorState.CLOSED;
    private int openTicks;
    private byte skin;

    /** -1 is the one tick after the last powered neighbour went quiet; see {@link #updateRedstone}. */
    private int redstone;
    private final Set<BlockPos> poweredBy = new HashSet<>(4);

    private boolean locked;
    private int pins;
    private double pickChance = 0.1;

    public DoorBlockEntity(BlockPos pos, BlockState state) {
        super(ModDoors.DOOR.get(), pos, state);
    }

    public DoorType type() {
        return getBlockState().getBlock() instanceof DoorBlock door ? door.type() : DoorType.QE_SLIDING_DOOR;
    }

    public DoorState state() {
        return state;
    }

    public int openTicks() {
        return openTicks;
    }

    public int skin() {
        return skin;
    }

    public boolean locked() {
        return locked;
    }

    public int pins() {
        return pins;
    }

    /** True while the door is fully shut, which is the only time its blocks collide as solid. */
    public boolean shut() {
        return state == DoorState.CLOSED;
    }

    /** How far open the door is, 0 to 1, interpolated for drawing. */
    public float openFraction(float partialTick) {
        float ticks = openTicks;
        if (state == DoorState.OPENING) {
            ticks = Math.min(ticks + partialTick, type().timeToOpen());
        } else if (state == DoorState.CLOSING) {
            ticks = Math.max(ticks - partialTick, 0.0f);
        }
        return ticks / type().timeToOpen();
    }

    // --- ticking ---------------------------------------------------------------------------------

    public static void tick(Level level, BlockPos pos, BlockState state, DoorBlockEntity door) {
        door.tick(level);
    }

    private void tick(Level level) {
        DoorType type = type();

        if (state == DoorState.OPENING) {
            openTicks = Math.min(openTicks + 1, type.timeToOpen());
        } else if (state == DoorState.CLOSING) {
            openTicks = Math.max(openTicks - 1, 0);
        }

        type.onTick(this, level);

        if (level.isClientSide) {
            return;
        }

        if (state.moving()) {
            sweep(level, type);
        }

        if (state == DoorState.OPENING && openTicks == type.timeToOpen()) {
            setState(DoorState.OPEN);
        } else if (state == DoorState.CLOSING && openTicks == 0) {
            setState(DoorState.CLOSED);
        }

        if (redstone == -1 && state == DoorState.OPEN) {
            toggle();
        } else if (redstone > 0 && state == DoorState.CLOSED) {
            toggle();
        }
        if (redstone == -1) {
            redstone = 0;
        }
    }

    /** Turns placeholder blocks the door has swung past into passable ones and back. */
    private void sweep(Level level, DoorType type) {
        Direction facing = getBlockState().getValue(DoorBlock.FACING);
        Rotation rotation = DoorFrame.facingRotation(facing);
        if (facing == Direction.EAST || facing == Direction.WEST) {
            rotation = rotation.getRotated(Rotation.CLOCKWISE_180);
        }
        Block block = getBlockState().getBlock();
        boolean opening = state == DoorState.OPENING;

        int[][] ranges = type.openRanges();
        for (int i = 0; i < ranges.length; i++) {
            int[] range = ranges[i];
            float time = type.rangeOpenTime(openTicks, i);
            int run = Math.abs(range[3]);
            int sign = Integer.signum(range[3]);
            float span = Math.abs(range[3] - 1);

            for (int step = 0; step < run; step++) {
                int j = opening ? step : run - 1 - step;
                float at = j / span;
                if (opening ? at > time : at < time) {
                    break;
                }
                for (int k = 0; k < range[4]; k++) {
                    BlockPos offset = switch (range[5]) {
                        case 0 -> new BlockPos(0, k, sign * j);
                        case 1 -> new BlockPos(k, sign * j, 0);
                        default -> new BlockPos(sign * j, k, 0);
                    };
                    BlockPos target = DoorFrame
                            .rotate(new BlockPos(range[0] + offset.getX(), range[1] + offset.getY(),
                                    range[2] + offset.getZ()), rotation)
                            .offset(worldPosition);
                    if (target.equals(worldPosition)) {
                        continue;
                    }
                    setRole(level, target, block, opening ? DoorRole.OPEN : DoorRole.DUMMY);
                }
            }
        }
    }

    private static void setRole(Level level, BlockPos pos, Block door, DoorRole role) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(door) || state.getValue(DoorBlock.ROLE) == DoorRole.CORE
                || state.getValue(DoorBlock.ROLE) == role) {
            return;
        }
        DoorFrame.structural(() ->
                level.setBlock(pos, state.setValue(DoorBlock.ROLE, role), Block.UPDATE_ALL));
    }

    // --- opening ---------------------------------------------------------------------------------

    /** Starts the door moving whichever way it is not already going. Ignores the lock. */
    public boolean toggle() {
        if (state == DoorState.CLOSED) {
            setState(DoorState.OPENING);
            return true;
        }
        if (state == DoorState.OPEN) {
            setState(DoorState.CLOSING);
            return true;
        }
        return false;
    }

    /** A player with a key. */
    public boolean toggleWithKey(Player player, ItemStack key) {
        if (locked && !DoorKeyItem.opens(key, pins)) {
            return false;
        }
        if (redstone > 0) {
            return false;
        }
        return toggle();
    }

    public void open() {
        if (state == DoorState.CLOSED) {
            setState(DoorState.OPENING);
        }
    }

    public void close() {
        if (state == DoorState.OPEN) {
            setState(DoorState.CLOSING);
        }
    }

    private void setState(DoorState next) {
        if (state == next) {
            return;
        }
        state = next;
        setChanged();
        if (level != null && !level.isClientSide) {
            playTransition(level, next);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private void playTransition(Level level, DoorState next) {
        DoorSounds.Set set = type().sounds();
        DeferredHolder<SoundEvent, SoundEvent> sound = switch (next) {
            case OPENING -> set.startOpen();
            case CLOSING -> set.startClose();
            case OPEN -> set.endOpen();
            case CLOSED -> set.endClose();
        };
        play(level, sound, 1.0f);
    }

    /** Plays a one-shot at the door, at the type's volume. Server side, so everyone nearby hears it. */
    public void play(Level level, @Nullable DeferredHolder<SoundEvent, SoundEvent> sound, float pitch) {
        if (sound == null || level.isClientSide) {
            return;
        }
        level.playSound(null, worldPosition, sound.get(), SoundSource.BLOCKS, type().soundVolume(), pitch);
    }

    // --- redstone --------------------------------------------------------------------------------

    /**
     * Counts how many blocks of the door are being powered, so a door wired at one corner is not un-powered by an
     * unrelated block update at another.
     */
    public void updateRedstone(BlockPos pos) {
        if (level == null) {
            return;
        }
        boolean powered = level.hasNeighborSignal(pos);
        boolean known = poweredBy.contains(pos);
        if (powered && !known) {
            poweredBy.add(pos);
            if (redstone == -1) {
                redstone = 0;
            }
            redstone++;
            setChanged();
        } else if (!powered && known) {
            poweredBy.remove(pos);
            redstone--;
            if (redstone == 0) {
                redstone = -1;
            }
            setChanged();
        }
    }

    // --- items -----------------------------------------------------------------------------------

    void onPlaced(ItemStack stack) {
        skin = (byte) Math.floorMod(DoorItem.skinOf(stack), Math.max(1, type().skins()));
        setChanged();
    }

    ItemInteractionResult useItem(ItemStack stack, Player player, net.minecraft.world.InteractionHand hand) {
        if (level == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof DoorLockItem lock) {
            return lock.applyTo(this, stack, player, level);
        }
        // A key is handled by the key, not here: see DoorKeyItem#useOn.
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    boolean cycleSkin(ItemStack key) {
        if (type().skins() <= 1 || (locked && !DoorKeyItem.opens(key, pins))) {
            return false;
        }
        if (level != null && !level.isClientSide) {
            skin = (byte) ((skin + 1) % type().skins());
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        return true;
    }

    /** Whether {@code stack} works this door: any key on an unlocked one, the right key on a locked one. */
    public boolean keyWorks(ItemStack stack) {
        if (!(stack.getItem() instanceof DoorKeyItem)) {
            return false;
        }
        return !locked || DoorKeyItem.opens(stack, pins);
    }

    /** Whether redstone is holding the door where it is, which a key cannot override. */
    public boolean heldByRedstone() {
        return redstone > 0;
    }

    /** Takes the lock off and hands back the pattern it was cut to, so the padlock can be re-fitted elsewhere. */
    public int removeLock() {
        if (!locked) {
            return 0;
        }
        int had = pins;
        locked = false;
        pins = 0;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        return had;
    }

    /** Sets the skin outright, which is what the probe's per-skin actions do. */
    public boolean setSkin(int wanted) {
        int count = Math.max(1, type().skins());
        int next = Math.floorMod(wanted, count);
        if (next == skin) {
            return false;
        }
        skin = (byte) next;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        return true;
    }

    /** Binds this door to a lock's pin pattern. Fails on a door that already has one. */
    public boolean applyLock(int newPins, double mod) {
        if (locked || newPins == 0) {
            return false;
        }
        locked = true;
        pins = newPins;
        pickChance = mod;
        setChanged();
        return true;
    }

    public double pickChance() {
        return pickChance;
    }

    /** How many of the door's blocks are being powered. Not synced; see {@link DoorProbeData}. */
    public int poweredBlocks() {
        return poweredBy.size();
    }

    // --- persistence -----------------------------------------------------------------------------

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        state = DoorState.of(tag.getInt("State"));
        openTicks = tag.getInt("OpenTicks");
        skin = tag.getByte("Skin");
        redstone = tag.getInt("Redstone");
        locked = tag.getBoolean("Locked");
        pins = tag.getInt("Pins");
        pickChance = tag.contains("PickChance") ? tag.getDouble("PickChance") : 0.1;
        poweredBy.clear();
        ListTag list = tag.getList("PoweredBy", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            poweredBy.add(new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z")));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("State", state.ordinal());
        tag.putInt("OpenTicks", openTicks);
        tag.putByte("Skin", skin);
        tag.putInt("Redstone", redstone);
        tag.putBoolean("Locked", locked);
        tag.putInt("Pins", pins);
        tag.putDouble("PickChance", pickChance);
        ListTag list = new ListTag();
        for (BlockPos pos : poweredBy) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", pos.getX());
            entry.putInt("y", pos.getY());
            entry.putInt("z", pos.getZ());
            list.add(entry);
        }
        tag.put("PoweredBy", list);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("State", state.ordinal());
        tag.putInt("OpenTicks", openTicks);
        tag.putByte("Skin", skin);
        tag.putBoolean("Locked", locked);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Applies the packet and hands the client the transition it implies, which is what starts and stops a door's
     * motor.
     */
    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        DoorState previous = state;
        super.onDataPacket(net, packet, registries);
        if (previous != state && level != null && level.isClientSide) {
            com.wf.wfballistics.door.client.DoorLoops.onStateChanged(this, previous, state);
        }
    }
}
