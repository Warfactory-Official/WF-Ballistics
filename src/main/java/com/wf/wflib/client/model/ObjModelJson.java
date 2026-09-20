package com.wf.wflib.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The two things a {@code neoforge:obj} model json says that a rig needs: which mesh it draws and which texture it
 * wears.
 */
public record ObjModelJson(ResourceLocation obj, ResourceLocation texture) {

    /**
     * @param modelId a model id as the registries hold it, e.g. {@code wflib:entity/missiles/v2}
     * @throws IOException if the json is missing, is not an obj model, or names no texture
     */
    public static ObjModelJson read(ResourceLocation modelId) throws IOException {
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(),
                "models/" + modelId.getPath() + ".json");

        JsonObject json;
        try (InputStream in = Minecraft.getInstance()
                .getResourceManager()
                .getResourceOrThrow(path)
                .open()) {
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("could not parse " + path, e);
        }

        if (!json.has("model")) {
            throw new IOException(path + " names no obj; is it a " + json.get("loader") + " model?");
        }

        return new ObjModelJson(ResourceLocation.parse(json.get("model")
                .getAsString()), texture(json, path));
    }

    /** The skin, expanded from the shorthand a model json uses into the full path of the png. */
    private static ResourceLocation texture(JsonObject json, ResourceLocation path) throws IOException {
        if (!json.has("textures")) {
            throw new IOException(path + " names no textures");
        }

        JsonObject textures = json.getAsJsonObject("textures");
        String chosen = null;
        for (Map.Entry<String, com.google.gson.JsonElement> entry : textures.entrySet()) {
            if (!"particle".equals(entry.getKey())) {
                chosen = entry.getValue()
                        .getAsString();
                break;
            }
        }
        if (chosen == null && textures.has("particle")) {
            chosen = textures.get("particle")
                    .getAsString();
        }
        if (chosen == null || chosen.startsWith("#")) {
            throw new IOException(path + " has no resolvable texture, only " + textures.keySet());
        }

        ResourceLocation sprite = ResourceLocation.parse(chosen);
        return ResourceLocation.fromNamespaceAndPath(sprite.getNamespace(),
                "textures/" + sprite.getPath() + ".png");
    }
}
