package com.wf.wflib.round.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wf.wflib.WFLib;
import com.wf.wflib.kinetic.KineticPreset;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effect registry: id -> {@link Factory}, bound per preset with trigger, chance and params
 * ({@code KineticPreset.Builder.effect}). Register during mod construction (any thread), before presets naming the
 * id are built. Built-ins: {@link BuiltinEffects}.
 */
public final class ImpactEffects {

    private static final Map<ResourceLocation, Factory> FACTORIES = new ConcurrentHashMap<>();
    public static final ResourceLocation IGNITE = rl("ignite");
    public static final ResourceLocation DAMAGE = rl("damage");
    public static final ResourceLocation EXPLODE = rl("explode");
    public static final ResourceLocation THERMAL = rl("thermal");

    static {
        BuiltinEffects.register();
    }

    private ImpactEffects() {
    }

    /** Params -> effect; bad params => {@link IllegalArgumentException}. */
    @FunctionalInterface
    public interface Factory {
        ImpactEffect create(ImpactTrigger on, Params params);
    }

    /** A preset's effect: {@code chance} rolled per trigger, >= 1 never rolled. */
    public record Bound(ResourceLocation id, ImpactTrigger on, float chance, ImpactEffect effect) {
    }

    /** Parameterless: any params refused. */
    public static void register(ResourceLocation id, ImpactEffect effect) {
        registerFactory(id, (on, params) -> effect);
    }

    public static void registerFactory(ResourceLocation id, Factory factory) {
        if (FACTORIES.putIfAbsent(id, factory) != null) {
            throw new IllegalStateException("impact effect " + id + " registered twice");
        }
    }

    public static boolean exists(ResourceLocation id) {
        return FACTORIES.containsKey(id);
    }

    /**
     * @param params the effect's own keys ({@code id}/{@code on}/{@code chance} excluded); every key must be read
     * @throws IllegalArgumentException unknown id, chance outside (0, 1], bad or unread params
     */
    public static Bound bind(ResourceLocation id, ImpactTrigger on, double chance, JsonObject params) {
        Factory f = FACTORIES.get(id);
        if (f == null) {
            throw new IllegalArgumentException("unknown impact effect " + id);
        }
        if (!(chance > 0.0 && chance <= 1.0)) {
            throw new IllegalArgumentException(id + ": chance " + chance + " outside (0, 1]");
        }
        Params p = new Params(id, params);
        ImpactEffect effect;
        try {
            effect = f.create(on, p);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": " + e.getMessage(), e);
        }
        p.end();
        return new Bound(id, on, (float) chance, effect);
    }

    /** Effects of {@code preset} on {@code on} that fire this time. */
    public static List<ImpactEffect> roll(KineticPreset preset, ImpactTrigger on, RandomSource random) {
        ImpactEffect one = null;
        List<ImpactEffect> many = null;
        for (Bound b : preset.effects()) {
            if (b.on != on || b.chance < 1.0f && random.nextFloat() >= b.chance) {
                continue;
            }
            if (many != null) {
                many.add(b.effect);
            } else if (one == null) {
                one = b.effect;
            } else {
                many = new ArrayList<>(4);
                many.add(one);
                many.add(b.effect);
            }
        }
        return many != null ? many : one != null ? List.of(one) : List.of();
    }

    /** Every bound effect's {@link ImpactEffect#check}; all failures in one {@link IllegalStateException}. */
    public static void check(Collection<KineticPreset> presets, RegistryAccess registries) {
        List<String> bad = new ArrayList<>();
        for (KineticPreset preset : presets) {
            for (Bound b : preset.effects()) {
                try {
                    b.effect.check(registries);
                } catch (IllegalStateException e) {
                    bad.add(preset.id() + " " + b.on.key + " " + e.getMessage());
                }
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalStateException("impact effects: " + String.join("; ", bad));
        }
    }

    /** Rolls and applies {@code ctx.trigger()}'s effects. */
    public static void fire(ImpactContext ctx) {
        for (ImpactEffect e : roll(ctx.preset(), ctx.trigger(), ctx.level().random)) {
            e.apply(ctx);
        }
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    /** An effect's params; {@link #end} refuses keys no getter read. */
    public static final class Params {
        private final ResourceLocation id;
        private final JsonObject json;
        private final Set<String> read = new HashSet<>();

        Params(ResourceLocation id, JsonObject json) {
            this.id = id;
            this.json = json;
        }

        public double num(String key, double fallback, double min, double max) {
            JsonElement e = get(key);
            if (e == null) {
                return fallback;
            }
            double v = number(key, e);
            if (!(v >= min && v <= max)) {
                throw new IllegalArgumentException(key + " " + v + " outside [" + min + ", " + max + "]");
            }
            return v;
        }

        public double reqNum(String key, double min, double max) {
            require(key);
            return num(key, 0.0, min, max);
        }

        public boolean bool(String key, boolean fallback) {
            JsonElement e = get(key);
            if (e == null) {
                return fallback;
            }
            if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
                throw new IllegalArgumentException(key + ": expected bool, got " + e);
            }
            return p.getAsBoolean();
        }

        public ResourceLocation reqRl(String key) {
            require(key);
            JsonElement e = get(key);
            ResourceLocation rl = e instanceof JsonPrimitive p && p.isString() && p.getAsString().indexOf(':') > 0
                    ? ResourceLocation.tryParse(p.getAsString()) : null;
            if (rl == null) {
                throw new IllegalArgumentException(key + ": expected namespace:path, got " + e);
            }
            return rl;
        }

        private JsonElement get(String key) {
            read.add(key);
            return json.get(key);
        }

        private void require(String key) {
            if (!json.has(key)) {
                throw new IllegalArgumentException("missing '" + key + "'");
            }
        }

        private static double number(String key, JsonElement e) {
            if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
                throw new IllegalArgumentException(key + ": expected number, got " + e);
            }
            return p.getAsDouble();
        }

        void end() {
            for (String k : json.keySet()) {
                if (!read.contains(k)) {
                    throw new IllegalArgumentException(id + ": unknown param '" + k + "'");
                }
            }
        }
    }
}
