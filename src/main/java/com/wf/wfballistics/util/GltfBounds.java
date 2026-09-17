package com.wf.wfballistics.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads a glTF's node layout and bounds out of the mod jar, for the half of the game that has no renderer. */
public final class GltfBounds {

    private static final Map<ResourceLocation, GltfBounds> CACHE = new ConcurrentHashMap<>();

    /** What an asset that could not be read answers: no nodes, empty bounds, every lookup absent. */
    private static final GltfBounds EMPTY =
            new GltfBounds(Map.of(), List.of(), ObjBounds.Bounds.EMPTY);

    private final Map<String, Node> byName;
    private final List<Node> nodes;
    private final ObjBounds.Bounds whole;

    private GltfBounds(Map<String, Node> byName, List<Node> nodes, ObjBounds.Bounds whole) {
        this.byName = byName;
        this.nodes = nodes;
        this.whole = whole;
    }

    /**
     * @return the node layout of a glTF asset, parsed once and cached. Never null: an asset that is
     *      missing or malformed reads as an empty graph, so a broken model costs an airframe its hitbox
     *      rather than crashing the server that loaded it.
     */
    public static GltfBounds of(ResourceLocation asset) {
        return CACHE.computeIfAbsent(asset, GltfBounds::read);
    }

    /** @return bounds of every mesh in the asset. */
    public ObjBounds.Bounds bounds() {
        return whole;
    }

    /** @return whether the asset named a node by this name. */
    public boolean has(String node) {
        return byName.containsKey(node);
    }

    /**
     * @return where a node's own origin sits in the asset's frame. The pivot a part turns about: a rotor
     *      hub, a hinge, the point a payload hangs from. Zero for a node the asset does not have.
     */
    public Vec3 origin(String node) {
        Node n = byName.get(node);
        if (n == null) {
            return Vec3.ZERO;
        }
        Vector3f o = n.world.transformPosition(new Vector3f(0.0f, 0.0f, 0.0f));
        return new Vec3(o.x, o.y, o.z);
    }

    /**
     * @return bounds of a node and everything under it: the part as it is drawn, not the one mesh
     *      hanging directly off it. A rotor is a hub node with its blades as children, so the subtree is what
     *      "the rotor" means. {@link ObjBounds.Bounds#EMPTY} for a node with no geometry beneath it, which is
     *      what a bare attachment point is.
     */
    public ObjBounds.Bounds subtree(String node) {
        Node n = byName.get(node);
        return n == null ? ObjBounds.Bounds.EMPTY : n.subtree;
    }

