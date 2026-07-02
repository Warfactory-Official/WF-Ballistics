package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.menu.MissileDispenserMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;


public class MissileDispenserBlockEntity extends BlockEntity implements MenuProvider {

    // Stable per-launcher identity, stamped onto every missile this dispenser fires as its "control id" so
    // its own missiles never collide with each other (friendly fire), while still hitting other launchers'.
    private UUID controlId;

    public MissileDispenserBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MISSILE_DISPENSER.get(), pos, state);
    }

    /** @return this launcher's control id, generated (and persisted) on first use. */
    public UUID getControlId() {
        if (this.controlId == null) {
            this.controlId = UUID.randomUUID();
            this.setChanged();
        }
        return this.controlId;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("ControlId")) {
            this.controlId = tag.getUUID("ControlId");
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.wfballistics.missile_dispenser");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new MissileDispenserMenu(id, inv,
                ContainerLevelAccess.create(this.level, this.worldPosition), this.worldPosition);
    }
}
