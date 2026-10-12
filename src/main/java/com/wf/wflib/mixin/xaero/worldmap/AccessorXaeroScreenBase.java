package com.wf.wflib.mixin.xaero.worldmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.lib.client.gui.ScreenBase;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;

@Mixin(value = ScreenBase.class, remap = false)
public interface AccessorXaeroScreenBase {

    @Accessor("openDropdown")
    DropDownWidget wflib$openDropdown();
}
