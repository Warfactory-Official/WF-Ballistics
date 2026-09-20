package com.wf.wflib.build;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Turns a {@code {Name, Properties}} palette entry into a {@link BlockState}, upgrading it on the way. */
final class PaletteReader {

    private final HolderGetter<Block> blocks;
    private final int dataVersion;
    private final int current;
    private int unresolved;

    PaletteReader(HolderGetter<Block> blocks, int dataVersion) {
        this.blocks = blocks;
        this.dataVersion = dataVersion;
        this.current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /**
     * @return the state this entry names, or air if this server has no such block.
     */
    BlockState read(CompoundTag entry) {
        CompoundTag fixed = this.upgrade(entry);
        ResourceLocation id = ResourceLocation.tryParse(fixed.getString("Name"));
        if (id == null || this.blocks.get(ResourceKey.create(Registries.BLOCK, id)).isEmpty()) {
            this.unresolved++;
            return Blocks.AIR.defaultBlockState();
        }
        return NbtUtils.readBlockState(this.blocks, fixed);
    }

    private CompoundTag upgrade(CompoundTag entry) {
        if (this.dataVersion <= 0 || this.dataVersion >= this.current) {
            return entry;
        }
        Dynamic<?> fixed = DataFixers.getDataFixer().update(References.BLOCK_STATE,
                new Dynamic<>(NbtOps.INSTANCE, entry), this.dataVersion, this.current);
        return fixed.getValue() instanceof CompoundTag tag ? tag : entry;
    }

    /**
     * @return how many entries named a block this server does not have.
     */
    int unresolved() {
        return this.unresolved;
    }
}
