package com.wf.wfballistics.fire;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;

/**
 * Capability provider that bolts a {@link WFFireData} onto an entity and handles its (de)serialization.
 * One instance is created per entity in {@link FireHandler#attach}.
 */
public class WFFireProvider implements ICapabilitySerializable<CompoundTag> {

    private final WFFireData data = new WFFireData();
    private final LazyOptional<WFFireData> optional = LazyOptional.of(() -> data);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        return WFFire.CAPABILITY.orEmpty(cap, optional);
    }

    @Override
    public CompoundTag serializeNBT() {
        return data.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        data.deserializeNBT(nbt);
    }

    /** Releases the lazy optional when the host entity is invalidated, preventing capability leaks. */
    public void invalidate() {
        optional.invalidate();
    }
}
