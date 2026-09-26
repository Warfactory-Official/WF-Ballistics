package com.wf.wflib.tv.client;

import com.wf.wflib.missile.MissileSeeker;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraLink;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.tv.TvConnectPacket;
import com.wf.wflib.tv.TvGuidance;
import com.wf.wflib.tv.TvLinkPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/** Operator end of a TV link: which round, mouse -> seeker gimbal ({@link CameraLink}), view mode. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class TvClient {

    public static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "tv_link");

    /** Seeker gimbal limit off the round's heading, degrees. Mouse past it winds nothing up. */
    static final double GIMBAL_LIMIT = 60.0;
    /** Ticks the signal-lost card stays after the round is gone. */
    private static final int LOST_HOLD = 20;
    /** {@code LocalPlayer.turn}'s own mouse-to-degrees factor. */
    private static final double TURN_SCALE = 0.15;

    private static int missileId;
    private static boolean full = true;
    private static int lostTicks;
    private static int linkedTicks;

    private TvClient() {
    }

    /** @return true while the mouse flies a round. */
    public static boolean active() {
        return missileId != 0 && lostTicks == 0;
    }

    static int missileId() {
        return missileId;
    }

    static boolean full() {
        return full;
    }

    static boolean lost() {
        return lostTicks > 0;
    }

    public static void accept(TvLinkPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (packet.lost()) {
            if (packet.missileId() == missileId) {
                lose();
            }
            return;
        }
        if (packet.missileId() == 0) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("wflib.tv.none"), true);
            }
            return;
        }
        link(packet.missileId());
    }

    private static void link(int id) {
        Minecraft mc = Minecraft.getInstance();
        missileId = id;
        lostTicks = 0;
        linkedTicks = 0;
        Vec3 seed = heading(mc.level == null ? null : mc.level.getEntity(id));
        if (seed == null) {
            CameraLink.open(id, mc.player.getYRot(), mc.player.getXRot());
        } else {
            CameraLink.open(id, yawOf(seed), pitchOf(seed));
        }
    }

    private static void lose() {
        CameraLink.close();
        lostTicks = LOST_HOLD;
    }

    private static void unlink() {
        if (CameraLink.active() && CameraLink.feedId() == missileId) {
            CameraLink.close();
        }
        missileId = 0;
        lostTicks = 0;
    }

    /** Mouse turn, as {@code LocalPlayer.turn} would take it. @return true if the TV link consumed it. */
    public static boolean steer(double yaw, double pitch) {
        if (!active()) {
            return false;
        }
        double gain = TURN_SCALE / Math.max(1.0, CameraLink.zoom());
        CameraLink.pan(yaw * gain, pitch * gain);
        clampToGimbal();
        return true;
    }

    /** Keep the order inside {@link #GIMBAL_LIMIT} of the heading the client sees. */
    private static void clampToGimbal() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 heading = heading(mc.level == null ? null : mc.level.getEntity(missileId));
        if (heading == null) {
            return;
        }
        Vec3 aim = MissileSeeker.sight(CameraLink.yaw(), CameraLink.pitch());
        double cos = Math.max(-1.0, Math.min(1.0, heading.dot(aim)));
        double limit = Math.toRadians(GIMBAL_LIMIT);
        if (Math.acos(cos) <= limit) {
            return;
        }
        Vec3 side = aim.subtract(heading.scale(cos));
        if (side.lengthSqr() < 1.0E-10) {
            return;
        }
        Vec3 clamped = heading.scale(Math.cos(limit)).add(side.normalize().scale(Math.sin(limit)));
        CameraLink.aim(yawOf(clamped), pitchOf(clamped));
    }

    private static Vec3 heading(Entity entity) {
        if (!(entity instanceof MissileEntity missile)) {
            return null;
        }
        Vec3 v = missile.getDeltaMovement();
        return v.lengthSqr() < 1.0E-6 ? null : v.normalize();
    }

    private static float yawOf(Vec3 dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    private static float pitchOf(Vec3 dir) {
        return (float) Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        while (TvKeys.LINK.consumeClick()) {
            if (missileId != 0) {
                unlink();
            } else {
                PacketDistributor.sendToServer(new TvConnectPacket());
            }
        }
        while (TvKeys.VIEW.consumeClick()) {
            full = !full;
        }
        if (lostTicks > 0) {
            if (--lostTicks == 0) {
                missileId = 0;
            }
            return;
        }
        if (missileId == 0) {
            return;
        }
        // Another screen took the gimbal (a drone feed), or the operator died.
        if (!mc.player.isAlive() || !CameraLink.active() || CameraLink.feedId() != missileId) {
            unlink();
            return;
        }
        linkedTicks++;
        CameraFeed feed = CameraFeedCache.feed(missileId);
        long age = feed == null ? linkedTicks : mc.level.getGameTime() - feed.gameTime();
        if (age > CameraFeed.STALE_TICKS) {
            lose();
            return;
        }
        clampToGimbal();
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (active() && Minecraft.getInstance().screen == null && event.getScrollDeltaY() != 0.0) {
            CameraLink.zoomBy(event.getScrollDeltaY());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        missileId = 0;
        lostTicks = 0;
    }

    @SubscribeEvent
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, new TvOverlay());
    }
}
