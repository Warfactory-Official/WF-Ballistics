package com.wf.wfballistics.menu;

import com.wf.wfballistics.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;


public class MissileDispenserMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    private final BlockPos pos;


    public MissileDispenserMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv, ContainerLevelAccess.NULL, buf.readBlockPos());
    }


    public MissileDispenserMenu(int id, Inventory inv, ContainerLevelAccess access, BlockPos pos) {
        super(ModMenus.MISSILE_DISPENSER.get(), id);
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
        return stillValid(this.access, player, ModBlocks.MISSILE_DISPENSER.get());
    }
}
