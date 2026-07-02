package com.wf.wfballistics.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the bounding-box size of a Wavefront {@code .obj} bundled in the mod jar by scanning its vertex
 * lines for the min/max X/Y/Z. Mc makes normal clientside data pipeline fail on serverside hence you need
 * to load it directly from the jar
 */
public final class ObjBounds {

    private ObjBounds() {
    }

    /**
     * Scans the obj at the given jar resource path for its axis-aligned bounds.
     */
    public static Bounds bounds(String jarResourcePath) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        boolean any = false;

        try (InputStream in = ObjBounds.class.getResourceAsStream(jarResourcePath)) {
            if (in == null) return Bounds.EMPTY;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                // Geometric vertices
                if (line.length() < 2 || line.charAt(0) != 'v' || line.charAt(1) != ' ') continue;
                String[] tok = line.trim().split("\\s+");
                if (tok.length < 4) continue;
                try {
                    double x = Double.parseDouble(tok[1]);
                    double y = Double.parseDouble(tok[2]);
                    double z = Double.parseDouble(tok[3]);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                    any = true;
                } catch (NumberFormatException ignored) {
                    // malformed vertex line - skip it
                }
            }
        } catch (IOException e) {
            return Bounds.EMPTY;
        }

        if (!any) return Bounds.EMPTY;
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ, true);
    }

    public static Vec3 dimensions(String jarResourcePath) {
        return bounds(jarResourcePath).size();
    }

    /**
     * @return the geometric center offset of the mesh (midpoint of its bounds), in model units.
     */
    public static Vec3 center(String jarResourcePath) {
        return bounds(jarResourcePath).center();
    }

    public static double longestAxis(String jarResourcePath) {
        Vec3 d = dimensions(jarResourcePath);
        return Math.max(d.x, Math.max(d.y, d.z));
    }

    /**
     * Resolves a model json's referenced obj to its jar resource path, or null if unavailable.
     */
    private static String objResourcePath(ResourceLocation modelId) {
        String jsonPath = "/assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json";
        try (InputStream in = ObjBounds.class.getResourceAsStream(jsonPath)) {
            if (in == null) return null;
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement modelEl = json.get("model");
            if (modelEl == null) return null;
            ResourceLocation objId = new ResourceLocation(modelEl.getAsString());
            return "/assets/" + objId.getNamespace() + "/" + objId.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return the mesh bounds for a model json's referenced obj, in model units.
     */
    public static Bounds boundsFromModel(ResourceLocation modelId) {
        String path = objResourcePath(modelId);
        return path == null ? Bounds.EMPTY : bounds(path);
    }

    public static Vec3 dimensionsFromModel(ResourceLocation modelId) {
        return boundsFromModel(modelId).size();
    }

    /**
     * @return the geometric center offset for a model json's referenced obj, in model units.
     */
    public static Vec3 centerFromModel(ResourceLocation modelId) {
        return boundsFromModel(modelId).center();
    }

    public static double longestAxisFromModel(ResourceLocation modelId) {
        Vec3 d = dimensionsFromModel(modelId);
        return Math.max(d.x, Math.max(d.y, d.z));
    }

    /**
     * Axis-aligned min/max of a mesh, in model units. {@code any} is false when no vertices were read.
     */
    public record Bounds(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ, boolean any) {

        public static final Bounds EMPTY = new Bounds(0, 0, 0, 0, 0, 0, false);

        /**
         * @return per-axis size (max - min).
         */
        public Vec3 size() {
            return any ? new Vec3(maxX - minX, maxY - minY, maxZ - minZ) : Vec3.ZERO;
        }

        /**
         * @return geometric center (midpoint of min/max) in model space.
         */
        public Vec3 center() {
            return any ? new Vec3((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0) : Vec3.ZERO;
        }
    }
}
