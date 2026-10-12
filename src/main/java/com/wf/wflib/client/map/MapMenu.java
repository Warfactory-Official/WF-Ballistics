package com.wf.wflib.client.map;

import net.minecraft.core.BlockPos;

import java.util.function.Consumer;

/** A right-click menu on a fullscreen map; actions get the clicked block. */
public interface MapMenu {

    void item(String label, Consumer<BlockPos> action);

    MapMenu sub(String label);
}
