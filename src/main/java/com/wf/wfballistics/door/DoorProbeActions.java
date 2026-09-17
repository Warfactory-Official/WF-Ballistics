package com.wf.wfballistics.door;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.probe.ProbeActions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** What the probe's door actions actually do. */
public final class DoorProbeActions {

    public static final ResourceLocation TOGGLE = id("door_toggle");
    public static final ResourceLocation FIT_LOCK = id("door_fit_lock");
    public static final ResourceLocation TAKE_LOCK = id("door_take_lock");
    public static final ResourceLocation SKIN = id("door_skin");

    private DoorProbeActions() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, path);
    }

    public static void register() {
        ProbeActions.registerBlock(TOGGLE, (player, level, pos, state, arg) -> {
            DoorBlockEntity door = door(level, pos, state);
            ItemStack key = keyFor(player, door);
            return door != null && !key.isEmpty() && door.toggleWithKey(player, key);
        });

        ProbeActions.registerBlock(FIT_LOCK, (player, level, pos, state, arg) -> {
            DoorBlockEntity door = door(level, pos, state);
            ItemStack lock = cutPadlock(player);
            if (door == null || lock.isEmpty() || door.locked()) {
                return false;
            }
            if (!door.applyLock(DoorKeyItem.pinsOf(lock), 0.1)) {
                return false;
            }
            level.playSound(null, pos, DoorSounds.MOTOR_STOP.get(), SoundSource.BLOCKS, 0.6f, 1.6f);
            lock.shrink(1);
            return true;
        });

        ProbeActions.registerBlock(TAKE_LOCK, (player, level, pos, state, arg) -> {
            DoorBlockEntity door = door(level, pos, state);
            if (door == null || !door.locked() || keyFor(player, door).isEmpty()) {
                return false;
            }
            int pins = door.removeLock();
            if (pins == 0) {
                return false;
            }
            ItemStack padlock = new ItemStack(ModDoors.DOOR_LOCK.get());
            DoorKeyItem.setPins(padlock, pins);
            if (!player.addItem(padlock)) {
                player.drop(padlock, false);
            }
            level.playSound(null, pos, DoorSounds.MOTOR_START.get(), SoundSource.BLOCKS, 0.6f, 1.8f);
            return true;
        });

        ProbeActions.registerBlock(SKIN, (player, level, pos, state, arg) -> {
            DoorBlockEntity door = door(level, pos, state);
            return door != null && !keyFor(player, door).isEmpty() && door.setSkin(arg);
        });
    }

    /** The door behind any of its blocks, or null if this is not one. */
    @Nullable
    public static DoorBlockEntity door(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock block)) {
            return null;
        }
        BlockPos core = DoorFrame.findCore(level, pos, block);
        return core != null && level.getBlockEntity(core) instanceof DoorBlockEntity door ? door : null;
    }

    /** The key in either hand that works this door, or an empty stack. */
    public static ItemStack keyFor(Player player, @Nullable DoorBlockEntity door) {
        if (door == null) {
            return ItemStack.EMPTY;
        }
        if (door.keyWorks(player.getMainHandItem())) {
            return player.getMainHandItem();
        }
        if (door.keyWorks(player.getOffhandItem())) {
            return player.getOffhandItem();
        }
        return ItemStack.EMPTY;
    }

    /** A padlock that has been cut, in either hand. */
    public static ItemStack cutPadlock(Player player) {
        for (ItemStack held : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()}) {
            if (held.getItem() instanceof DoorLockItem && DoorKeyItem.pinsOf(held) != 0) {
                return held;
            }
        }
        return ItemStack.EMPTY;
    }

    private static DoorBlockEntity door(ServerLevel level, BlockPos pos, BlockState state) {
        return door((BlockGetter) level, pos, state);
    }
}
