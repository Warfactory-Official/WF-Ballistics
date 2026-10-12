package com.wf.wflib.ui;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** wflib's own subjects and signals. {@code ui.time}/{@code ui.dt} are fixed slots, not signals. */
public final class UiSignals {
    public static final Subject<UiEval> UI = Subject.register("ui", UiEval.class);
    public static final Subject<LocalPlayer> PLAYER = Subject.register("player", LocalPlayer.class,
            () -> Minecraft.getInstance().player);
    public static final Subject<ClientLevel> WORLD = Subject.register("world", ClientLevel.class,
            () -> Minecraft.getInstance().level);
    public static final Subject<Camera> CAMERA = Subject.register("camera", Camera.class,
            () -> Minecraft.getInstance().gameRenderer.getMainCamera());

    private static boolean registered;

    private UiSignals() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        Signals.num(UI, "width", ev -> ev.width);
        Signals.num(UI, "height", ev -> ev.height);
        Signals.num(UI, "partial", ev -> ev.partialTick);
        Signals.num(UI, "gui_scale", ev -> Minecraft.getInstance().getWindow().getGuiScale());
        Signals.num(UI, "scale", ev -> ev.scale);
        Signals.num(UI, "px", ev -> ev.pixel);
        Signals.bool(UI, "hide_gui", ev -> Minecraft.getInstance().options.hideGui);

        Signals.num(PLAYER, "health", LocalPlayer::getHealth);
        Signals.num(PLAYER, "max_health", LocalPlayer::getMaxHealth);
        Signals.num(PLAYER, "x", LocalPlayer::getX);
        Signals.num(PLAYER, "y", LocalPlayer::getY);
        Signals.num(PLAYER, "z", LocalPlayer::getZ);
        Signals.num(PLAYER, "yaw", p -> Mth.wrapDegrees(p.getViewYRot(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true))));
        Signals.num(PLAYER, "pitch", p -> p.getViewXRot(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true)));
        Signals.bool(PLAYER, "sneaking", LocalPlayer::isShiftKeyDown);
        Signals.bool(PLAYER, "passenger", LocalPlayer::isPassenger);
        Signals.bool(PLAYER, "spectator", LocalPlayer::isSpectator);

        Signals.num(WORLD, "day_time", ClientLevel::getDayTime);
        Signals.num(WORLD, "hour", w -> (Math.floorMod(w.getDayTime(), 24000L) / 1000L + 6L) % 24L);
        Signals.num(WORLD, "minute", w -> Math.floorMod(w.getDayTime(), 24000L) % 1000L * 60L / 1000L);
        Signals.num(WORLD, "game_time", ClientLevel::getGameTime);

        Signals.num(CAMERA, "yaw", Camera::getYRot);
        Signals.num(CAMERA, "pitch", Camera::getXRot);
        Signals.num(CAMERA, "x", c -> c.getPosition().x);
        Signals.num(CAMERA, "y", c -> c.getPosition().y);
        Signals.num(CAMERA, "z", c -> c.getPosition().z);
        Signals.bool(CAMERA, "first_person", c -> Minecraft.getInstance().options.getCameraType().isFirstPerson());
        Signals.bool(CAMERA, "detached", Camera::isDetached);
    }
}
