package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import com.wf.wfballistics.menu.MissileDispenserMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;


public class MissileDispenserBlockEntity extends BlockEntity implements MenuProvider {

    // Stable per-launcher identity, stamped onto every missile this dispenser fires as its "control id" so
    // its own missiles never collide with each other (friendly fire), while still hitting other launchers'.
    private UUID controlId;
    private LaunchConfig config;
    private boolean wasPowered;

    public MissileDispenserBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MISSILE_DISPENSER.get(), pos, state);
    }

    /**
     * @return this launcher's control id, generated (and persisted) on first use.
     */
    public UUID getControlId() {
        if (this.controlId == null) {
            this.controlId = UUID.randomUUID();
            this.setChanged();
        }
        return this.controlId;
    }

    @Nullable
    public LaunchConfig getConfig() {
        return this.config;
    }

    public void setConfig(LaunchConfig config) {
        this.config = config;
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public void initPowered(boolean powered) {
        this.wasPowered = powered;
        this.setChanged();
    }

    public void onRedstone(boolean powered) {
        if (powered && !this.wasPowered) {
            this.fireStored();
        }
        if (powered != this.wasPowered) {
            this.wasPowered = powered;
            this.setChanged();
        }
    }

    public void fireStored() {
        if (this.config != null && this.level instanceof ServerLevel sl) {
            this.config.spawn(sl, this.worldPosition, this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.controlId != null) {
            tag.putUUID("ControlId", this.controlId);
        }
        if (this.config != null) {
            CompoundTag c = new CompoundTag();
            this.config.save(c);
            tag.put("Config", c);
        }
        tag.putBoolean("WasPowered", this.wasPowered);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("ControlId")) {
            this.controlId = tag.getUUID("ControlId");
        }
        this.config = tag.contains("Config") ? LaunchConfig.load(tag.getCompound("Config")) : null;
        this.wasPowered = tag.getBoolean("WasPowered");
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        this.saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
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
