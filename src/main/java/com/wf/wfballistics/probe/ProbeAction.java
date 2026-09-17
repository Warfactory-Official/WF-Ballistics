package com.wf.wfballistics.probe;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One thing you can do to the thing you are looking at.
 *
 * @param arg a number the handler is given back, so one id can cover a family (which skin, which
 *      side, which channel), without an id per member
 * @param note why it is unavailable, shown beside a disabled row; null when enabled
 */
public record ProbeAction(ResourceLocation id, int arg, Component label, @Nullable ProbeElement icon,
                          boolean enabled, @Nullable Component note) {

    public static ProbeAction of(ResourceLocation id, Component label) {
        return new ProbeAction(id, 0, label, null, true, null);
    }

    public static ProbeAction of(ResourceLocation id, int arg, Component label) {
        return new ProbeAction(id, arg, label, null, true, null);
    }

    public ProbeAction withIcon(ProbeElement element) {
        return new ProbeAction(id, arg, label, element, enabled, note);
    }

    /** Shown, but greyed and not performable. Say why: a row that is simply missing teaches nothing. */
    public ProbeAction unavailable(Component why) {
        return new ProbeAction(id, arg, label, icon, false, why);
    }

    /** Whether this row is the same action as {@code other}, ignoring how it currently reads. */
    public boolean sameAs(@Nullable ProbeAction other) {
        return other != null && other.id.equals(id) && other.arg == arg;
    }
}
