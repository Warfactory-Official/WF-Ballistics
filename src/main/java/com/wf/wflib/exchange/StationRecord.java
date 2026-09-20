package com.wf.wflib.exchange;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** One registered station: its code, where it actually is, and who it will accept cargo from. */
public final class StationRecord {

    private final String code;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;
    /** Station codes this one will accept a handshake from. */
    private final Set<String> allowed = new LinkedHashSet<>();
    /** What this station is willing to do. */
    private final EnumSet<StationRole> roles = EnumSet.copyOf(StationKind.STATION.roles());
    private final long registeredAt;

    public StationRecord(String code, ResourceKey<Level> dimension, BlockPos pos, long registeredAt) {
        this.code = code;
        this.dimension = dimension;
        this.pos = pos;
        this.registeredAt = registeredAt;
    }

    public String code() {
        return code;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos pos() {
        return pos;
    }

    /**
     * @return the launch point, one block above the pad.
     */
    public Vec3 launchPoint() {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    public long registeredAt() {
        return registeredAt;
    }

    public Set<String> allowed() {
        return java.util.Collections.unmodifiableSet(allowed);
    }

    public boolean allows(String senderCode) {
        return senderCode != null && allowed.contains(senderCode);
    }

    public boolean allow(String senderCode) {
        return StationCode.valid(senderCode) && allowed.add(senderCode);
    }

    public boolean revoke(String senderCode) {
        return allowed.remove(senderCode);
    }

    // --- roles ---

    public Set<StationRole> roles() {
        return java.util.Collections.unmodifiableSet(this.roles);
    }

    public boolean has(StationRole role) {
        return this.roles.contains(role);
    }

    /**
     * @return true if anything changed
     */
    public boolean setRoles(Set<StationRole> wanted) {
        if (this.roles.equals(wanted)) {
            return false;
        }
        this.roles.clear();
        this.roles.addAll(wanted);
        return true;
    }

    public boolean setRole(StationRole role, boolean on) {
        return on ? this.roles.add(role) : this.roles.remove(role);
    }

    /**
     * @return this station's preset name, or a description of its roles if it does not match one.
     */
    public String kind() {
        return StationKind.describe(this.roles);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Code", code);
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Pos", pos.asLong());
        tag.putLong("RegisteredAt", registeredAt);
        ListTag list = new ListTag();
        for (String peer : allowed) {
            list.add(StringTag.valueOf(peer));
        }
        tag.put("Allowed", list);
        ListTag roleList = new ListTag();
        for (StationRole role : roles) {
            roleList.add(StringTag.valueOf(role.name()));
        }
        tag.put("Roles", roleList);
        return tag;
    }

    public static StationRecord load(CompoundTag tag) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("Dimension"));
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                dimensionId != null ? dimensionId : Level.OVERWORLD.location());
        StationRecord record = new StationRecord(tag.getString("Code"), dimension,
                BlockPos.of(tag.getLong("Pos")), tag.getLong("RegisteredAt"));
        ListTag list = tag.getList("Allowed", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            record.allowed.add(list.getString(i));
        }
        if (tag.contains("Roles", Tag.TAG_LIST)) {
            record.roles.clear();
            ListTag roleList = tag.getList("Roles", Tag.TAG_STRING);
            for (int i = 0; i < roleList.size(); i++) {
                try {
                    record.roles.add(StationRole.valueOf(roleList.getString(i)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return record;
    }
}
