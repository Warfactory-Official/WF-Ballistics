package com.wf.wfballistics.client.model;

import com.mojang.logging.LogUtils;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.AnimationDrive;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.gltf.NodeSpin;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;
import com.wf.gemrender.rig.RigBuilder;
import com.wf.gemrender.rig.RigGeometry;
import com.wf.gemrender.rig.WavefrontObj;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.anim.NodeIsolate;
import com.wf.wfballistics.anim.NodeScale;
import com.wf.wfballistics.anim.Rotor;
import com.wf.wfballistics.anim.Rotors;
import com.wf.wfballistics.drone.DroneModels;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The airframe rigs: every missile and every drone, with the parts that move hung off the parts that do not. */
public final class PartRigs {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The spin clip: every rotor turning, at its own signed multiple of the reference speed. */
    public static final String SPIN = "spin";
    /** The stow clip: every node the airframe declares it does not draw, collapsed to nothing. */
    public static final String STOW = "stow";
    /** A piece of wreckage: that node alone, at full size at 0 and shrunk away at 1. */
    public static final String PIECE = "piece";

    private static final Map<ResourceLocation, Entry> DRONES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Entry> MISSILES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Entry> PIECES = new ConcurrentHashMap<>();

    private PartRigs() {
    }

    /**
     * An airframe together with the drives that turn what the game measures into clip-local time.
     *
     * @param spin null for a model with no rotors, which is most missiles
     * @param stow null for a model that draws everything it has
     * @param piece null for anything but wreckage, which is the one thing drawn as a part of a model
     *      rather than as the whole of one
     */
    public record Rig(GemRenderGltfModel model, @Nullable AnimationDrive spin,
                      @Nullable AnimationDrive stow, @Nullable AnimationDrive piece) {
    }

    /** @return the airframe for a drone id, or null while its asset has yet to load or has failed to */
    @Nullable
    public static Rig drone(ResourceLocation id) {
        return DRONES.computeIfAbsent(id, Entry::drone)
                .get();
    }

    /** @return the airframe for a missile id, or null while its meshes have yet to load or have failed to */
    @Nullable
    public static Rig missile(ResourceLocation id) {
        return MISSILES.computeIfAbsent(id, Entry::missile)
                .get();
    }

    /**
     * One piece of a broken-up airframe: the node it is, drawn out of the asset it came from.
     *
     * @return the piece, or null while the asset has yet to load or has failed to
     */
    @Nullable
    public static Rig piece(ResourceLocation pieceId) {
        return PIECES.computeIfAbsent(pieceId, Entry::piece)
                .get();
    }

    /**
     * Declare every airframe's handle up front, so the rigs are built when resources finish loading rather than by
     * whichever visual happens to want one first, which would be an import on a flywheel task thread, mid-frame.
     */
    public static void init() {
        for (ResourceLocation id : DroneModels.ids()) {
            drone(id);
            for (ResourceLocation part : DroneModels.pieces(id)) {
                piece(part);
            }
        }
        for (ResourceLocation id : MissileModels.ids()) {
            missile(id);
        }
    }

    /** One airframe's handle plus the {@link Rig} derived from whatever is currently behind it. */
    private static final class Entry {

        private final ModelCache.Handle<GemRenderGltfModel> handle;
        /** How the clips are built once the model resolves. Null for a missile, which builds its own. */
        @Nullable
        private final Clips clips;
        private final float rotorReference;

        private volatile Rig rig;

        private Entry(ModelCache.Handle<GemRenderGltfModel> handle, @Nullable Clips clips,
                      float rotorReference) {
            this.handle = handle;
            this.clips = clips;
            this.rotorReference = rotorReference;
        }

        /** A drone: the asset imported whole, with its clips written against the node table that comes back. */
        private static Entry drone(ResourceLocation id) {
            List<Rotor> rotors = Rotors.of(id);
            return new Entry(GemRenderModels.handle(DroneModels.asset(id)),
                    table -> droneClips(id, rotors, table, reference(rotors)),
                    reference(rotors));
        }

        private static Entry piece(ResourceLocation pieceId) {
            DroneModels.Piece piece = DroneModels.piece(pieceId);
            if (piece == null) {
                return new Entry(GemRenderModels.handle(pieceId), null, 0.0f);
            }
            return new Entry(GemRenderModels.handle(piece.asset()),
                    table -> pieceClips(piece, table), 0.0f);
        }

        private static Entry missile(ResourceLocation id) {
            List<Rotor> rotors = Rotors.of(id);
            float reference = reference(rotors);
            return new Entry(GemRenderModels.built(
                    ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "rig/missile/" + id.getPath()),
                    key -> buildMissile(id, MissileModels.model(id), rotors, reference)),
                    null, reference);
        }

        /**
         * Every rotor's speed is expressed as a multiple of the first one's, so the clip is one revolution of the
         * reference disc and the drive is fed the phase in ticks.
         */
        private static float reference(List<Rotor> rotors) {
            return rotors.isEmpty() ? 0.0f : Math.abs(rotors.get(0)
                    .degreesPerTick());
        }

