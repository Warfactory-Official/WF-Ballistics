package com.wf.wflib.ammo.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.gemrender.direct.GemRenderItemRenderer;
import com.wf.gemrender.direct.ItemAppearance;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.WFLib;
import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.ammo.AmmoVariant;
import com.wf.wflib.ammo.Calibre;
import com.wf.wflib.ammo.ProjectileLook;
import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.client.flywheel.InstancedTrailEffect;
import com.wf.wflib.round.client.ClientRounds;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/** Cartridge item renderer (GemRender direct path) + trails behind entity-less rounds whose look has one. */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AmmoClient {
    /** Cartridge longest side, item cell units. */
    static final float FILL = 0.9f;
    private static GemRenderItemRenderer renderer;
    /** Rounds with a trail (identity: adoption re-keys a round). */
    private static final java.util.Set<ClientRounds.Round> TRAILED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static java.util.Set<ClientRounds.Round> liveNow = java.util.Set.of();

    private AmmoClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(AmmoModels::init);
        NeoForge.EVENT_BUS.addListener(AmmoClient::onClientTick);
    }

    public static GemRenderItemRenderer renderer() {
        if (renderer == null) {
            renderer = new GemRenderItemRenderer(new Appearance());
        }
        return renderer;
    }

    /** Drawn length, blocks: whole round (rockets: the projectile). */
    static float length(Calibre c, ProjectileLook look) {
        return (c.hasCase() ? c.overallMm() : look.lengthMm()) * 0.001f;
    }

    private static final class Appearance implements ItemAppearance {
        @Nullable
        @Override
        public GemRenderGltfModel model(ItemStack stack, ItemDisplayContext context) {
            AmmoVariant v = AmmoItems.variantOf(stack);
            return v == null ? null : AmmoModels.cartridge(v.id());
        }

        /** Nose up-right in the cell, centred, longest side {@link #FILL}. */
        @Override
        public void transform(ItemStack stack, ItemDisplayContext context, PoseStack pose) {
            AmmoVariant v = AmmoItems.variantOf(stack);
            Calibre c = Ammo.calibre(v.calibre());
            float l = length(c, Ammo.look(v.id()));
            pose.mulPose(Axis.ZP.rotationDegrees(-45));
            pose.mulPose(Axis.XP.rotationDegrees(-90));
            pose.mulPose(Axis.ZP.rotationDegrees(30));
            float s = FILL / l;
            pose.scale(s, s, s);
            pose.translate(0, 0, -l / 2);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || !AmmoModels.anyTrail() || !FlywheelEffectManager.isAvailable(level)) {
            TRAILED.clear();
            return;
        }
        java.util.Set<ClientRounds.Round> now = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        now.addAll(ClientRounds.live());
        liveNow = now;
        for (ClientRounds.Round r : now) {
            ProjectileLook look = Ammo.lookOrNull(r.preset().id());
            if (look == null || !look.visible() || !look.trail() || !TRAILED.add(r)) {
                continue;
            }
            FlywheelEffectManager.spawn(new InstancedTrailEffect(level, new InstancedTrailEffect.Source() {
                @Override
                public boolean gone() {
                    return r.ended() || !liveNow.contains(r);
                }

                @Override
                public Vec3 emitPoint() {
                    return r.previous();
                }

                @Override
                public boolean laying() {
                    return !r.resting();
                }
            }));
        }
        TRAILED.retainAll(now);
    }
}
