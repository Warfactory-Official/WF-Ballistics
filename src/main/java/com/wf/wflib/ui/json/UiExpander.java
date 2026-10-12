package com.wf.wflib.ui.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiLayout;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns layout JSON into a {@link WidgetDef} tree. Components expand in place (no runtime node); a
 * {@code $name} in a string is replaced by the parameter / constant text, parenthesised unless it is a
 * single token, so an argument always stays one operand ({@code $y + 8} never re-associates).
 * Replacement text is not rescanned: arguments resolve in the caller's scope.
 */
public final class UiExpander {
    static final int MAX_DEPTH = 32;
    private static final Pattern ATOM = Pattern.compile("[A-Za-z0-9_.#:]+");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.:/-]+");

    enum Kind {
        NUM, BOOL, COLOR, TEXT, KEY, WIDGET, WIDGETS
    }

    record Param(Kind kind, JsonElement fallback) {
    }

    record Component(ResourceLocation id, String file, List<ResourceLocation> uses, JsonObject constants,
                     Map<String, Param> params, JsonElement root) {
    }

    private sealed interface Value permits Text, One, Many {
    }

    private record Text(String text) implements Value {
    }

    private record One(WidgetDef def) implements Value {
    }

    private record Many(List<WidgetDef> defs) implements Value {
    }

    static final class Scope {
        private final Map<String, Value> values = new HashMap<>();
    }

    private final Map<ResourceLocation, Component> components;
    private final Map<ResourceLocation, JsonObject> palettes;
    private final Deque<ResourceLocation> expanding = new ArrayDeque<>();

    UiExpander(Map<ResourceLocation, Component> components, Map<ResourceLocation, JsonObject> palettes) {
        this.components = components;
        this.palettes = palettes;
    }

    // ------------------------------------------------------------------ files

    /** {@code {"scaling": "relative"|"absolute", "reference": 270, "use": [..], "constants": {..}, "root": {..}}} */
    UiLayout layout(ResourceLocation id, String file, JsonObject json) {
        allowOnly(file, json, "scaling", "reference", "use", "constants", "root");
        Scope scope = fileScope(file, uses(file, json), object(file, json, "constants"));
        String scaling = json.has("scaling") ? json.get("scaling").getAsString() : "relative";
        float reference = switch (scaling) {
            case "relative" -> json.has("reference") ? json.get("reference").getAsFloat() : UiLayout.DEFAULT_REFERENCE;
            case "absolute" -> {
                if (json.has("reference")) {
                    throw new UiJsonException(file + "/reference", "absolute layouts take no reference");
                }
                yield 0;
            }
            default -> throw new UiJsonException(file + "/scaling", "`" + scaling + "` is not relative or absolute");
        };
        if (!(reference >= 0)) {
            throw new UiJsonException(file + "/reference", "needs a positive number");
        }
        if (!json.has("root")) {
            throw new UiJsonException(file, "no `root`");
        }
        return new UiLayout(id, widget(json.get("root"), file + "/root", scope), reference);
    }

    /** {@code {"use": [..], "constants": {..}, "params": {"name": "num" | {"type": .., "default": ..}}, "root": {..}}} */
    static Component component(ResourceLocation id, String file, JsonObject json) {
        allowOnly(file, json, "use", "constants", "params", "root");
        Map<String, Param> params = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : object(file, json, "params").entrySet()) {
            String where = file + "/params/" + e.getKey();
            JsonElement spec = e.getValue();
            String type;
            JsonElement fallback = null;
            if (spec.isJsonPrimitive()) {
                type = spec.getAsString();
            } else if (spec.isJsonObject()) {
                JsonObject o = spec.getAsJsonObject();
                allowOnly(where, o, "type", "default");
                if (!o.has("type")) {
                    throw new UiJsonException(where, "no `type`");
                }
                type = o.get("type").getAsString();
                fallback = o.get("default");
            } else {
                throw new UiJsonException(where, "needs a type name or {type, default}");
            }
            Kind kind;
            try {
                kind = Kind.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new UiJsonException(where, "type `" + type + "` is not one of num, bool, color, text, key, widget, widgets");
            }
            params.put(e.getKey(), new Param(kind, fallback));
        }
        if (!json.has("root")) {
            throw new UiJsonException(file, "no `root`");
        }
        return new Component(id, file, uses(file, json), object(file, json, "constants"), params, json.get("root"));
    }

    private static List<ResourceLocation> uses(String file, JsonObject json) {
        List<ResourceLocation> out = new ArrayList<>();
        if (!json.has("use")) {
            return out;
        }
        if (!json.get("use").isJsonArray()) {
            throw new UiJsonException(file + "/use", "needs an array of palette ids");
        }
        for (JsonElement e : json.getAsJsonArray("use")) {
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id == null) {
                throw new UiJsonException(file + "/use", "bad id `" + e.getAsString() + "`");
            }
            out.add(id);
        }
        return out;
    }

    private static JsonObject object(String file, JsonObject json, String key) {
        if (!json.has(key)) {
            return new JsonObject();
        }
        if (!json.get(key).isJsonObject()) {
            throw new UiJsonException(file + "/" + key, "needs an object");
        }
        return json.getAsJsonObject(key);
    }

    private static void allowOnly(String where, JsonObject json, String... keys) {
        for (String key : json.keySet()) {
            boolean known = false;
            for (String k : keys) {
                known |= k.equals(key);
            }
            if (!known) {
                throw new UiJsonException(where + "/" + key, "unknown property");
            }
        }
    }

    /** Palettes in order, then the file's own constants; each may use the ones before it. */
    private Scope fileScope(String file, List<ResourceLocation> uses, JsonObject constants) {
        Scope scope = new Scope();
        for (ResourceLocation use : uses) {
            JsonObject palette = palettes.get(use);
            if (palette == null) {
                throw new UiJsonException(file + "/use", "unknown palette " + use);
            }
            constants(palette, "palette " + use, scope);
        }
        constants(constants, file + "/constants", scope);
        return scope;
    }

    private void constants(JsonObject constants, String where, Scope scope) {
        for (Map.Entry<String, JsonElement> e : constants.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                throw new UiJsonException(where + "/" + e.getKey(), "needs a string, number or boolean");
            }
            scope.values.put(e.getKey(), new Text(operand(substitute(e.getValue().getAsString(),
                    where + "/" + e.getKey(), scope))));
        }
    }

    // ------------------------------------------------------------------ widgets

    WidgetDef widget(JsonElement json, String path, Scope scope) {
        if (json.isJsonPrimitive()) {
            Value value = reference(json.getAsString(), path, scope);
            if (value instanceof One one) {
                return one.def;
            }
            throw new UiJsonException(path, "`" + json.getAsString() + "` is not a widget parameter");
        }
        if (!json.isJsonObject()) {
            throw new UiJsonException(path, "needs a widget object");
        }
        JsonObject object = json.getAsJsonObject();
        if (!object.has("type")) {
            throw new UiJsonException(path, "no `type`");
        }
        String typeName = substitute(object.get("type").getAsString(), path + "/type", scope);
        ResourceLocation type;
        try {
            type = UiWidgetTypes.id(typeName);
        } catch (RuntimeException e) {
            throw new UiJsonException(path + "/type", "bad type `" + typeName + "`");
        }
        WidgetReader reader = UiWidgetTypes.get(type);
        if (reader != null) {
            JsonProps props = new JsonProps(this, object, path, scope);
            WidgetDef def;
            try {
                def = reader.read(props);
            } catch (UiJsonException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new UiJsonException(path, e.getMessage(), e);
            }
            props.finish();
            return new Located(path, def);
        }
        Component component = components.get(type);
        if (component == null) {
            throw new UiJsonException(path + "/type", "unknown widget type or component " + type);
        }
        return call(component, object, path, scope);
    }

    List<WidgetDef> widgets(JsonElement json, String path, Scope scope) {
        if (json.isJsonPrimitive()) {
            Value value = reference(json.getAsString(), path, scope);
            if (value instanceof Many many) {
                return many.defs;
            }
            throw new UiJsonException(path, "`" + json.getAsString() + "` is not a widgets parameter");
        }
        if (!json.isJsonArray()) {
            throw new UiJsonException(path, "needs an array of widgets");
        }
        JsonArray array = json.getAsJsonArray();
        List<WidgetDef> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement e = array.get(i);
            if (e.isJsonPrimitive() && reference(e.getAsString(), path + "/" + i, scope) instanceof Many many) {
                out.addAll(many.defs);
            } else {
                out.add(widget(e, path + "/" + i, scope));
            }
        }
        return out;
    }

    private WidgetDef call(Component component, JsonObject args, String path, Scope caller) {
        if (expanding.contains(component.id)) {
            throw new UiJsonException(path, "component " + component.id + " includes itself");
        }
        if (expanding.size() >= MAX_DEPTH) {
            throw new UiJsonException(path, "components nested deeper than " + MAX_DEPTH);
        }
        for (String key : args.keySet()) {
            if (!key.equals("type") && !component.params.containsKey(key)) {
                throw new UiJsonException(path + "/" + key, "not a parameter of " + component.id);
            }
        }
        expanding.push(component.id);
        try {
            Scope scope = fileScope(component.file, component.uses, component.constants);
            for (Map.Entry<String, Param> e : component.params.entrySet()) {
                String name = e.getKey();
                Param param = e.getValue();
                JsonElement given = args.get(name);
                if (given != null) {
                    scope.values.put(name, argument(param.kind, given, path + "/" + name, caller));
                } else if (param.fallback != null) {
                    scope.values.put(name, argument(param.kind, param.fallback, component.file + "/params/" + name, scope));
                } else {
                    throw new UiJsonException(path, component.id + " needs `" + name + "`");
                }
            }
            return widget(component.root, component.file + "/root", scope);
        } catch (UiJsonException e) {
            throw new UiJsonException(path, "in " + component.id + ": " + e.getMessage(), e);
        } finally {
            expanding.pop();
        }
    }

    private Value argument(Kind kind, JsonElement json, String path, Scope scope) {
        return switch (kind) {
            case WIDGET -> new One(widget(json, path, scope));
            case WIDGETS -> new Many(widgets(json, path, scope));
            default -> {
                if (!json.isJsonPrimitive()) {
                    throw new UiJsonException(path, "needs a string, number or boolean");
                }
                String text = substitute(json.getAsString(), path, scope);
                if (kind == Kind.KEY && !KEY.matcher(text).matches()) {
                    throw new UiJsonException(path, "`" + text + "` is not a translation / resource key");
                }
                yield new Text(kind == Kind.TEXT || kind == Kind.KEY ? text : operand(text));
            }
        };
    }

    // ------------------------------------------------------------------ substitution

    private static String operand(String text) {
        return ATOM.matcher(text).matches() ? text : "(" + text + ")";
    }

    private Value reference(String text, String path, Scope scope) {
        if (!text.startsWith("$") || text.length() < 2 || !isNameStart(text.charAt(1))) {
            throw new UiJsonException(path, "needs a widget, got `" + text + "`");
        }
        int end = nameEnd(text, 1);
        if (end != text.length()) {
            throw new UiJsonException(path, "needs a widget, got `" + text + "`");
        }
        Value value = scope.values.get(text.substring(1));
        if (value == null) {
            throw new UiJsonException(path, "unknown `" + text + "`");
        }
        return value;
    }

    /** {@code $name} => text value; {@code $$} => {@code $}. */
    String substitute(String text, String path, Scope scope) {
        int at = text.indexOf('$');
        if (at < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int from = 0;
        while (at >= 0) {
            out.append(text, from, at);
            if (at + 1 < text.length() && text.charAt(at + 1) == '$') {
                out.append('$');
                from = at + 2;
            } else if (at + 1 < text.length() && isNameStart(text.charAt(at + 1))) {
                int end = nameEnd(text, at + 1);
                String name = text.substring(at + 1, end);
                Value value = scope.values.get(name);
                if (value == null) {
                    throw new UiJsonException(path, "unknown `$" + name + "` in `" + text + "`");
                }
                if (!(value instanceof Text t)) {
                    throw new UiJsonException(path, "`$" + name + "` is a widget, not text, in `" + text + "`");
                }
                out.append(t.text);
                from = end;
            } else {
                throw new UiJsonException(path, "stray `$` in `" + text + "` (`$$` for a literal)");
            }
            at = text.indexOf('$', from);
        }
        return out.append(text, from, text.length()).toString();
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static int nameEnd(String text, int from) {
        int end = from;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return end;
    }

    /** Prefixes bind errors with the JSON location; delegates otherwise, so it costs nothing per frame. */
    record Located(String path, WidgetDef def) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            try {
                return def.create(binder);
            } catch (UiJsonException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new UiJsonException(path, e.getMessage(), e);
            }
        }
    }
}
