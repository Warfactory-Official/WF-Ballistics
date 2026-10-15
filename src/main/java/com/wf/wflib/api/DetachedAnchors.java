package com.wf.wflib.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** {@link DetachedBodyHost} anchors in one synced compound: operator UUID -> parked body position. */
public final class DetachedAnchors {

    private final Entity host;
    private final EntityDataAccessor<CompoundTag> data;

    public DetachedAnchors(Entity host, EntityDataAccessor<CompoundTag> data) {
        this.host = host;
        this.data = data;
    }

    public boolean isEmpty() {
        return this.host.getEntityData().get(this.data).isEmpty();
    }

    @Nullable
    public Vec3 get(Entity operator) {
        ListTag anchor = this.host.getEntityData().get(this.data).getList(operator.getStringUUID(), Tag.TAG_DOUBLE);
        return anchor.size() == 3 ? new Vec3(anchor.getDouble(0), anchor.getDouble(1), anchor.getDouble(2)) : null;
    }

    /** Server only; null removes. */
    public void set(Entity operator, @Nullable Vec3 anchor) {
        if (this.host.level().isClientSide()) {
            return;
        }
        CompoundTag anchors = this.host.getEntityData().get(this.data).copy();
        if (anchor == null) {
            anchors.remove(operator.getStringUUID());
        } else {
            ListTag list = new ListTag();
            list.add(DoubleTag.valueOf(anchor.x));
            list.add(DoubleTag.valueOf(anchor.y));
            list.add(DoubleTag.valueOf(anchor.z));
            anchors.put(operator.getStringUUID(), list);
        }
        this.host.getEntityData().set(this.data, anchors);
    }

    public void clear() {
        if (!this.host.level().isClientSide()) {
            this.host.getEntityData().set(this.data, new CompoundTag());
        }
    }

    /** Passengers holding an anchor. */
    public Collection<Entity> operators() {
        CompoundTag anchors = this.host.getEntityData().get(this.data);
        if (anchors.isEmpty()) {
            return List.of();
        }
        List<Entity> out = new ArrayList<>();
        for (Entity passenger : this.host.getPassengers()) {
            if (anchors.contains(passenger.getStringUUID(), Tag.TAG_LIST)) {
                out.add(passenger);
            }
        }
        return out;
    }

    /** Dismount target: the anchor (consumed), else null. */
    @Nullable
    public Vec3 release(Entity operator) {
        Vec3 anchor = this.get(operator);
        if (anchor != null) {
            this.set(operator, null);
        }
        return anchor;
    }
}