        @Nullable
        private Rig get() {
            GemRenderGltfModel model = handle.get();
            if (model == null) {
                return null;
            }

            Rig current = rig;
            if (current != null && current.model() == model) {
                return current;
            }

            Map<String, GltfAnimation> built = clips == null ? Map.of()
                    : clips.build(model.layout()
                    .nodeTable());

            GltfAnimation spin = built.containsKey(SPIN) ? built.get(SPIN) : model.animation(SPIN);
            GltfAnimation stow = built.get(STOW);
            GltfAnimation piece = built.get(PIECE);

            current = new Rig(model,
                    spin == null || rotorReference <= 0.0f ? null
                            : AnimationDrive.cyclic(spin, 360.0f / rotorReference),
                    // The stow and the piece are both already 0 to 1, which is exactly the clip.
                    stow == null ? null : AnimationDrive.ranged(stow, 0.0f, 1.0f),
                    piece == null ? null : AnimationDrive.ranged(piece, 0.0f, 1.0f));
            rig = current;
            return current;
        }
    }

    /** Writes an imported model's clips once its node table exists. */
    @FunctionalInterface
    private interface Clips {
        Map<String, GltfAnimation> build(NodeTable table);
    }

    // --- drone clips, written against an imported node table --------------------------------------

    private static Map<String, GltfAnimation> droneClips(ResourceLocation id, List<Rotor> rotors,
                                                         NodeTable table, float reference) {
        Map<String, GltfAnimation> clips = new LinkedHashMap<>();

        if (!rotors.isEmpty() && reference > 0.0f) {
            List<PoseDriver> spin = new ArrayList<>(rotors.size());
            for (Rotor rotor : rotors) {
                int slot = slot(table, rotor.node(), id);
                if (slot < 0) {
                    continue;
                }
                Vector3f axis = rotor.axis();
                spin.add(NodeSpin.about(table, slot, axis.x, axis.y, axis.z,
                        rotor.degreesPerTick() / reference));
            }
            if (!spin.isEmpty()) {
                clips.put(SPIN, GltfAnimation.procedural(SPIN, 1.0f, spin.toArray(new PoseDriver[0])));
            }
        }

        List<PoseDriver> stow = new ArrayList<>();
        for (String node : DroneModels.stowed(id)) {
            int slot = slot(table, node, id);
            if (slot >= 0) {
                stow.add(NodeScale.away(table, slot));
            }
        }
        if (!stow.isEmpty()) {
            clips.put(STOW, GltfAnimation.procedural(STOW, 1.0f, stow.toArray(new PoseDriver[0])));
        }

        return clips;
    }

    private static Map<String, GltfAnimation> pieceClips(DroneModels.Piece piece, NodeTable table) {
        int slot = table.slotOfName(piece.node());
        if (slot < 0) {
            LOGGER.warn("drone piece names node '{}', which {} does not have; it will draw "
                    + "as the whole airframe", piece.node(), piece.asset());
            return Map.of();
        }
        return Map.of(PIECE, GltfAnimation.procedural(PIECE, 1.0f,
                NodeIsolate.of(table, slot), NodeScale.away(table, slot)));
    }

    private static int slot(NodeTable table, @Nullable String node, ResourceLocation id) {
        if (node == null) {
            return -1;
        }
        int slot = table.slotOfName(node);
        if (slot < 0) {
            LOGGER.warn("airframe {} names node '{}', which its asset does not have; that "
                    + "part will not move", id, node);
            return -1;
        }
        if (!table.isPosable(slot)) {
            LOGGER.warn("airframe {} drives node '{}', but it was exported with a baked "
                    + "matrix rather than translation/rotation/scale, so it cannot move. Re-export it "
                    + "without 'Bake all objects' / with TRS transforms.", id, node);
            return -1;
        }
        return slot;
    }

    // --- missile rigs, assembled from loose objs ---------------------------------------------------

    private static GemRenderGltfModel buildMissile(ResourceLocation id, ResourceLocation body,
                                                   List<Rotor> rotors, float rotorReference)
            throws IOException {
        ObjModelJson hull = ObjModelJson.read(body);
        Map<ResourceLocation, Material> materials = new HashMap<>();
        Material hullMaterial = materials.computeIfAbsent(hull.texture(), PartRigs::material);

        RigBuilder rig = new RigBuilder("missile/" + id.getPath());
        int root = rig.bone("body", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);

        int[] rotorSlots = new int[rotors.size()];
        ObjModelJson[] rotorParts = new ObjModelJson[rotors.size()];
        for (int i = 0; i < rotors.size(); i++) {
            Vector3f pivot = Rotors.pivot(rotors.get(i));
            rotorSlots[i] = rig.bone("prop" + i, root, pivot.x, pivot.y, pivot.z);
            rotorParts[i] = ObjModelJson.read(rotors.get(i)
                    .model());
        }

        NodeTable table = rig.table();

        rig.attachAll(root, groups(hull), null);
        for (int i = 0; i < rotorSlots.length; i++) {
            rig.attachAll(rotorSlots[i], groups(rotorParts[i]),
                    partMaterial(materials, rotorParts[i], hull));
        }

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        if (!rotors.isEmpty() && rotorReference > 0.0f) {
            List<PoseDriver> spin = new ArrayList<>(rotors.size());
            for (int i = 0; i < rotors.size(); i++) {
                Rotor rotor = rotors.get(i);
                Vector3f axis = rotor.axis();
                spin.add(NodeSpin.about(table, rotorSlots[i], axis.x, axis.y, axis.z,
                        rotor.degreesPerTick() / rotorReference));
            }
            clips.put(SPIN, GltfAnimation.procedural(SPIN, 1.0f, spin.toArray(new PoseDriver[0])));
        }

        return rig.build(hullMaterial, clips);
    }

    private static Map<String, RigGeometry> groups(ObjModelJson model) throws IOException {
        return WavefrontObj.load(model.obj());
    }

    @Nullable
    private static Material partMaterial(Map<ResourceLocation, Material> materials, ObjModelJson part,
                                         ObjModelJson hull) {
        if (part.texture()
                .equals(hull.texture())) {
            return null;
        }
        return materials.computeIfAbsent(part.texture(), PartRigs::material);
    }

    private static Material material(ResourceLocation texture) {
        return SimpleMaterial.builder()
                .texture(texture)
                .mipmap(false)
                .build();
    }
}
