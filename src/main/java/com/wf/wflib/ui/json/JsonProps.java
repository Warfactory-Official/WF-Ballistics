package com.wf.wflib.ui.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.expr.ExprException;
import com.wf.wflib.ui.expr.ExprParser;
import com.wf.wflib.ui.expr.ExprType;
import com.wf.wflib.ui.expr.Node;
import com.wf.wflib.ui.expr.Symbols;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One widget object being read. Strings pass through {@code $name} substitution (params, constants);
 * expression and template properties stay source text for the binder. Constant properties
 * ({@link #number}, {@link #flag}) take a JSON literal or a constant expression ({@code "$gap * 2"}).
 */
public final class JsonProps {
    private static final Symbols NO_SYMBOLS = new Symbols() {
        @Override
        public Node resolve(String name) {
            return null;
        }

        @Override
        public int allocateState(int count, String function) {
            throw new ExprException(function + "(..) in a constant");
        }
    };

    private final UiExpander expander;
    private final JsonObject json;
    private final String path;
    private final UiExpander.Scope scope;
    private final Set<String> read = new HashSet<>();

    JsonProps(UiExpander expander, JsonObject json, String path, UiExpander.Scope scope) {
        this.expander = expander;
        this.json = json;
        this.path = path;
        this.scope = scope;
        read.add("type");
    }

    public String path() {
        return path;
    }

    public UiJsonException error(String key, String message) {
        return new UiJsonException(path + "/" + key, message);
    }

    public boolean has(String key) {
        return json.has(key);
    }

    /** Expression source (number, boolean or colour); JSON numbers and booleans are accepted as literals. */
    public String expr(String key, String fallback) {
        JsonPrimitive value = primitive(key);
        return value == null ? fallback : expander.substitute(value.getAsString(), path + "/" + key, scope);
    }

    public String expr(String key) {
        return required(key, expr(key, null));
    }

    /** Text template ({@code {..}} parts). */
    public String template(String key, String fallback) {
        return expr(key, fallback);
    }

    public String template(String key) {
        return required(key, template(key, null));
    }

    public float number(String key, float fallback) {
        JsonPrimitive value = primitive(key);
        if (value == null) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.getAsFloat();
        }
        return (float) constant(key, value.getAsString(), ExprType.NUM);
    }

    public float number(String key) {
        if (!json.has(key)) {
            throw error(key, "required");
        }
        return number(key, 0);
    }

    public int integer(String key, int fallback) {
        float value = number(key, fallback);
        if (value != Math.rint(value)) {
            throw error(key, "needs a whole number, got " + value);
        }
        return (int) value;
    }

    public boolean flag(String key, boolean fallback) {
        JsonPrimitive value = primitive(key);
        if (value == null) {
            return fallback;
        }
        if (value.isBoolean()) {
            return value.getAsBoolean();
        }
        return constant(key, value.getAsString(), ExprType.BOOL) != 0;
    }

    /** Lower-case constant name. */
    public <E extends Enum<E>> E choice(String key, Class<E> type, E fallback) {
        JsonPrimitive value = primitive(key);
        if (value == null) {
            return fallback;
        }
        String name = expander.substitute(value.getAsString(), path + "/" + key, scope);
        for (E e : type.getEnumConstants()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(name)) {
                return e;
            }
        }
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            names.add(e.name().toLowerCase(Locale.ROOT));
        }
        throw error(key, "`" + name + "` is not one of " + names);
    }

    public ResourceLocation id(String key) {
        String text = required(key, expr(key, null));
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null) {
            throw error(key, "bad id `" + text + "`");
        }
        return id;
    }

    public float[] numbers(String key, float[] fallback) {
        JsonElement value = take(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonArray()) {
            throw error(key, "needs an array of numbers");
        }
        JsonArray array = value.getAsJsonArray();
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            JsonElement e = array.get(i);
            if (!e.isJsonPrimitive()) {
                throw error(key + "/" + i, "needs a number");
            }
            out[i] = e.getAsJsonPrimitive().isNumber() ? e.getAsFloat()
                    : (float) constant(key + "/" + i, e.getAsString(), ExprType.NUM);
        }
        return out;
    }

    /** Object of string => string, values substituted. */
    public Map<String, String> strings(String key) {
        JsonElement value = take(key);
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null) {
            return out;
        }
        if (!value.isJsonObject()) {
            throw error(key, "needs an object");
        }
        for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                throw error(key + "/" + e.getKey(), "needs a string");
            }
            out.put(e.getKey(), expander.substitute(e.getValue().getAsString(), path + "/" + key + "/" + e.getKey(), scope));
        }
        return out;
    }

    public WidgetDef widget(String key) {
        WidgetDef def = widgetOrNull(key);
        if (def == null) {
            throw error(key, "required");
        }
        return def;
    }

    public WidgetDef widgetOrNull(String key) {
        JsonElement value = take(key);
        return value == null ? null : expander.widget(value, path + "/" + key, scope);
    }

    /** Array of widgets; {@code "$param"} entries of a widgets parameter are spliced in. */
    public List<WidgetDef> widgets(String key) {
        JsonElement value = take(key);
        if (value == null) {
            return List.of();
        }
        return expander.widgets(value, path + "/" + key, scope);
    }

    /** Array of plain objects (no {@code type}), each read by {@code read}. */
    public <T> List<T> objects(String key, Function<JsonProps, T> read) {
        JsonElement value = take(key);
        if (value == null) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw error(key, "needs an array of objects");
        }
        JsonArray array = value.getAsJsonArray();
        List<T> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                throw error(key + "/" + i, "needs an object");
            }
            JsonProps props = new JsonProps(expander, array.get(i).getAsJsonObject(), path + "/" + key + "/" + i, scope);
            out.add(read.apply(props));
            props.finish();
        }
        return out;
    }

    /** Unread keys => error (typos). */
    void finish() {
        for (String key : json.keySet()) {
            if (!read.contains(key)) {
                throw error(key, "unknown property");
            }
        }
    }

    private JsonElement take(String key) {
        read.add(key);
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value;
    }

    private JsonPrimitive primitive(String key) {
        JsonElement value = take(key);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive()) {
            throw error(key, "needs a string, number or boolean");
        }
        return value.getAsJsonPrimitive();
    }

    private <T> T required(String key, T value) {
        if (value == null) {
            throw error(key, "required");
        }
        return value;
    }

    private double constant(String key, String source, ExprType type) {
        String text = expander.substitute(source, path + "/" + key, scope);
        Node node;
        try {
            node = ExprParser.parse(text, NO_SYMBOLS, type);
        } catch (ExprException e) {
            throw error(key, e.getMessage());
        }
        if (!(node instanceof Node.Const c)) {
            throw error(key, "`" + text + "` must be constant here");
        }
        return c.value();
    }
}
