package com.wf.wfballistics.drone;

import com.wf.wfballistics.ModEntities;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A cargo crate: falling after release, or sitting on the ground waiting to be opened. Its contents are a
 * real inventory, so a delivery actually moves items from one place to another.
 *
 * <p>There is deliberately no third case for "being carried". A crate under a drone used to be one of
 * these, positioned onto its carrier every tick, and it never quite kept up: the two entities' movement
 * reached the client on separate schedules and the crate juddered along behind. A drone now holds the cargo
 * as items and draws the crate itself, and one of these is created only at the moment the load becomes
 * independent of the drone: released, shot down, or spilled out of a wreck.
 */
public class CrateEntity extends Entity {

    public static final int ROWS = 3;
    public static final int SLOTS = ROWS * 9;
    /**
     * Edge length in blocks. One number for the hitbox, the cube the renderer draws, and the space a drone
     * leaves for it in its grippers: a crate that changed size when it was released would give away that
     * the carried one was never really there.
     */
    public static final float SIZE = 0.9f;

    private static final double GRAVITY = 0.04;
    private static final double TERMINAL_FALL = -1.2;
    private static final double DRAG = 0.98;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public CrateEntity(EntityType<? extends CrateEntity> type, Level level) {
        super(type, level);
    }

    public CrateEntity(Level level, Vec3 pos) {
        this(ModEntities.CRATE.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }

        if (this.onGround()) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        double fall = Math.max(TERMINAL_FALL, velocity.y - GRAVITY);
        this.setDeltaMovement(velocity.x * DRAG, fall, velocity.z * DRAG);
        this.move(MoverType.SELF, this.getDeltaMovement());
    }

    /**
     * Start falling. Called the tick the crate is spawned out of a drone's grippers: it is moved over the
     * aim point but keeps whatever height it was let go at, so it falls from the drone rather than
     * appearing on the ground under it.
     */
    public void release(Vec3 at) {
        this.setPos(at.x, Math.max(at.y, this.getY()), at.z);
        this.setDeltaMovement(this.getDeltaMovement().x * 0.2, 0.0, this.getDeltaMovement().z * 0.2);
        this.setOnGround(false);
    }

    public NonNullList<ItemStack> items() {
        return this.items;
    }

    public boolean isEmpty() {
        return this.items.stream().allMatch(ItemStack::isEmpty);
    }

    /**
     * Cargo only, without the entity around it. Used to carry contents across an offload to the drone sim,
     * where the crate entity itself stops existing.
     */
    public CompoundTag saveCargo() {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, this.items, this.registryAccess());
        return tag;
    }

    public void loadCargo(CompoundTag tag) {
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, this.registryAccess());
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        this.openCargo(player);
        return InteractionResult.CONSUME;
    }

    /**
     * Open this crate's contents for a player.
     */
    public void openCargo(Player player) {
        Container container = new SimpleContainer(this.items.toArray(new ItemStack[0])) {
            @Override
            public void setChanged() {
                for (int i = 0; i < CrateEntity.SLOTS; i++) {
                    CrateEntity.this.items.set(i, this.getItem(i));
                }
            }
        };
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> ChestMenu.threeRows(id, inventory, container),
                Component.translatable("entity.wfballistics.crate")));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        this.spill();
        this.discard();
        return true;
    }

    /**
     * Scatter the contents on the ground: used when the crate is destroyed.
     */
    private void spill() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                Containers.dropItemStack(this.level(), this.getX(), this.getY(), this.getZ(), stack);
            }
        }
        this.items.clear();
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, this.registryAccess());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ContainerHelper.saveAllItems(tag, this.items, this.registryAccess());
    }
}
