package com.wf.wfballistics.door.client;

import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.AnimationDrive;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;
import com.wf.gemrender.rig.RigBuilder;
import com.wf.gemrender.rig.RigGeometry;
import com.wf.gemrender.rig.WavefrontObj;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.door.DoorType;
import com.wf.wfballistics.probe.ProbeElement;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every door as a GemRender model: the meshes NTM ships, given a skeleton, and the motion its renderers did by hand
 * turned into two clips.
 */
public final class DoorRigs {

    /** The clip a door plays while it opens, and while it sits open or shut. */
    public static final String OPEN = "open";
    /** The clip it plays while it shuts. */
    public static final String CLOSE = "close";

    private static final String MESH_ROOT = "raw_models/doors/";
    private static final String SKIN_ROOT = "textures/models/doors/";

    private static final Map<Long, Entry> RIGS = new ConcurrentHashMap<>();

    private DoorRigs() {
    }

    /**
     * A door's model plus the two drives that turn its open fraction into clip-local time, and the fixed transform
     * its renderer applied before drawing anything.
     */
    public record DoorModel(GemRenderGltfModel model, AnimationDrive open, AnimationDrive close,
                            Matrix4f base) {
    }

    /** @return the model for a door and skin, or null while its meshes have yet to load or have failed to */
    @Nullable
    public static DoorModel model(DoorType type, int skin) {
        int index = Math.floorMod(skin, Math.max(1, type.skins()));
        return RIGS.computeIfAbsent((long) type.ordinal() << 8 | index, key -> new Entry(type, index))
                .get();
    }

    /** Declare every door's handle up front. */
    public static void init() {
        for (DoorType type : DoorType.values()) {
            for (int skin = 0; skin < Math.max(1, type.skins()); skin++) {
                model(type, skin);
            }
        }
    }

    private static final class Entry {

        private final DoorType type;
        private final int skin;
        private final ModelCache.Handle<GemRenderGltfModel> handle;
        private volatile DoorModel resolved;

        private Entry(DoorType type, int skin) {
            this.type = type;
            this.skin = skin;
            this.handle = type == DoorType.TRANSITION_SEAL
                    ? GemRenderModels.handle(mesh("transition_seal.glb"))
                    : GemRenderModels.built(
                            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID,
                                    "rig/door/" + type.id() + "/" + skin),
                            id -> build(type, skin));
        }

