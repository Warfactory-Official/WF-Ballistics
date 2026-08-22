package com.wf.wfballistics.client.model;

import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.vertex.PosTexNormalVertexView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a Wavefront {@code .obj} out of the mod's assets as one flywheel {@link Mesh} per named group.
 *
 * <p>Missiles and drones go through the {@code neoforge:obj} model loader instead, and should: it bakes a
 * file into a {@code BakedModel} on the block atlas, which is exactly what a rigid prop wants. A rigged mob
 * wants neither half of it. Its walk cycle poses nineteen groups of one file against each other and the
 * loader hands back one flat model with no way to address a group; and its caste skins are entity textures,
 * so putting them on the block atlas would cost one model json per group <em>per caste</em> just to name
 * which sprite each reads. Parsing here gives named groups for nothing and leaves the texture to the
 * material, where a skin swap is one {@code ResourceLocation}.
 *
 * <p>Not a general obj loader: it reads geometry and drops materials, which is all a flywheel mesh carries.
 */
public final class ObjMeshes {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Cleared alongside flywheel's own model caches when the renderer reloads, so an edited obj comes back
     * with F3+A rather than needing a restart.
     */
    private static final RendererReloadCache<ResourceLocation, Map<String, Mesh>> CACHE =
            new RendererReloadCache<>(ObjMeshes::load);

    private ObjMeshes() {
    }

    /**
     * @return the named groups of an obj, or an empty map if it could not be read. Cached; safe to call from
     * the visual threads flywheel builds visuals on.
     */
    public static Map<String, Mesh> of(ResourceLocation obj) {
        return CACHE.get(obj);
    }

    private static Map<String, Mesh> load(ResourceLocation obj) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(obj);
        if (resource.isEmpty()) {
            LOGGER.error("Missing obj {}", obj);
            return Map.of();
        }

        Floats positions = new Floats();
        Floats uvs = new Floats();
        Floats normals = new Floats();
        Floats corners = new Floats();
        Map<String, Mesh> groups = new LinkedHashMap<>();
        String group = "Default";

        try (BufferedReader reader = resource.get().openAsReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (line.startsWith("v ")) {
                    String[] t = split(line);
                    positions.add(parse(t, 1)).add(parse(t, 2)).add(parse(t, 3));
                } else if (line.startsWith("vt ")) {
                    String[] t = split(line);
                    // The obj V axis runs up from the bottom left, Minecraft's runs down from the top left.
                    uvs.add(parse(t, 1)).add(1.0f - parse(t, 2));
                } else if (line.startsWith("vn ")) {
                    String[] t = split(line);
                    normals.add(parse(t, 1)).add(parse(t, 2)).add(parse(t, 3));
                } else if (line.startsWith("f ")) {
                    face(split(line), corners, positions, uvs, normals);
                } else if (line.startsWith("o ") || line.startsWith("g ")) {
                    flush(groups, group, corners, obj);
                    group = line.substring(2).trim();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read obj {}", obj, e);
            return Map.of();
        }
        flush(groups, group, corners, obj);
        return Map.copyOf(groups);
    }

    /**
     * Fan-triangulate a face, then pad each triangle to a quad by repeating its last corner: flywheel indexes
     * every mesh as quads, and the pad becomes a zero-area triangle the rasteriser throws away.
     */
    private static void face(String[] tokens, Floats corners, Floats positions, Floats uvs, Floats normals) {
        for (int k = 2; k + 1 < tokens.length; k++) {
            corner(tokens[1], corners, positions, uvs, normals);
            corner(tokens[k], corners, positions, uvs, normals);
            corner(tokens[k + 1], corners, positions, uvs, normals);
            corner(tokens[k + 1], corners, positions, uvs, normals);
        }
    }

    /**
     * One {@code v}, {@code v/vt}, {@code v//vn} or {@code v/vt/vn} corner, flattened to the eight floats a
     * {@link PosTexNormalVertexView} vertex holds.
     */
    private static void corner(String token, Floats dst, Floats positions, Floats uvs, Floats normals) {
        int slash = token.indexOf('/');
        int second = slash < 0 ? -1 : token.indexOf('/', slash + 1);
        String vs = slash < 0 ? token : token.substring(0, slash);
        String ts = slash < 0 || second == slash + 1 ? "" :
                token.substring(slash + 1, second < 0 ? token.length() : second);
        String ns = second < 0 ? "" : token.substring(second + 1);

        int p = deref(vs, positions.size() / 3) * 3;
        dst.add(positions.get(p)).add(positions.get(p + 1)).add(positions.get(p + 2));

        if (ts.isEmpty()) {
            dst.add(0.0f).add(0.0f);
        } else {
            int t = deref(ts, uvs.size() / 2) * 2;
            dst.add(uvs.get(t)).add(uvs.get(t + 1));
        }

        if (ns.isEmpty()) {
            dst.add(0.0f).add(1.0f).add(0.0f);
        } else {
            int n = deref(ns, normals.size() / 3) * 3;
            dst.add(normals.get(n)).add(normals.get(n + 1)).add(normals.get(n + 2));
        }
    }

    /**
     * Obj indices are one-based, and negative ones count back from the end of what has been declared so far.
     */
    private static int deref(String token, int count) {
        int index = Integer.parseInt(token);
        return index > 0 ? index - 1 : count + index;
    }

    private static void flush(Map<String, Mesh> groups, String name, Floats corners, ResourceLocation obj) {
        if (corners.size() == 0) {
            return;
        }
        int vertices = corners.size() / 8;
        MemoryBlock data = MemoryBlock.mallocTracked((long) vertices * PosTexNormalVertexView.STRIDE);
        PosTexNormalVertexView view = new PosTexNormalVertexView();
        view.load(data);
        for (int i = 0; i < vertices; i++) {
            int o = i * 8;
            view.x(i, corners.get(o));
            view.y(i, corners.get(o + 1));
            view.z(i, corners.get(o + 2));
            view.u(i, corners.get(o + 3));
            view.v(i, corners.get(o + 4));
            view.normalX(i, corners.get(o + 5));
            view.normalY(i, corners.get(o + 6));
            view.normalZ(i, corners.get(o + 7));
        }
        corners.clear();
        groups.put(name, new SimpleQuadMesh(view, obj + "#" + name));
    }

    private static String[] split(String line) {
        return line.split("\\s+");
    }

    private static float parse(String[] tokens, int index) {
        return Float.parseFloat(tokens[index]);
    }

    /**
     * A growable float array. The parse touches every number in the file twice over and {@code ArrayList}
     * would box each one.
     */
    private static final class Floats {

        private float[] data = new float[1024];
        private int size;

        Floats add(float value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
            return this;
        }

        float get(int index) {
            return data[index];
        }

        int size() {
            return size;
        }

        void clear() {
            size = 0;
        }
    }
}
