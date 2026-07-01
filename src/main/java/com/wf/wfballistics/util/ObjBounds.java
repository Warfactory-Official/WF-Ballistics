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

    private ObjBounds() { }

    public static Vec3 dimensions(String jarResourcePath) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        boolean any = false;

        try (InputStream in = ObjBounds.class.getResourceAsStream(jarResourcePath)) {
            if (in == null) return Vec3.ZERO;
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
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                    any = true;
                } catch (NumberFormatException ignored) {
                    // malformed vertex line - skip it
                }
            }
        } catch (IOException e) {
            return Vec3.ZERO;
        }

        if (!any) return Vec3.ZERO;
        return new Vec3(maxX - minX, maxY - minY, maxZ - minZ);
    }

    public static double longestAxis(String jarResourcePath) {
        Vec3 d = dimensions(jarResourcePath);
        return Math.max(d.x, Math.max(d.y, d.z));
    }


    public static Vec3 dimensionsFromModel(ResourceLocation modelId) {
        String jsonPath = "/assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json";
        try (InputStream in = ObjBounds.class.getResourceAsStream(jsonPath)) {
            if (in == null) return Vec3.ZERO;
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement modelEl = json.get("model");
            if (modelEl == null) return Vec3.ZERO;
            ResourceLocation objId = new ResourceLocation(modelEl.getAsString());
            return dimensions("/assets/" + objId.getNamespace() + "/" + objId.getPath());
        } catch (Exception e) {
            return Vec3.ZERO;
        }
    }

    public static double longestAxisFromModel(ResourceLocation modelId) {
        Vec3 d = dimensionsFromModel(modelId);
        return Math.max(d.x, Math.max(d.y, d.z));
    }
}