        @Nullable
        private DoorModel get() {
            GemRenderGltfModel model = handle.get();
            if (model == null) {
                return null;
            }
            DoorModel current = resolved;
            if (current != null && current.model() == model) {
                return current;
            }

            AnimationDrive open;
            AnimationDrive close;
            if (type == DoorType.TRANSITION_SEAL) {
                GltfAnimation clip = model.animationOrAny(OPEN);
                open = AnimationDrive.ranged(clip, 0.0f, 1.0f);
                close = open;
            } else {
                open = AnimationDrive.ranged(model.animation(OPEN), 0.0f, 1.0f);
                close = AnimationDrive.ranged(model.animation(CLOSE), 1.0f, 0.0f);
            }
            current = new DoorModel(model, open, close, base(type));
            resolved = current;
            return current;
        }
    }

    // --- the fixed transform each renderer applied before drawing --------------------------------

    private static Matrix4f base(DoorType type) {
        Matrix4f m = new Matrix4f();
        switch (type) {
            case FIRE_DOOR -> m.rotateY((float) Math.toRadians(90)).translate(-0.5f, 0.0f, 0.0f);
            case SLIDING_SEAL_DOOR -> m.translate(0.5f, 0.0f, 0.0f);
            case SECURE_ACCESS_DOOR -> m.translate(0.0f, 1.0f, 0.0f);
            case ROUND_AIRLOCK_DOOR, TRANSITION_SEAL -> m.translate(0.0f, 0.0f, 0.5f);
            case QE_SLIDING_DOOR -> m.translate(0.53125f, 0.001f, 0.5f);
            case QE_CONTAINMENT -> m.translate(0.25f, 0.0f, 0.0f);
            case WATER_DOOR -> m.translate(0.375f, 0.0f, 0.0f).rotateY((float) Math.toRadians(90));
            case LARGE_VEHICLE_DOOR -> m.rotateY((float) Math.toRadians(90));
            case BLAST_DOOR -> m.rotateY((float) Math.PI);
            default -> {
            }
        }
        return m;
    }

    // --- construction ----------------------------------------------------------------------------

    private static GemRenderGltfModel build(DoorType type, int skin) throws IOException {
        return switch (type) {
            case VAULT_DOOR -> vault(skin);
            case FIRE_DOOR -> lift("fire_door", skinOf(type, skin), 2.75f, 0.0f, 1.0f, 0.0f);
            case SECURE_ACCESS_DOOR -> lift("secure_door", skinOf(type, skin), 3.5f, 0.0f, 1.0f, 0.0f);
            case QE_CONTAINMENT -> lift("containment_door", skinOf(type, skin), 2.25f, 0.0f, 1.0f, 0.0f);
            case SLIDING_SEAL_DOOR -> seal();
            case SLIDING_BLAST_DOOR -> slidingBlast();
            case ROUND_AIRLOCK_DOOR -> split("airlock_door", skinOf(type, skin), 1.5f, 0.0f, 0.0f, 1.0f);
            case QE_SLIDING_DOOR -> split("sliding_door", skinOf(type, skin), 0.95f, 0.0f, 0.0f, 1.0f);
            case LARGE_VEHICLE_DOOR -> split("vehicle_door", skinOf(type, skin), 3.0f, -1.0f, 0.0f, 0.0f);
            case WATER_DOOR -> water(skinOf(type, skin));
            case CARGO_DOOR -> cargo(skinOf(type, skin));
            case SILO_HATCH -> hatch("silo_hatch", -1.875f);
            case SILO_HATCH_LARGE -> hatch("silo_hatch_large", -2.875f);
            case BLAST_DOOR -> portcullis();
            case TRANSITION_SEAL -> throw new IllegalStateException("the seal is imported, not built");
        };
    }

    /** The plug pulls clear of its frame, then rolls aside on a rail; the roll is the slide over its own girth. */
    private static GemRenderGltfModel vault(int skin) throws IOException {
        Map<String, RigGeometry> groups = groups("vault_door");
        int time = DoorType.VAULT_DOOR.timeToOpen();
        float span = seconds(time);

        DoorTrack pullOpen = DoorTrack.over(time).from(0).to(1, 2000, DoorTrack.SIN_FULL).build();
        DoorTrack slideOpen = DoorTrack.over(time).to(0, 2000).to(1, 4000).build();
        DoorTrack pullClose = DoorTrack.over(time).from(1).hold(1, 4000)
                .to(0, 2000, DoorTrack.SIN_FULL).build();
        DoorTrack slideClose = DoorTrack.over(time).from(1).to(0, 4000).build();

        RigBuilder rig = new RigBuilder("door/vault_door/" + skin);
        int frame = rig.bone("frame", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int door = rig.bone("door", RigBuilder.ROOT, 0.0f, 2.5f, 0.0f);
        NodeTable table = rig.table();

        float degreesPerSlide = (float) (360.0 * 5.0 / (4.25 * Math.PI));

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                DoorDrivers.Slide.along(table, door, -1, 0, 0, pullOpen, 1.0f),
                DoorDrivers.Slide.along(table, door, 0, 0, 1, slideOpen, 5.0f),
                DoorDrivers.Turn.about(table, door, 1, 0, 0, slideOpen, degreesPerSlide)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                DoorDrivers.Slide.along(table, door, -1, 0, 0, pullClose, 1.0f),
                DoorDrivers.Slide.along(table, door, 0, 0, 1, slideClose, 5.0f),
                DoorDrivers.Turn.about(table, door, 1, 0, 0, slideClose, degreesPerSlide)));

        String[] textures = VAULT_SKINS[Math.floorMod(skin, VAULT_SKINS.length)];
        Material body = material(skinTexture(textures[0]));
        rig.attach(frame, groups, "Frame", body)
                .attach(door, groups, "Door", body)
                // The number plate is a second sheet, so a second draw. Seven of them off one mesh.
                .attach(door, groups, "Label", material(skinTexture(textures[1])));
        return rig.build(body, clips);
    }

    private static final String[][] VAULT_SKINS = {
            {"vault_door_3", "label_101"},
            {"vault_door_3", "label_87"},
            {"vault_door_3", "label_106"},
            {"vault_door_4", "label_81"},
            {"vault_door_4", "label_111"},
            {"vault_door_s", "label_2"},
            {"vault_door_s", "label_99"},
    };

    /** A frame and one leaf that travels along an axis: the fire, secure and containment doors. */
    private static GemRenderGltfModel lift(String mesh, ResourceLocation texture, float travel,
                                           float axisX, float axisY, float axisZ) throws IOException {
        return oneLeaf(mesh, texture, "Door", travel, axisX, axisY, axisZ, DoorTrack.LINEAR);
    }

    private static GemRenderGltfModel seal() throws IOException {
        return oneLeaf("seal_door", skinTexture("seal_door"), "Door", 0.9f, 0, 0, 1, DoorTrack.SMOOTHSTEP);
    }

    private static GemRenderGltfModel oneLeaf(String mesh, ResourceLocation texture, String group,
                                              float travel, float axisX, float axisY, float axisZ,
                                              byte easing) throws IOException {
        Map<String, RigGeometry> groups = groups(mesh);
        DoorType type = typeOfMesh(mesh);
        int time = type.timeToOpen();
        float span = seconds(time);
        DoorTrack open = DoorTrack.over(time).from(0).to(1, time * 50, easing).build();
        DoorTrack close = DoorTrack.over(time).from(1).to(0, time * 50, easing).build();

        RigBuilder rig = new RigBuilder("door/" + mesh + "/" + texture.getPath());
        int frame = rig.bone("frame", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int leaf = rig.bone("leaf", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        NodeTable table = rig.table();

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                DoorDrivers.Slide.along(table, leaf, axisX, axisY, axisZ, open, travel)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                DoorDrivers.Slide.along(table, leaf, axisX, axisY, axisZ, close, travel)));

        rig.attach(frame, groups, "Frame").attach(leaf, groups, group);
        return rig.build(material(texture), clips);
    }

    /** Two leaves that part along one axis: the airlock, the QE slider and the vehicle door. */
    private static GemRenderGltfModel split(String mesh, ResourceLocation texture, float travel,
                                            float axisX, float axisY, float axisZ) throws IOException {
        Map<String, RigGeometry> groups = groups(mesh);
        DoorType type = typeOfMesh(mesh);
        int time = type.timeToOpen();
        float span = seconds(time);
        DoorTrack open = DoorTrack.over(time).from(0).to(1, time * 50).build();
        DoorTrack close = DoorTrack.over(time).from(1).to(0, time * 50).build();

        RigBuilder rig = new RigBuilder("door/" + mesh + "/" + texture.getPath());
        int frame = rig.bone("frame", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int left = rig.bone("left", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int right = rig.bone("right", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        NodeTable table = rig.table();

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                DoorDrivers.Slide.along(table, left, axisX, axisY, axisZ, open, travel),
                DoorDrivers.Slide.along(table, right, -axisX, -axisY, -axisZ, open, travel)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                DoorDrivers.Slide.along(table, left, axisX, axisY, axisZ, close, travel),
                DoorDrivers.Slide.along(table, right, -axisX, -axisY, -axisZ, close, travel)));

        rig.attach(frame, groups, "Frame").attach(left, groups, "Left").attach(right, groups, "Right");
        return rig.build(material(texture), clips);
    }

    /** Locks quarter-turn out, then the leaves part. The locks ride on the opposite leaf, as NTM draws them. */
    private static GemRenderGltfModel slidingBlast() throws IOException {
        Map<String, RigGeometry> groups = groups("sliding_blast_door");
        int time = DoorType.SLIDING_BLAST_DOOR.timeToOpen();
        float span = seconds(time);

        DoorTrack lockOpen = DoorTrack.over(time).from(0).to(1, 200).build();
        DoorTrack doorOpen = DoorTrack.over(time).from(0).hold(0, 350).to(0.05f, 200)
                .to(1, 650, DoorTrack.SIN_UP).build();
        DoorTrack lockClose = DoorTrack.over(time).from(1).hold(1, 1000).to(0, 200).build();
        DoorTrack doorClose = DoorTrack.over(time).from(1).to(0.05f, 650, DoorTrack.SIN_UP)
                .to(0, 200).build();

        RigBuilder rig = new RigBuilder("door/sliding_blast_door/0");
        int frame = rig.bone("frame", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int leftDoor = rig.bone("left_door", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int rightDoor = rig.bone("right_door", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int rightLock = rig.bone("right_lock", leftDoor, 0.0f, 1.8125f, 0.0f);
        int leftLock = rig.bone("left_lock", rightDoor, 0.0f, 1.8125f, 0.0f);
        NodeTable table = rig.table();

        DoorTrack always = DoorTrack.constant(1.0f);

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                DoorDrivers.Slide.along(table, leftDoor, 0, 0, 1, doorOpen, 2.125f),
                DoorDrivers.Slide.along(table, rightDoor, 0, 0, -1, doorOpen, 2.125f),
                DoorDrivers.Turn.about(table, rightLock, 1, 0, 0, always, 90.0f),
                DoorDrivers.Turn.about(table, leftLock, 1, 0, 0, always, 90.0f),
                DoorDrivers.Turn.about(table, rightLock, 1, 0, 0, lockOpen, 90.0f),
                DoorDrivers.Turn.about(table, leftLock, 1, 0, 0, lockOpen, 90.0f)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                DoorDrivers.Slide.along(table, leftDoor, 0, 0, 1, doorClose, 2.125f),
                DoorDrivers.Slide.along(table, rightDoor, 0, 0, -1, doorClose, 2.125f),
                DoorDrivers.Turn.about(table, rightLock, 1, 0, 0, always, 90.0f),
                DoorDrivers.Turn.about(table, leftLock, 1, 0, 0, always, 90.0f),
                DoorDrivers.Turn.about(table, rightLock, 1, 0, 0, lockClose, 90.0f),
                DoorDrivers.Turn.about(table, leftLock, 1, 0, 0, lockClose, 90.0f)));

        rig.attach(frame, groups, "Frame")
                .attach(leftDoor, groups, "LeftDoor")
                .attach(rightDoor, groups, "RightDoor")
                .attach(rightLock, groups, "RightLock")
                .attach(leftLock, groups, "LeftLock");
        return rig.build(material(skinTexture("blast_door")), clips);
    }

    /** The dogs spin off and slide clear, then the leaf swings on its hinge. */
    private static GemRenderGltfModel water(ResourceLocation texture) throws IOException {
        Map<String, RigGeometry> groups = groups("water_door");
        int time = DoorType.WATER_DOOR.timeToOpen();
        float span = seconds(time);

        DoorTrack doorOpen = DoorTrack.over(time).from(0).hold(0, 1500)
                .to(1, 1500, DoorTrack.SIN_FULL).build();
        DoorTrack boltOpen = DoorTrack.over(time).from(0).to(1, 1500, DoorTrack.SIN_FULL).build();
        DoorTrack doorClose = DoorTrack.over(time).from(1).to(0, 1500, DoorTrack.SIN_FULL).build();
        DoorTrack boltClose = DoorTrack.over(time).from(1).hold(1, 1200)
                .to(0, 1500, DoorTrack.SIN_FULL).build();

        RigBuilder rig = new RigBuilder("door/water_door/" + texture.getPath());
        int frame = rig.bone("frame", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int door = rig.bone("door", RigBuilder.ROOT, -1.1875f, 0.0f, 0.0f);
        int bolts = rig.bone("bolts", door, 0.0f, 0.0f, 0.0f);
        int top = rig.bone("top", door, 1.59375f, 2.28125f, 0.0f);
        int bottom = rig.bone("bottom", door, 1.59375f, 0.71875f, 0.0f);
        NodeTable table = rig.table();

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                DoorDrivers.Turn.about(table, door, 0, 1, 0, doorOpen, -120.0f),
                DoorDrivers.Slide.along(table, bolts, -1, 0, 0, boltOpen, 0.4f),
                DoorDrivers.Turn.about(table, top, 0, 0, 1, boltOpen, 360.0f),
                DoorDrivers.Turn.about(table, bottom, 0, 0, 1, boltOpen, 360.0f)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                DoorDrivers.Turn.about(table, door, 0, 1, 0, doorClose, -120.0f),
                DoorDrivers.Slide.along(table, bolts, -1, 0, 0, boltClose, 0.4f),
                DoorDrivers.Turn.about(table, top, 0, 0, 1, boltClose, 360.0f),
                DoorDrivers.Turn.about(table, bottom, 0, 0, 1, boltClose, 360.0f)));

        rig.attach(frame, groups, "Frame")
                .attach(door, groups, "Door_Cube.003")
                .attach(bolts, groups, "Bolts")
                .attach(top, groups, "Top")
                .attach(bottom, groups, "Bottom");
        return rig.build(material(texture), clips);
    }

    /** Two shutters, the lower leading the upper by half the travel. */
    private static GemRenderGltfModel cargo(ResourceLocation texture) throws IOException {
        Map<String, RigGeometry> groups = groups("cargo_door");
        int time = DoorType.CARGO_DOOR.timeToOpen();
        float span = seconds(time);
        int half = time * 25;

        DoorTrack botOpen = DoorTrack.over(time).from(0).to(1, half * 2).build();
        DoorTrack topOpen = DoorTrack.over(time).from(0).hold(0, half).to(1, half).build();
        DoorTrack botClose = DoorTrack.over(time).from(1).to(0, half * 2).build();
        DoorTrack topClose = DoorTrack.over(time).from(1).hold(1, half).to(0, half).build();

        RigBuilder rig = new RigBuilder("door/cargo_door/" + texture.getPath());
        int frame = rig.bone("frame", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int top = rig.bone("top", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int bottom = rig.bone("bottom", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        NodeTable table = rig.table();

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                DoorDrivers.Slide.along(table, top, 0, 1, 0, topOpen, 1.0f),
                DoorDrivers.Slide.along(table, bottom, 0, 1, 0, botOpen, 2.0f)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                DoorDrivers.Slide.along(table, top, 0, 1, 0, topClose, 1.0f),
                DoorDrivers.Slide.along(table, bottom, 0, 1, 0, botClose, 2.0f)));

        rig.attach(frame, groups, "Frame")
                .attach(top, groups, "DoorTop")
                .attach(bottom, groups, "DoorBot");
        return rig.build(material(texture), clips);
    }

    /** A hatch that lifts a quarter block clear of its pad and then folds back. */
    private static GemRenderGltfModel hatch(String mesh, float pivotZ) throws IOException {
        Map<String, RigGeometry> groups = groups(mesh);
        DoorType type = typeOfMesh(mesh);
        int time = type.timeToOpen();
        float span = seconds(time);

        DoorTrack liftOpen = DoorTrack.over(time).from(0).to(1, 500, DoorTrack.SMOOTHSTEP).build();
        DoorTrack foldOpen = DoorTrack.over(time).from(0).hold(0, 1000)
                .to(1, 4000, DoorTrack.SMOOTHSTEP).build();
        DoorTrack liftClose = liftOpen.reversed();
        DoorTrack foldClose = foldOpen.reversed();

        RigBuilder rig = new RigBuilder("door/" + mesh + "/0");
        int frame = rig.bone("frame", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int fold = rig.bone("hatch", RigBuilder.ROOT, 0.0f, 0.875f, pivotZ);
        int lift = rig.bone("hatch_lift", fold, 0.0f, 0.0f, 0.0f);
        NodeTable table = rig.table();

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                DoorDrivers.Turn.about(table, fold, 1, 0, 0, foldOpen, -240.0f),
                DoorDrivers.Slide.along(table, lift, 0, 1, 0, liftOpen, 0.25f)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                DoorDrivers.Turn.about(table, fold, 1, 0, 0, foldClose, -240.0f),
                DoorDrivers.Slide.along(table, lift, 0, 1, 0, liftClose, 0.25f)));

        rig.attach(frame, groups, "Frame").attach(lift, groups, "Hatch");
        return rig.build(material(skinTexture(mesh)), clips);
    }

    /** The portcullis: a head that stays put and a mast of five segments that telescopes down out of it. */
    private static GemRenderGltfModel portcullis() throws IOException {
        Map<String, RigGeometry> base = groups("blast_door_base");
        Map<String, RigGeometry> block = groups("blast_door_block");
        Map<String, RigGeometry> slider = groups("blast_door_slider");
        Map<String, RigGeometry> tooth = groups("blast_door_tooth");
        int time = DoorType.BLAST_DOOR.timeToOpen();
        float span = seconds(time);

        // How far the mast has drawn out of the housing: 0 stowed, 1 fully down and the doorway shut.
        DoorTrack outOpen = DoorTrack.over(time).from(1).to(0, time * 50).build();
        DoorTrack outClose = DoorTrack.over(time).from(0).to(1, time * 50).build();
        // Its complement, which is how far each segment has risen.
        DoorTrack upOpen = DoorTrack.over(time).from(0).to(1, time * 50).build();
        DoorTrack upClose = DoorTrack.over(time).from(1).to(0, time * 50).build();
        DoorTrack always = DoorTrack.constant(1.0f);

        RigBuilder rig = new RigBuilder("door/blast_door/0");
        int baseSlot = rig.bone("base", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int head = rig.bone("head", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int toothSlot = rig.bone("tooth", RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        int[] segments = new int[4];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = rig.bone("mast" + (i + 1), RigBuilder.ROOT, 0.0f, 0.0f, 0.0f);
        }
        NodeTable table = rig.table();

        Map<String, GltfAnimation> clips = new LinkedHashMap<>();
        clips.put(OPEN, GltfAnimation.procedural(OPEN, span,
                mast(table, head, toothSlot, segments, always, upOpen, outOpen)));
        clips.put(CLOSE, GltfAnimation.procedural(CLOSE, span,
                mast(table, head, toothSlot, segments, always, upClose, outClose)));

        rig.attach(baseSlot, base, only(base), material(skinTexture("blast_door_base")))
                .attach(head, block, only(block), material(skinTexture("blast_door_block")))
                .attach(toothSlot, tooth, only(tooth), material(skinTexture("blast_door_tooth")));
        Material mastSkin = material(skinTexture("blast_door_slider"));
        for (int slot : segments) {
            rig.attach(slot, slider, only(slider), mastSkin);
        }
        return rig.build(material(skinTexture("blast_door_base")), clips);
    }

    /** The mast, as drivers. */
    private static PoseDriver[] mast(NodeTable table, int head, int tooth, int[] segments,
                                     DoorTrack always, DoorTrack up, DoorTrack out) {
        List<PoseDriver> drivers = new ArrayList<>();
        drivers.add(DoorDrivers.Slide.along(table, head, 0, 1, 0, always, 3.0f));
        drivers.add(DoorDrivers.Slide.along(table, tooth, 0, 1, 0, up, 5.0f));
        for (int i = 0; i < segments.length; i++) {
            drivers.add(DoorDrivers.Slide.along(table, segments[i], 0, 1, 0, up, 5.0f));
            drivers.add(DoorDrivers.Slide.along(table, segments[i], 0, 1, 0, always, i));
            drivers.add(DoorDrivers.Gate.above(table, segments[i], out, (i + 1) / 5.0f));
        }
        return drivers.toArray(new PoseDriver[0]);
    }

    // --- the still preview -----------------------------------------------------------------------

    /**
     * The same meshes and sheets, as flat parts, for a probe panel to draw the door as a model.
     *
     * @return empty for a door there is no obj for, which is the imported seal: the caller falls back
     *      to the door's item
     */
    public static List<ProbeElement.Mesh.Part> preview(DoorType type, int skin) {
        return switch (type) {
            case VAULT_DOOR -> {
                String[] textures = VAULT_SKINS[Math.floorMod(skin, VAULT_SKINS.length)];
                ResourceLocation body = skinTexture(textures[0]);
                yield List.of(part("vault_door", "Frame", body), part("vault_door", "Door", body),
                        part("vault_door", "Label", skinTexture(textures[1])));
            }
            case FIRE_DOOR -> whole("fire_door", skinOf(type, skin));
            case SECURE_ACCESS_DOOR -> whole("secure_door", skinOf(type, skin));
            case QE_CONTAINMENT -> whole("containment_door", skinOf(type, skin));
            case SLIDING_SEAL_DOOR -> whole("seal_door", skinTexture("seal_door"));
            case SLIDING_BLAST_DOOR -> whole("sliding_blast_door", skinTexture("blast_door"));
            case ROUND_AIRLOCK_DOOR -> whole("airlock_door", skinOf(type, skin));
            case QE_SLIDING_DOOR -> whole("sliding_door", skinOf(type, skin));
            case LARGE_VEHICLE_DOOR -> whole("vehicle_door", skinOf(type, skin));
            case WATER_DOOR -> whole("water_door", skinOf(type, skin));
            case CARGO_DOOR -> whole("cargo_door", skinOf(type, skin));
            case SILO_HATCH -> whole("silo_hatch", skinTexture("silo_hatch"));
            case SILO_HATCH_LARGE -> whole("silo_hatch_large", skinTexture("silo_hatch_large"));
            case BLAST_DOOR -> List.of(
                    part("blast_door_base", null, skinTexture("blast_door_base")),
                    part("blast_door_block", null, skinTexture("blast_door_block")));
            case TRANSITION_SEAL -> List.of();
        };
    }

    private static List<ProbeElement.Mesh.Part> whole(String mesh, ResourceLocation texture) {
        return List.of(part(mesh, null, texture));
    }

    private static ProbeElement.Mesh.Part part(String mesh, @Nullable String group,
                                               ResourceLocation texture) {
        return new ProbeElement.Mesh.Part(mesh(mesh + ".obj"), group, texture);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String only(Map<String, RigGeometry> groups) {
        return groups.keySet().iterator().next();
    }

    private static Map<String, RigGeometry> groups(String mesh) throws IOException {
        return WavefrontObj.load(mesh(mesh + ".obj"));
    }

    private static ResourceLocation mesh(String file) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, MESH_ROOT + file);
    }

    private static ResourceLocation skinTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, SKIN_ROOT + name + ".png");
    }

    /** A door sheet, alpha-tested. */
    private static Material material(ResourceLocation texture) {
        return SimpleMaterial.builder()
                .texture(texture)
                .mipmap(false)
                .cutout(CutoutShaders.ONE_TENTH)
                .build();
    }

    /** Clip length for a door that takes this many ticks, in seconds. @see DoorTrack */
    private static float seconds(int ticks) {
        return ticks / 20.0f;
    }

    private static ResourceLocation skinOf(DoorType type, int skin) {
        String[] names = SKINS.get(type);
        if (names == null || names.length == 0) {
            return skinTexture(type.id());
        }
        return skinTexture(names[Math.floorMod(skin, names.length)]);
    }

    private static final Map<DoorType, String[]> SKINS = new java.util.EnumMap<>(DoorType.class);

    static {
        SKINS.put(DoorType.FIRE_DOOR, new String[] {"fire_door", "fire_door_black", "fire_door_orange",
                "fire_door_yellow", "fire_door_trefoil"});
        SKINS.put(DoorType.SECURE_ACCESS_DOOR, new String[] {"secure_door", "secure_door_grey",
                "secure_door_black", "secure_door_yellow"});
        SKINS.put(DoorType.ROUND_AIRLOCK_DOOR, new String[] {"airlock_door", "airlock_door_clean",
                "airlock_door_green"});
        SKINS.put(DoorType.QE_CONTAINMENT, new String[] {"containment_door", "containment_door_trefoil",
                "containment_door_trefoil_yellow"});
        SKINS.put(DoorType.WATER_DOOR, new String[] {"water_door", "water_door_clean"});
        SKINS.put(DoorType.CARGO_DOOR, new String[] {"cargo_door"});
        SKINS.put(DoorType.QE_SLIDING_DOOR, new String[] {"sliding_door"});
        SKINS.put(DoorType.LARGE_VEHICLE_DOOR, new String[] {"vehicle_door"});
        SKINS.put(DoorType.SLIDING_SEAL_DOOR, new String[] {"seal_door"});
        SKINS.put(DoorType.SLIDING_BLAST_DOOR, new String[] {"blast_door"});
        SKINS.put(DoorType.SILO_HATCH, new String[] {"silo_hatch"});
        SKINS.put(DoorType.SILO_HATCH_LARGE, new String[] {"silo_hatch_large"});
    }

    /** Which door a mesh belongs to, so a builder shared by several doors still reads its own timing. */
    private static DoorType typeOfMesh(String mesh) {
        return switch (mesh) {
            case "fire_door" -> DoorType.FIRE_DOOR;
            case "secure_door" -> DoorType.SECURE_ACCESS_DOOR;
            case "containment_door" -> DoorType.QE_CONTAINMENT;
            case "seal_door" -> DoorType.SLIDING_SEAL_DOOR;
            case "airlock_door" -> DoorType.ROUND_AIRLOCK_DOOR;
            case "sliding_door" -> DoorType.QE_SLIDING_DOOR;
            case "vehicle_door" -> DoorType.LARGE_VEHICLE_DOOR;
            case "silo_hatch" -> DoorType.SILO_HATCH;
            case "silo_hatch_large" -> DoorType.SILO_HATCH_LARGE;
            default -> throw new IllegalArgumentException("no door owns mesh " + mesh);
        };
    }
}
