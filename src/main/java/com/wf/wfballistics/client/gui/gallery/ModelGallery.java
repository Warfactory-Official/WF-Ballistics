package com.wf.wfballistics.client.gui.gallery;

import com.wf.gemrender.gltf.AnimationDrive;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.ModModels;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.client.model.GlyphidModel;
import com.wf.wfballistics.client.model.MineRigs;
import com.wf.wfballistics.client.model.PartRigs;
import com.wf.wfballistics.door.DoorType;
import com.wf.wfballistics.door.client.DoorRigs;
import com.wf.wfballistics.drone.DroneModels;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.mine.MineCamo;
import com.wf.wfballistics.mine.MineModels;
import com.wf.wfballistics.probe.client.ProbeModels;
import com.wf.wfballistics.util.GltfBounds;
import com.wf.wfballistics.util.ObjBounds;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Every model this mod has, gathered out of the registries that hold them. */
public final class ModelGallery {

    private ModelGallery() {
    }

    /** One tab: a registry's name and everything in it, in the registry's own order. */
    public record Category(String name, List<GalleryEntry> entries) {
    }

    /** Gathers the whole catalogue. */
    public static List<Category> build() {
        List<Category> categories = new ArrayList<>();
        add(categories, "missiles", missiles());
        add(categories, "mines", mines());
        add(categories, "drones", drones());
        add(categories, "pieces", pieces());
        add(categories, "doors", doors());
        add(categories, "glyphids", glyphids());
        add(categories, "props", props());
        add(categories, "probe", probe());
        return List.copyOf(categories);
    }

    private static void add(List<Category> categories, String name, List<GalleryEntry> entries) {
        if (!entries.isEmpty()) {
            categories.add(new Category(name, List.copyOf(entries)));
        }
    }

    // --- missiles: baked vanilla models, one json + obj each --------------------------------------

    private static List<GalleryEntry> missiles() {
        List<GalleryEntry> entries = new ArrayList<>();
        for (ResourceLocation id : MissileModels.ids()) {
            entries.add(BakedEntry.missile(id, () -> missileInfo(id)));
        }
        return entries;
    }

