package com.wf.wflib.mixin.xaero.worldmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;

@Mixin(value = GuiMap.class, remap = false)
public interface AccessorGuiMap {

    @Accessor("mouseBlockPosX")
    int wflib$mouseBlockX();

    /** 32767 = unknown. */
    @Accessor("mouseBlockPosY")
    int wflib$mouseBlockY();

    @Accessor("mouseBlockPosZ")
    int wflib$mouseBlockZ();

    @Invoker("handleRightClick")
    void wflib$openMenu(IRightClickableElement target, int x, int y);
}