    /** @return every node name the asset declares, in file order. */
    public List<String> names() {
        List<String> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            out.add(n.name);
        }
        return out;
    }

    // --- parsing ---------------------------------------------------------------------------------

    private static GltfBounds read(ResourceLocation asset) {
        String path = "/assets/" + asset.getNamespace() + "/" + asset.getPath();
        JsonObject gltf;
        try (InputStream in = GltfBounds.class.getResourceAsStream(path)) {
            if (in == null) {
                return EMPTY;
            }
            gltf = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return EMPTY;
        }

        try {
            return build(gltf);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static GltfBounds build(JsonObject gltf) {
        JsonArray rawNodes = array(gltf, "nodes");
        JsonArray meshes = array(gltf, "meshes");
        JsonArray accessors = array(gltf, "accessors");

        int count = rawNodes.size();
        Node[] nodes = new Node[count];
        for (int i = 0; i < count; i++) {
            JsonObject n = rawNodes.get(i).getAsJsonObject();
            String name = n.has("name") ? n.get("name").getAsString() : "node" + i;
            nodes[i] = new Node(name, local(n), meshBounds(n, meshes, accessors));
        }

        // Children as declared, so the walk below can go top-down from the scene roots.
        int[][] children = new int[count][];
        for (int i = 0; i < count; i++) {
            JsonObject n = rawNodes.get(i).getAsJsonObject();
            JsonArray kids = n.has("children") ? n.getAsJsonArray("children") : new JsonArray();
            children[i] = new int[kids.size()];
            for (int k = 0; k < kids.size(); k++) {
                children[i][k] = kids.get(k).getAsInt();
            }
        }

        for (int root : sceneRoots(gltf, count)) {
            compose(nodes, children, root, new Matrix4f());
        }

        for (int root : sceneRoots(gltf, count)) {
            accumulate(nodes, children, root);
        }

        ObjBounds.Bounds whole = ObjBounds.Bounds.EMPTY;
        Map<String, Node> byName = new HashMap<>(count);
        List<Node> ordered = new ArrayList<>(count);
        for (Node n : nodes) {
            byName.putIfAbsent(n.name, n);
            ordered.add(n);
            whole = merge(whole, n.own);
        }
        return new GltfBounds(Map.copyOf(byName), List.copyOf(ordered), whole);
    }

    private static int[] sceneRoots(JsonObject gltf, int count) {
        JsonArray scenes = array(gltf, "scenes");
        int index = gltf.has("scene") ? gltf.get("scene").getAsInt() : 0;
        if (index < 0 || index >= scenes.size()) {
            int[] all = new int[count];
            for (int i = 0; i < count; i++) {
                all[i] = i;
            }
            return all;
        }
        JsonArray roots = scenes.get(index).getAsJsonObject().getAsJsonArray("nodes");
        int[] out = new int[roots.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = roots.get(i).getAsInt();
        }
        return out;
    }

    private static void compose(Node[] nodes, int[][] children, int index, Matrix4f parent) {
        Node node = nodes[index];
        node.world.set(parent).mul(node.local);
        node.own = transform(node.mesh, node.world);
        for (int child : children[index]) {
            compose(nodes, children, child, node.world);
        }
    }

    private static ObjBounds.Bounds accumulate(Node[] nodes, int[][] children, int index) {
        Node node = nodes[index];
        ObjBounds.Bounds total = node.own;
        for (int child : children[index]) {
            total = merge(total, accumulate(nodes, children, child));
        }
        node.subtree = total;
        return total;
    }

    /** A node's local transform: a matrix if it declares one, otherwise its TRS in glTF's own order. */
    private static Matrix4f local(JsonObject node) {
        Matrix4f m = new Matrix4f();
        if (node.has("matrix")) {
            JsonArray a = node.getAsJsonArray("matrix");
            float[] v = new float[16];
            for (int i = 0; i < 16; i++) {
                v[i] = a.get(i).getAsFloat();
            }
            // glTF stores column-major, which is what JOML's set(float[]) expects.
            return m.set(v);
        }
        if (node.has("translation")) {
            JsonArray t = node.getAsJsonArray("translation");
            m.translate(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat());
        }
        if (node.has("rotation")) {
            JsonArray r = node.getAsJsonArray("rotation");
            // glTF quaternions are (x, y, z, w), which is JOML's order too.
            m.rotate(new org.joml.Quaternionf(r.get(0).getAsFloat(), r.get(1).getAsFloat(),
                    r.get(2).getAsFloat(), r.get(3).getAsFloat()));
        }
        if (node.has("scale")) {
            JsonArray s = node.getAsJsonArray("scale");
            m.scale(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat());
        }
        return m;
    }

    /**
     * The box of every primitive a node draws, in the node's own space, read off the POSITION accessors' declared
     * min/max rather than off the vertices they describe.
     */
    private static ObjBounds.Bounds meshBounds(JsonObject node, JsonArray meshes, JsonArray accessors) {
        if (!node.has("mesh")) {
            return ObjBounds.Bounds.EMPTY;
        }
        int index = node.get("mesh").getAsInt();
        if (index < 0 || index >= meshes.size()) {
            return ObjBounds.Bounds.EMPTY;
        }
        JsonArray primitives = meshes.get(index).getAsJsonObject().getAsJsonArray("primitives");
        ObjBounds.Bounds total = ObjBounds.Bounds.EMPTY;
        for (JsonElement element : primitives) {
            JsonObject attributes = element.getAsJsonObject().getAsJsonObject("attributes");
            if (attributes == null || !attributes.has("POSITION")) {
                continue;
            }
            int accessor = attributes.get("POSITION").getAsInt();
            if (accessor < 0 || accessor >= accessors.size()) {
                continue;
            }
            JsonObject a = accessors.get(accessor).getAsJsonObject();
            if (!a.has("min") || !a.has("max")) {
                continue;
            }
            JsonArray min = a.getAsJsonArray("min");
            JsonArray max = a.getAsJsonArray("max");
            total = merge(total, new ObjBounds.Bounds(
                    min.get(0).getAsDouble(), min.get(1).getAsDouble(), min.get(2).getAsDouble(),
                    max.get(0).getAsDouble(), max.get(1).getAsDouble(), max.get(2).getAsDouble(), true));
        }
        return total;
    }

    /** A box through a matrix. */
    private static ObjBounds.Bounds transform(ObjBounds.Bounds box, Matrix4f matrix) {
        if (!box.any()) {
            return ObjBounds.Bounds.EMPTY;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        Vector3f corner = new Vector3f();
        for (int i = 0; i < 8; i++) {
            corner.set((float) ((i & 1) == 0 ? box.minX() : box.maxX()),
                    (float) ((i & 2) == 0 ? box.minY() : box.maxY()),
                    (float) ((i & 4) == 0 ? box.minZ() : box.maxZ()));
            matrix.transformPosition(corner);
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y);
            maxY = Math.max(maxY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new ObjBounds.Bounds(minX, minY, minZ, maxX, maxY, maxZ, true);
    }

    private static ObjBounds.Bounds merge(ObjBounds.Bounds a, ObjBounds.Bounds b) {
        if (!a.any()) {
            return b;
        }
        if (!b.any()) {
            return a;
        }
        return new ObjBounds.Bounds(
                Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()),
                true);
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    /** One node: its local transform, the box it draws, and what those become once composed. */
    private static final class Node {
        private final String name;
        private final Matrix4f local;
        private final ObjBounds.Bounds mesh;
        private final Matrix4f world = new Matrix4f();
        private ObjBounds.Bounds own = ObjBounds.Bounds.EMPTY;
        private ObjBounds.Bounds subtree = ObjBounds.Bounds.EMPTY;

        private Node(String name, Matrix4f local, ObjBounds.Bounds mesh) {
            this.name = name;
            this.local = local;
            this.mesh = mesh;
        }
    }

    /** @return a node's name if the asset has it, else null. Useful for validating a registration. */
    @Nullable
    public String resolve(String node) {
        return byName.containsKey(node) ? node : null;
    }
}
