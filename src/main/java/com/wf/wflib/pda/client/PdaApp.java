package com.wf.wflib.pda.client;

import com.wf.wflib.ui.WidgetDef;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** One PDA app; register with {@link PdaApps#register} during client setup. */
public interface PdaApp {
    ResourceLocation id();

    Component title();

    /** Called once; drawn in the app area, {@link PdaShell#APP_WIDTH} x {@link PdaShell#APP_HEIGHT} units. */
    WidgetDef build();

    /** Shown on the home grid; re-read each client tick. */
    default boolean visible(LocalPlayer player) {
        return true;
    }

    /** Count on the home tile (alerts); 0 = none. */
    default int badge() {
        return 0;
    }

    /** True => right button goes to the screen (secondary) instead of lowering the PDA; Esc still lowers. */
    default boolean ownsSecondary() {
        return false;
    }

    default void opened() {
    }

    default void closed() {
    }
}