    private static List<Component> missileInfo(ResourceLocation id) {
        double length = MissileModels.length(id);
        Vec3 size = MissileModels.dimensions(id);
        Vec3 center = MissileModels.center(id);
        double baseY = center.y - size.y / 2.0;
        List<Component> lines = new ArrayList<>();
        lines.add(name(id));
        lines.add(grey(String.format("length %.3f", length)));
        lines.add(grey(String.format("dim  %.3f x %.3f x %.3f", size.x, size.y, size.z)));
        lines.add(grey(String.format("center %.3f, %.3f, %.3f", center.x, center.y, center.z)));
        lines.add(Component.literal(String.format("baseY %.3f", baseY))
                .append(Component.literal("  (0 = base on origin)")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .withStyle(Math.abs(baseY) < 1.0E-3 ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(grey("model  " + MissileModels.model(id)));
        if (!ModModels.baked(id)) {
            lines.add(Component.literal("did not bake - drawn as " + MissileModels.defaultId())
                    .withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    // --- mines: imported glTF, with camouflage as packed variants ---------------------------------

    private static List<GalleryEntry> mines() {
        List<GalleryEntry> entries = new ArrayList<>();
        for (ResourceLocation id : MineModels.ids()) {
            entries.add(GltfEntry.of(id, () -> MineRigs.model(id))
                    .fit(() -> new GltfEntry.Fit(MineModels.center(id), longest(MineModels.size(id))))
                    .clips(() -> held("laid", MineRigs.empty(id), "firing"))
                    .variants(() -> camos(id))
                    .info(() -> mineInfo(id))
                    .build());
        }
        return entries;
    }

    /** The finishes this mine has artwork for. Slot 0 is the base olive and is always present. */
    private static List<GalleryEntry.Variant> camos(ResourceLocation id) {
        List<GalleryEntry.Variant> variants = new ArrayList<>();
        for (MineCamo camo : MineCamo.values()) {
            int index = MineRigs.variant(id, camo);
            if (camo == MineCamo.DEFAULT || index != 0) {
                variants.add(new GalleryEntry.Variant(camo.id(), index));
            }
        }
        return variants.size() > 1 ? variants : List.of();
    }

    private static List<Component> mineInfo(ResourceLocation id) {
        Vec3 size = MineModels.size(id);
        Vec3 center = MineModels.center(id);
        List<Component> lines = new ArrayList<>();
        lines.add(name(id));
        lines.add(grey(String.format("size  %.3f x %.3f x %.3f", size.x, size.y, size.z)));
        lines.add(grey(String.format("center %.3f, %.3f, %.3f", center.x, center.y, center.z)));
        lines.add(grey(String.format("buries to %.0f%%", MineModels.buryFraction(id) * 100.0)));
        int canisters = MineModels.canisterNodes(id);
        if (canisters > 0) {
            lines.add(grey(canisters + " canister node(s)"));
        }
        lines.add(grey("asset  " + MineModels.asset(id)));
        return lines;
    }

    // --- drones: imported airframes ---------------------------------------------------------------

    private static List<GalleryEntry> drones() {
        List<GalleryEntry> entries = new ArrayList<>();
        for (ResourceLocation id : DroneModels.ids()) {
            entries.add(GltfEntry.of(id, () -> model(PartRigs.drone(id)))
                    .fit(() -> new GltfEntry.Fit(DroneModels.center(id),
                            longest(DroneModels.dimensions(id))))
                    .clips(() -> droneClips(id))
                    .info(() -> droneInfo(id))
                    .build());
        }
        return entries;
    }

    private static List<GalleryEntry.Clip> droneClips(ResourceLocation id) {
        PartRigs.Rig rig = PartRigs.drone(id);
        if (rig == null) {
            return List.of();
        }
        List<GalleryEntry.Clip> clips = new ArrayList<>();
        clips.add(new GalleryEntry.Clip("parked", null));
        if (rig.spin() != null) {
            clips.add(new GalleryEntry.Clip("rotors", rig.spin()));
        }
        if (rig.stow() != null) {
            clips.add(new GalleryEntry.Clip("stow", rig.stow()));
        }
        return clips.size() > 1 ? clips : List.of();
    }

    private static List<Component> droneInfo(ResourceLocation id) {
        Vec3 span = DroneModels.dimensions(id);
        Vec3 hull = DroneModels.hullSize(id);
        Vec3 mount = DroneModels.mount(id);
        List<Component> lines = new ArrayList<>();
        lines.add(name(id));
        lines.add(grey("attitude  " + DroneModels.attitudeId(id)));
        lines.add(grey(String.format("span  %.3f x %.3f x %.3f", span.x, span.y, span.z)));
        lines.add(grey(String.format("hull  %.3f x %.3f x %.3f", hull.x, hull.y, hull.z)));
        lines.add(grey(String.format("mount %.3f, %.3f, %.3f", mount.x, mount.y, mount.z)));
        lines.add(grey(DroneModels.rotorNodes(id)
                .size() + " rotor(s), " + DroneModels.pieces(id)
                .size() + " piece(s), " + DroneModels.stowed(id)
                .size() + " stowed"));
        lines.add(grey("asset  " + DroneModels.asset(id)));
        return lines;
    }

    // --- pieces: one node of an airframe, which is what wreckage is -------------------------------

    private static List<GalleryEntry> pieces() {
        List<GalleryEntry> entries = new ArrayList<>();
        for (ResourceLocation drone : DroneModels.ids()) {
            for (ResourceLocation id : DroneModels.pieces(drone)) {
                DroneModels.Piece piece = DroneModels.piece(id);
                if (piece == null) {
                    continue;
                }
                entries.add(GltfEntry.of(id, () -> model(PartRigs.piece(id)))
                        .label(tail(id))
                        .fit(() -> subtree(piece))
                        .clips(() -> pieceClips(id))
                        .info(() -> pieceInfo(drone, id, piece))
                        .build());
            }
        }
        return entries;
    }

    private static GltfEntry.Fit subtree(DroneModels.Piece piece) {
        ObjBounds.Bounds box = GltfBounds.of(piece.asset())
                .subtree(piece.node());
        return box.any() ? new GltfEntry.Fit(box.center(), longest(box.size()))
                : new GltfEntry.Fit(Vec3.ZERO, 1.0);
    }

    private static List<GalleryEntry.Clip> pieceClips(ResourceLocation id) {
        PartRigs.Rig rig = PartRigs.piece(id);
        if (rig == null || rig.piece() == null) {
            return List.of();
        }
        AnimationDrive drive = rig.piece();
        return List.of(new GalleryEntry.Clip("intact", AnimationDrive.ranged(drive.clip(), 0.0f, 0.0f)),
                new GalleryEntry.Clip("fading", drive));
    }

    private static List<Component> pieceInfo(ResourceLocation drone, ResourceLocation id,
                                             DroneModels.Piece piece) {
        ObjBounds.Bounds box = GltfBounds.of(piece.asset())
                .subtree(piece.node());
        Vec3 size = box.any() ? box.size() : Vec3.ZERO;
        List<Component> lines = new ArrayList<>();
        lines.add(name(id));
        lines.add(grey("of  " + drone));
        lines.add(grey("node  " + piece.node()));
        lines.add(grey(String.format("size  %.3f x %.3f x %.3f", size.x, size.y, size.z)));
        Vec3 offset = DroneModels.pieceOffset(drone, id);
        lines.add(grey(String.format("sits at %.3f, %.3f, %.3f on the airframe",
                offset.x, offset.y, offset.z)));
        if (!box.any()) {
            lines.add(Component.literal("the asset has no such node")
                    .withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    // --- doors: fifteen types, each with its own skins ---------------------------------------------

    private static List<GalleryEntry> doors() {
        List<GalleryEntry> entries = new ArrayList<>();
        for (DoorType type : DoorType.values()) {
            int skins = Math.max(1, type.skins());
            for (int index = 0; index < skins; index++) {
                int skin = index;
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID,
                        "door/" + type.id() + "/" + skin);
                entries.add(GltfEntry.of(id, () -> doorModel(type, skin))
                        .label(skins > 1 ? type.id() + " " + skin : type.id())
                        .base(() -> {
                            DoorRigs.DoorModel door = DoorRigs.model(type, skin);
                            return door == null ? null : door.base();
                        })
                        .clips(() -> doorClips(type, skin))
                        .info(() -> doorInfo(type, skin))
                        .build());
            }
        }
        return entries;
    }

    @Nullable
    private static GemRenderGltfModel doorModel(DoorType type, int skin) {
        DoorRigs.DoorModel door = DoorRigs.model(type, skin);
        return door == null ? null : door.model();
    }

    private static List<GalleryEntry.Clip> doorClips(DoorType type, int skin) {
        DoorRigs.DoorModel door = DoorRigs.model(type, skin);
        if (door == null || door.open()
                .clip() == null) {
            return List.of();
        }
        return List.of(new GalleryEntry.Clip("shut", AnimationDrive.ranged(door.open()
                        .clip(), 0.0f, 0.0f)),
                new GalleryEntry.Clip("opening", door.open()),
                new GalleryEntry.Clip("closing", door.close()));
    }

    private static List<Component> doorInfo(DoorType type, int skin) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(type.id() + (type.skins() > 1 ? "  skin " + skin + " of " + type.skins()
                : ""))
                .withStyle(ChatFormatting.WHITE));
        lines.add(grey(String.format("opens in %d ticks (%.1fs)", type.timeToOpen(),
                type.timeToOpen() / 20.0f)));
        lines.add(grey("box  " + Arrays.toString(type.dimensions())));
        if (type.extraDimensions().length > 0) {
            lines.add(grey("plus " + type.extraDimensions().length + " extra box(es)"));
        }
        lines.add(grey("offset " + type.blockOffset() + " block(s) below the placed one"));
        return lines;
    }

    // --- glyphids: one rig, the castes packed into its sheet ---------------------------------------

    private static List<GalleryEntry> glyphids() {
        List<GalleryEntry> entries = new ArrayList<>();
        entries.add(glyphid("body", GlyphidModel::body));
        entries.add(glyphid("infested", GlyphidModel::infested));
        return entries;
    }

    private static GalleryEntry glyphid(String name, Supplier<GlyphidModel.Skin> skin) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "glyphid/" + name);
        return GltfEntry.of(id, () -> {
                    GlyphidModel.Skin resolved = skin.get();
                    return resolved == null ? null : resolved.model();
                })
                .label(name)
                .clips(() -> glyphidClips(skin.get()))
                .variants(ModelGallery::castes)
                .info(() -> List.of(name(id), grey("one rig, " + GlyphidCaste.VALUES.length
                        + " castes packed into its sheet")))
                .build();
    }

    private static List<GalleryEntry.Clip> glyphidClips(@Nullable GlyphidModel.Skin skin) {
        if (skin == null) {
            return List.of();
        }
        List<GalleryEntry.Clip> clips = new ArrayList<>();
        clips.add(new GalleryEntry.Clip("still", null));
        clips.add(new GalleryEntry.Clip("walk", skin.walk()));
        clips.add(new GalleryEntry.Clip("bite", skin.bite()));
        return clips;
    }

    private static List<GalleryEntry.Variant> castes() {
        List<GalleryEntry.Variant> variants = new ArrayList<>(GlyphidCaste.VALUES.length);
        for (GlyphidCaste caste : GlyphidCaste.VALUES) {
            variants.add(new GalleryEntry.Variant(caste.name()
                    .toLowerCase(java.util.Locale.ROOT), caste.ordinal()));
        }
        return variants;
    }

    // --- props: the loose baked models the effects and the item renderers draw ---------------------

    private static List<GalleryEntry> props() {
        List<GalleryEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, PartialModel> prop : ModModels.props()
                .entrySet()) {
            entries.add(baked(prop.getKey(), prop.getValue(), "effect or prop"));
        }
        for (Map.Entry<ResourceLocation, PartialModel> part : ModModels.parts()
                .entrySet()) {
            entries.add(baked(part.getKey(), part.getValue(), "a missile's moving part"));
        }
        return entries;
    }

    private static GalleryEntry baked(ResourceLocation id, PartialModel partial, String what) {
        return BakedEntry.measured(id, tail(id), partial::get, () -> List.of(name(id), grey(what)));
    }

    // --- probe: standalone models a probe panel draws ----------------------------------------------

    private static List<GalleryEntry> probe() {
        List<GalleryEntry> entries = new ArrayList<>();
        for (ResourceLocation id : ProbeModels.declared()) {
            entries.add(BakedEntry.measured(id, id.getPath(), () -> ProbeModels.baked(id),
                    () -> List.of(name(id), grey("declared for a probe panel"))));
        }
        return entries;
    }

    // --- shared -----------------------------------------------------------------------------------

    /**
     * A clip list of "held at the first frame" and "played", for a model with exactly one thing it does.
     *
     * @return empty when the model has no such clip, which poses it at rest and hides the control
     */
    private static List<GalleryEntry.Clip> held(String stillName, @Nullable GltfAnimation clip,
                                                String playingName) {
        if (clip == null) {
            return List.of();
        }
        return List.of(new GalleryEntry.Clip(stillName, AnimationDrive.ranged(clip, 0.0f, 0.0f)),
                new GalleryEntry.Clip(playingName, AnimationDrive.ranged(clip, 0.0f, 1.0f)));
    }

    @Nullable
    private static GemRenderGltfModel model(@Nullable PartRigs.Rig rig) {
        return rig == null ? null : rig.model();
    }

    /** @return the last segment of an id's path, which is the part that tells one entry from the next. */
    private static String tail(ResourceLocation id) {
        String path = id.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static double longest(Vec3 size) {
        return Math.max(size.x, Math.max(size.y, size.z));
    }

    private static Component name(ResourceLocation id) {
        return Component.literal(id.toString())
                .withStyle(ChatFormatting.WHITE);
    }

    private static Component grey(String text) {
        return Component.literal(text)
                .withStyle(ChatFormatting.GRAY);
    }
}
