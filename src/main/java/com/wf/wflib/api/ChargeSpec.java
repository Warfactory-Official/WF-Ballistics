package com.wf.wflib.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Hand-charge behaviour carried by a thrown grenade.
 *
 * @param fuseTicks  from spoon release
 * @param bounce     normal restitution 0..1
 * @param sticky     first contact attaches
 * @param impact     first contact detonates
 * @param size       warhead blast size; {@code <= 0} = warhead default
 * @param fragments  frag rays; {@code <= 0} = warhead default
 * @param throwSpeed blocks/tick at release
 */
public record ChargeSpec(ResourceLocation warhead, int fuseTicks, float bounce, boolean sticky, boolean impact,
                         float size, int fragments, float throwSpeed) {

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(warhead);
        buf.writeVarInt(fuseTicks);
        buf.writeFloat(bounce);
        buf.writeBoolean(sticky);
        buf.writeBoolean(impact);
        buf.writeFloat(size);
        buf.writeVarInt(fragments);
        buf.writeFloat(throwSpeed);
    }

    public static ChargeSpec read(RegistryFriendlyByteBuf buf) {
        return new ChargeSpec(buf.readResourceLocation(), buf.readVarInt(), buf.readFloat(), buf.readBoolean(),
                buf.readBoolean(), buf.readFloat(), buf.readVarInt(), buf.readFloat());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("warhead", warhead.toString());
        tag.putInt("fuse", fuseTicks);
        tag.putFloat("bounce", bounce);
        tag.putBoolean("sticky", sticky);
        tag.putBoolean("impact", impact);
        tag.putFloat("size", size);
        tag.putInt("fragments", fragments);
        tag.putFloat("speed", throwSpeed);
        return tag;
    }

    public static ChargeSpec load(CompoundTag tag) {
        return new ChargeSpec(ResourceLocation.parse(tag.getString("warhead")), tag.getInt("fuse"),
                tag.getFloat("bounce"), tag.getBoolean("sticky"), tag.getBoolean("impact"), tag.getFloat("size"),
                tag.getInt("fragments"), tag.getFloat("speed"));
    }
}
