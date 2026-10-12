package com.wf.wflib.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.wflib.ui.expr.ExprException;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.settings.KeyModifier;

/** Display name of a key mapping, rebuilt only when the binding changes. Render thread. */
final class KeyName {
    static final int MAX_LENGTH = 32;

    private final KeyMapping mapping;
    private InputConstants.Key key;
    private KeyModifier modifier;
    private String text = "";

    private KeyName(KeyMapping mapping) {
        this.mapping = mapping;
    }

    static KeyName of(String name) {
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping.getName().equals(name)) {
                return new KeyName(mapping);
            }
        }
        throw new ExprException("unknown key mapping `" + name + "`");
    }

    String get() {
        if (mapping.getKey() != key || mapping.getKeyModifier() != modifier) {
            key = mapping.getKey();
            modifier = mapping.getKeyModifier();
            text = mapping.getTranslatedKeyMessage().getString();
        }
        return text;
    }
}
