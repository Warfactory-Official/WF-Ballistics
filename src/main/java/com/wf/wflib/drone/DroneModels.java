package com.wf.wflib.drone;

import com.wf.wflib.WFLib;
import com.wf.wflib.anim.Bones;
import com.wf.wflib.anim.Rotors;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.util.GltfBounds;
import com.wf.wflib.util.ObjBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of drone airframes, the {@code MissileModels} counterpart for things that fly under rotors. */
public final class DroneModels {

    public static final ResourceLocation DEFAULT = rl("amazog");
    public static final String DEFAULT_ATTITUDE = "copter";

    private static final Map<ResourceLocation, Rig> RIGS = new LinkedHashMap<>();
    /** Every piece of every airframe, back to the asset and node it names. See {@link #pieces}. */
    private static final Map<ResourceLocation, Piece> PIECES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Vec3> DIMENSIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Vec3> CENTERS = new ConcurrentHashMap<>();

    static {
        // The Amazog: eight rotors and a parcel bin it does not use.
        reg(new Builder("amazog")
                .airframe(Airframe.AMAZOG)
                .rotors("ROTOR_01_SPIN", "ROTOR_02_SPIN", "ROTOR_03_SPIN", "ROTOR_04_SPIN",
                        "ROTOR_05_SPIN", "ROTOR_06_SPIN", "ROTOR_07_SPIN", "ROTOR_08_SPIN")
                .stow("PAYLOAD_ROOT")
                .hull("FRAME", "CANOPY", "ELECTRONICS", "LANDING_GEAR", "PAYLOAD_ROOT")
                .shatter("FRAME", "CANOPY", "ARMS", "MOTORS", "LANDING_GEAR", "ELECTRONICS", "WIRING"));

        reg(new Builder("mavic")
                .airframe(Airframe.MAVIC)
                .rotors("13_ROTOR_FL", "13_ROTOR_FR", "13_ROTOR_RL", "13_ROTOR_RR")
                .gimbal("20_GIMBAL_yaw", "21_GIMBAL_roll", "22_GIMBAL_pitch")
                .mount(new Vec3(0.0, -0.02, 0.0))
                .hull("01_LOWER_fuselage", "02_UPPER_shell", "03_BATTERY_pack", "20_GIMBAL_yaw",
                        "11_LEG_FL", "11_LEG_FR", "11_LEG_RL", "11_LEG_RR")
                .shatter("01_LOWER_fuselage", "02_UPPER_shell", "03_BATTERY_pack",
                        "10_ARM_FL", "10_ARM_FR", "10_ARM_RL", "10_ARM_RR", "20_GIMBAL_yaw"));
    }

    private DroneModels() {
    }

