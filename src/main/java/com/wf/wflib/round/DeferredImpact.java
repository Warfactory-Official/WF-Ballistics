package com.wf.wflib.round;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * World effect owed to an unloaded chunk: a virtual round's block impact ({@code landed == false}: impact event,
 * then land or warhead) or a resting round whose chunk unloaded ({@code landed}: rests again for {@code fuse}).
 */
public record DeferredImpact(boolean landed, ResourceLocation preset, Vec3 at, Vec3 velocity, BlockPos block,
                             Direction face, @Nullable UUID shooter, @Nullable UUID faction, int fuse, float burrow) {

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("landed", landed);
        tag.putString("preset", preset.toString());
        tag.putDouble("x", at.x);
        tag.putDouble("y", at.y);
        tag.putDouble("z", at.z);
        tag.putDouble("vx", velocity.x);
        tag.putDouble("vy", velocity.y);
        tag.putDouble("vz", velocity.z);
        tag.putLong("block", block.asLong());
        tag.putByte("face", (byte) face.get3DDataValue());
        if (shooter != null) {
            tag.putUUID("shooter", shooter);
        }
        if (faction != null) {
            tag.putUUID("faction", faction);
        }
        tag.putInt("fuse", fuse);
        tag.putFloat("burrow", burrow);
        return tag;
    }

    static DeferredImpact load(CompoundTag tag) {
        return new DeferredImpact(tag.getBoolean("landed"), ResourceLocation.parse(tag.getString("preset")),
                new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")),
                new Vec3(tag.getDouble("vx"), tag.getDouble("vy"), tag.getDouble("vz")),
                BlockPos.of(tag.getLong("block")), Direction.from3DDataValue(tag.getByte("face")),
                tag.hasUUID("shooter") ? tag.getUUID("shooter") : null,
                tag.hasUUID("faction") ? tag.getUUID("faction") : null, tag.getInt("fuse"), tag.getFloat("burrow"));
    }
}
