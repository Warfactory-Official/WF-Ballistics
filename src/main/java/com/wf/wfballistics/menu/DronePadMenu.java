package com.wf.wfballistics.menu;

import com.wf.wfballistics.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/**
 * Slotless menu for the drone pad's config screen, mirroring {@link MissileDispenserMenu}: it exists only to
 * carry the pad's position to the client so the screen can read its stored mission and address it in packets.
 * The pad's cargo is a separate chest menu (sneak-right-click).
 */
public class DronePadMenu extends AbstractContainerMenu {

    private final ContainerLevelAccess access;
    private final BlockPos pos;

    public DronePadMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, ContainerLevelAccess.NULL, buf.readBlockPos());
    }

    public DronePadMenu(int id, Inventory inv, ContainerLevelAccess access, BlockPos pos) {
        super(ModMenus.DRONE_PAD.get(), id);
        this.access = access;
        this.pos = pos;
    }

    public BlockPos pos() {
        return this.pos;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, ModBlocks.DRONE_PAD.get());
    }
}