    public static ResourceLocation rl(String id) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, id);
    }

    /** @return where an airframe's glTF lives. One asset per airframe; the parts are nodes inside it. */
    public static ResourceLocation asset(ResourceLocation id) {
        return rig(id).asset();
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null && RIGS.containsKey(parsed) ? parsed : DEFAULT;
    }

    public static boolean exists(ResourceLocation id) {
        return RIGS.containsKey(id);
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(RIGS.keySet());
    }

    public static String attitudeId(ResourceLocation id) {
        return rig(id).attitudeId();
    }

    /**
     * @return how this airframe flies. The flight model is handed this rather than a constant, which is
     *      what makes a one-block recon quad and a loaded eight-rotor lifter different aircraft rather than
     *      two pictures of one.
     */
    public static Airframe airframe(ResourceLocation id) {
        return rig(id).airframe();
    }

    /**
     * @return the hub node of every rotor, in the order {@link RotorDamage} indexes its bitmask by. The
     *      order is the order they were registered, so it is stable across a restart and across a resource
     *      reload, which a bitmask carried in entity data has to be.
     */
    public static List<String> rotorNodes(ResourceLocation id) {
        return rig(id).rotorNodes();
    }

    /**
     * @return the nodes of this airframe's model that are never drawn.
     */
    public static List<String> stowed(ResourceLocation id) {
        return rig(id).stowed();
    }

    /** @return this airframe's camera head, or null if it has no modelled gimbal. */
    @Nullable
    public static Gimbal gimbal(ResourceLocation id) {
        return rig(id).gimbal();
    }

    /**
     * @return where a slung payload attaches, in model units: the point its top face is held at.
     */
    public static Vec3 mount(ResourceLocation id) {
        return rig(id).mount();
    }

    /**
     * @return the size (per-axis, model units) of the whole airframe, cached. Measured across everything
     *      the asset draws, because a multirotor is as wide as its discs and a hitbox cut to the hull would
     *      stop at the arms. Falls back to a 1x1x1 box when the asset can't be read.
     */
    public static Vec3 dimensions(ResourceLocation id) {
        return DIMENSIONS.computeIfAbsent(id, i -> {
            Vec3 d = bounds(i).size();
            return (d.x > 1.0E-3 || d.y > 1.0E-3 || d.z > 1.0E-3) ? d : new Vec3(1.0, 1.0, 1.0);
        });
    }

    /**
     * @return the size of the solid body, model units: what a player walks into and what a shot has to
     *      hit, as opposed to {@link #dimensions} which is how much sky the aircraft occupies.
     */
    public static Vec3 hullSize(ResourceLocation id) {
        return rig(id).hullSize();
    }

    /**
     * @return the centre of the solid body relative to the model origin. The point the collision box turns
     *      about, which is not the centre of the whole span whenever the hull is not concentric with the discs.
     */
    public static Vec3 hullCenter(ResourceLocation id) {
        return rig(id).hullCenter();
    }

    /**
     * @return the geometric centre offset (model units) of the whole airframe relative to its origin,
     *      cached. Drone models sit feet-at-origin, so this is roughly {@code (0, height/2, 0)}.
     */
    public static Vec3 center(ResourceLocation id) {
        return CENTERS.computeIfAbsent(id, i -> bounds(i).center());
    }

    /**
     * @return every piece an airframe comes apart into when it is destroyed rather than merely shot down:
     *      the rotors, plus whatever structure the airframe declared worth seeing hit the ground separately.
     */
    public static List<ResourceLocation> pieces(ResourceLocation id) {
        return rig(id).pieces();
    }

    /**
     * @return a piece to stand in for one that could not be resolved: the first the default airframe
     *      declares. Debris carries its piece as a synced string, and a string that does not parse has to
     *      become <em>something</em>: a rotor of the stock drone is a better answer than a crash in a visual.
     */
    public static ResourceLocation fallbackPiece() {
        List<ResourceLocation> all = pieces(DEFAULT);
        return all.isEmpty() ? DEFAULT : all.get(0);
    }

    /**
     * @return where a piece's own geometry is centred, in model units. What a debris visual subtracts so
     *      that the entity carrying a rotor is in the middle of that rotor rather than at the hull origin it
     *      was modelled against.
     */
    public static Vec3 pieceCenter(ResourceLocation pieceId) {
        Piece piece = PIECES.get(pieceId);
        if (piece == null) {
            return Vec3.ZERO;
        }
        return GltfBounds.of(piece.asset())
                .subtree(piece.node())
                .center();
    }

    /** @return the asset and node a piece id names, or null if nothing registered it. */
    @Nullable
    public static Piece piece(ResourceLocation pieceId) {
        return PIECES.get(pieceId);
    }

    /**
     * @return where one piece sits on the airframe, in model units relative to the airframe's centre.
     */
    public static Vec3 pieceOffset(ResourceLocation id, ResourceLocation pieceId) {
        Piece piece = PIECES.get(pieceId);
        if (piece == null) {
            return Vec3.ZERO;
        }
        ObjBounds.Bounds box = GltfBounds.of(piece.asset()).subtree(piece.node());
        return box.any() ? box.center().subtract(center(id)) : Vec3.ZERO;
    }

    // --- registration ----------------------------------------------------------------------------

    private static void reg(Builder builder) {
        ResourceLocation key = rl(builder.id);
        ResourceLocation asset = ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
                "raw_models/drones/" + builder.id + ".gltf");
        GltfBounds graph = GltfBounds.of(asset);

        List<ResourceLocation> pieces = new ArrayList<>();
        for (String node : builder.rotors) {
            pieces.add(registerPiece(builder.id, asset, node));
        }
        for (String node : builder.shatter) {
            pieces.add(registerPiece(builder.id, asset, node));
        }

        Vec3 mount = builder.mount;
        if (!builder.stow.isEmpty()) {
            ObjBounds.Bounds bin = graph.subtree(builder.stow.get(0));
            Vec3 at = graph.origin(builder.stow.get(0));
            mount = bin.any() ? new Vec3(at.x, bin.maxY(), at.z) : at;
        }

        ObjBounds.Bounds hull = ObjBounds.Bounds.EMPTY;
        for (String node : builder.hull) {
            hull = merge(hull, graph.subtree(node));
        }
        if (!hull.any()) {
            hull = graph.bounds();
        }

        RIGS.put(key, new Rig(asset, mount, builder.attitudeId, builder.airframe,
                List.copyOf(builder.rotors), List.copyOf(pieces), List.copyOf(builder.stow),
                builder.gimbal, hull.size(), hull.center()));

        List<Vec3> pivots = new ArrayList<>(builder.rotors.size());
        for (String node : builder.rotors) {
            pivots.add(graph.origin(node));
        }
        Bones.rig(key, (float) builder.airframe.hoverRotorSpeed(), builder.rotors, pivots,
                node -> piecePath(builder.id, asset, node));
    }

    private static ResourceLocation registerPiece(String airframe, ResourceLocation asset, String node) {
        ResourceLocation id = piecePath(airframe, asset, node);
        PIECES.putIfAbsent(id, new Piece(asset, node));
        return id;
    }

    /** A piece's id: the airframe it belongs to and the node it is, lowercased. */
    private static ResourceLocation piecePath(String airframe, ResourceLocation asset, String node) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
                "drones/" + airframe + "/" + node.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_.-]", "_"));
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

    private static ObjBounds.Bounds bounds(ResourceLocation id) {
        return GltfBounds.of(rig(id).asset()).bounds();
    }

    private static Rig rig(ResourceLocation id) {
        Rig frame = RIGS.get(id);
        return frame != null ? frame : RIGS.get(DEFAULT);
    }

    /**
     * One registered drone: the asset it is drawn from, how it carries things, and which of its nodes mean
     * something to the game.
     *
     * @param mount payload attach point, model units (top centre of a slung load)
     * @param attitudeId how it orients to its heading, see {@link MissileAttitudeRegistry}
     * @param rotorNodes the hub of each rotor, in {@link RotorDamage} bit order
     * @param pieces what it shatters into, rotors first
     * @param stowed nodes of the model that are never drawn, see {@link #stowed}
     * @param gimbal its camera head, or null if it has no modelled one
     */
    private record Rig(ResourceLocation asset, Vec3 mount, String attitudeId, Airframe airframe,
                       List<String> rotorNodes, List<ResourceLocation> pieces, List<String> stowed,
                       @Nullable Gimbal gimbal, Vec3 hullSize, Vec3 hullCenter) {
    }

    /** One piece of a shattered airframe: which asset it comes from and which node of it it is. */
    public record Piece(ResourceLocation asset, String node) {
    }

    /** A modelled camera head: the three nested nodes it points with, outermost first. */
    public record Gimbal(String yaw, String roll, String pitch) {
    }

    /** Collects an airframe's declaration, so a registration reads as a description of the aircraft. */
    private static final class Builder {
        private final String id;
        private final List<String> rotors = new ArrayList<>();
        private final List<String> shatter = new ArrayList<>();
        private final List<String> hull = new ArrayList<>();
        private final List<String> stow = new ArrayList<>();
        private String attitudeId = DEFAULT_ATTITUDE;
        private Airframe airframe = Airframe.AMAZOG;
        private Vec3 mount = Vec3.ZERO;
        private Gimbal gimbal;

        private Builder(String id) {
            this.id = id;
        }

        private Builder airframe(Airframe airframe) {
            this.airframe = airframe;
            return this;
        }

        private Builder rotors(String... nodes) {
            this.rotors.addAll(List.of(nodes));
            return this;
        }

        private Builder shatter(String... nodes) {
            this.shatter.addAll(List.of(nodes));
            return this;
        }

        /** The nodes that make up the solid body: what the aircraft is, rather than how far its parts reach. */
        private Builder hull(String... nodes) {
            this.hull.addAll(List.of(nodes));
            return this;
        }

        private Builder mount(Vec3 mount) {
            this.mount = mount;
            return this;
        }

        /** Nodes the model has that the game does not want drawn. */
        private Builder stow(String... nodes) {
            this.stow.addAll(List.of(nodes));
            return this;
        }

        private Builder gimbal(String yaw, String roll, String pitch) {
            this.gimbal = new Gimbal(yaw, roll, pitch);
            return this;
        }
    }
}
