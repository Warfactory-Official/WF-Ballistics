package com.wf.wflib.client.view;

import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.direct.DirectRenderer;
import com.wf.gemrender.direct.TextureSlots;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfPaletteLayout;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.texture.Paint;
import com.wf.gemrender.texture.VariantUv;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.wflib.WFLib;
import com.wf.wflib.armor.client.ArmorPieceClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * First-person player arms: skinned {@link #MODEL} (MineAssault {@code tools/ma.py player-arms}), joints
 * {@code arm_{right,left}_{upper,lower}} (forearm + fist rigid) + {@code arm_{side}_fist} (= hand frame at the fist
 * end), frames +Y down the bone; segment lengths read from the rest pose. Wears the local player's skin through texture slots. Render thread.
 *
 * <p>Worn glTF armour: {@code {side}_arm} node (vanilla arm part, glTF {@code S = diag(-1, -1, 1)}) remapped onto
 * the arm bones: across x {@link #ACROSS} about the vanilla arm axis, along piecewise at the elbow
 * ({@code [0, ELBOW]} -> upper, {@code [ELBOW, FIST]} -> forearm). AHF's {@code .ahf_ring} / {@code .ahf_bent}
 * joints (vertices sliced at that elbow) ride the forearm; absent => whole sleeve on the upper bone.
 */
public final class PlayerArms {
    public static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "models/arms/player_arms.glb");
    private static final String[] SIDES = {"right", "left"};
    /** Vanilla arm, part px / 16: elbow = AHF {@code player_wide.glb} elbow pivot; fist end = MA reach. */
    private static final float ELBOW = 4 / 16F, FIST = 10 / 16F;
    /** {@code player_arms.glb} shoulder section (MA {@code malib/arms.py} PROFILE) / vanilla 4 px. */
    private static final float ACROSS = 0.125F / (4 / 16F);
    private static final String RING = ".ahf_ring", BENT = ".ahf_bent";

    /** Shoulder -> elbow; elbow -> fist end. */
    public record Lengths(float upper, float forearm) { }

    private static ModelCache.Handle<GemRenderGltfModel> handle;
    private static PlayerArms cached;

    private final GemRenderGltfModel model;
    private final Matrix4f[] palette;
    private final Matrix4f[] rest;
    /** [side][upper, lower, fist] node slots. */
    private final int[][] joints = new int[2][3];
    public final Lengths lengths;
    private final Map<GemRenderGltfModel, Matrix4f[]> sleeves = new WeakHashMap<>();

    private PlayerArms(GemRenderGltfModel model) {
        this.model = model;
        palette = model.newPalette();
        rest = model.restPalette();
        NodeTable table = model.layout().nodeTable();
        String[] names = {"upper", "lower", "fist"};
        for (int side = 0; side < 2; side++) {
            for (int j = 0; j < 3; j++) {
                int slot = table.slotOfName("arm_" + SIDES[side] + "_" + names[j]);
                if (slot < 0) throw new IllegalStateException(MODEL + ": no node arm_" + SIDES[side] + "_" + names[j]);
                joints[side][j] = slot;
            }
        }
        lengths = new Lengths(table.restTranslation(joints[0][1], 1), table.restTranslation(joints[0][2], 1));
    }

    /** Null until loaded. */
    @Nullable
    public static PlayerArms get() {
        if (handle == null) handle = GemRenderModels.handle(MODEL);
        GemRenderGltfModel m = handle.get();
        if (m == null) return null;
        if (cached == null || cached.model != m) cached = new PlayerArms(m);
        return cached;
    }

    /** Joint globals of {@code side}, view space. */
    public void pose(int side, Matrix4fc upper, Matrix4fc lower) {
        int[] j = joints[side];
        palette[j[0]].set(upper);
        palette[j[1]].set(lower);
        palette[j[2]].set(lower).translate(0, lengths.forearm(), 0);
    }

    /** Both arms in {@code player}'s skin + worn glTF armour arms; a skin sleeve only while its layer shows, uncovered. */
    public void submit(Matrix4f base, int light, AbstractClientPlayer player) {
        boolean[] covered = new boolean[2];
        for (ItemStack worn : player.getArmorSlots()) {
            GemRenderGltfModel armour = ArmorPieceClient.model(worn);
            if (armour != null) {
                armour(armour, base, light, covered);
            }
        }
        for (GltfPaletteLayout.SkinBlock block : model.layout().skins()) {
            int[] slots = block.jointSlots();
            for (int k = 0; k < slots.length; k++) {
                palette[block.base() + k].set(palette[slots[k]]).mul(block.inverseBind()[k]);
            }
        }
        PlayerSkin skin = player.getSkin();
        ResourceLocation texture = skin.texture();
        boolean slim = skin.model() == PlayerSkin.Model.SLIM;
        boolean rightSleeve = !covered[0] && player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE);
        boolean leftSleeve = !covered[1] && player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE);
        TextureSlots slots = slot -> slot.endsWith("_slim") != slim ? null
                : switch (slim ? slot.substring(0, slot.length() - "_slim".length()) : slot) {
                    case "player_arm" -> texture;
                    case "player_sleeve_right" -> rightSleeve ? texture : null;
                    case "player_sleeve_left" -> leftSleeve ? texture : null;
                    default -> throw new IllegalStateException(MODEL + ": unknown texture slot " + slot);
                };
        DirectRenderer.submit(model, palette, null, base, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.HAND,
                VariantUv.NONE, Paint.NONE, 0, rest, slots);
    }

    /** {@code armour}'s arm nodes on the posed bones; other nodes collapsed. */
    private void armour(GemRenderGltfModel armour, Matrix4f base, int light, boolean[] covered) {
        NodeTable table = armour.layout().nodeTable();
        Matrix4f[] out = sleeves.computeIfAbsent(armour, GemRenderGltfModel::newPalette);
        for (Matrix4f m : out) {
            m.zero();
        }
        boolean any = false;
        for (int side = 0; side < 2; side++) {
            String name = SIDES[side] + "_arm";
            int owner = table.slotOfName(name);
            if (owner < 0) {
                continue;
            }
            any = covered[side] = true;
            float axis = (side == 0 ? -1 : 1) / 16F;
            out[owner].set(palette[joints[side][0]]).scale(ACROSS, lengths.upper() / ELBOW, ACROSS)
                    .translate(-axis, 0, 0).scale(-1, -1, 1);
            int bent = table.slotOfName(name + BENT);
            if (bent >= 0) {
                out[bent].set(palette[joints[side][1]]).scale(ACROSS, lengths.forearm() / (FIST - ELBOW), ACROSS)
                        .translate(-axis, -ELBOW, 0).scale(-1, -1, 1);
                out[table.slotOfName(name + RING)].set(out[bent]);
            }
        }
        if (any) {
            DirectRenderer.submit(armour, out, null, base, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.HAND,
                    VariantUv.NONE, Paint.NONE, 0, out);
        }
    }
}
