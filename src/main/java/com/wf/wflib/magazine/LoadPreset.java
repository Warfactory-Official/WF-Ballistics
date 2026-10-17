package com.wf.wflib.magazine;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Round kind per position; position 1 = bottom = fired last. {@code mix == null} => all {@code primary}. */
public record LoadPreset(ResourceLocation primary, @Nullable Mix mix) {

    public static LoadPreset of(ResourceLocation primary) {
        return new LoadPreset(primary, null);
    }

    public ResourceLocation roundAt(int position) {
        return mix != null && mix.pattern().matches(position, mix.count()) ? mix.round() : primary;
    }

    public record Mix(ResourceLocation round, Pattern pattern, int count) {
        public Mix {
            if (count < 1) {
                throw new IllegalArgumentException("mix count " + count + " < 1");
            }
        }
    }

    public enum Pattern {
        EVERY_NTH {
            @Override
            boolean matches(int position, int n) {
                return (position - 1) % n == 0;
            }
        },
        LAST_N {
            @Override
            boolean matches(int position, int n) {
                return position <= n;
            }
        };

        abstract boolean matches(int position, int n);
    }
}
