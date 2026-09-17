package com.wf.wfballistics.client.survey;

import com.wf.wfballistics.orbital.survey.SurveyTile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

public class SurveyScreen extends Screen {

    private static final int[] ZOOMS = {1, 2, 4, 8};
    private static final long STALE_TICKS = 24000L;

    private final long netId;
    private double centreX;
    private double centreZ;
    private int zoom = 1;
    private SurveyCache.Mode mode = SurveyCache.Mode.TERRAIN;
    private long clock;

    public SurveyScreen(long netId, int centreX, int centreZ) {
        super(Component.literal("Orbital Survey"));
        this.netId = netId;
        this.centreX = centreX;
        this.centreZ = centreZ;
        SurveyCache.bind(netId);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        clock = this.minecraft != null && this.minecraft.level != null
                ? this.minecraft.level.getGameTime() : clock;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xFF0B0F0B);
        double scale = ZOOMS[zoom] / (double) SurveyTile.BLOCKS_PER_PIXEL;
        int tileSide = (int) Math.max(1, SurveyTile.PIXELS * ZOOMS[zoom]);
        int span = (int) Math.ceil(Math.max(this.width, this.height) / (double) tileSide) + 2;
        int centreTileX = SurveyTile.tileOf(centreX);
        int centreTileZ = SurveyTile.tileOf(centreZ);

        for (int tz = -span; tz <= span; tz++) {
            for (int tx = -span; tx <= span; tx++) {
                int tileX = centreTileX + tx;
                int tileZ = centreTileZ + tz;
                SurveyCache.Held held = SurveyCache.tile(tileX, tileZ, mode);
                int sx = (int) ((tileX * (double) SurveyTile.BLOCKS - centreX) * scale) + this.width / 2;
                int sy = (int) ((tileZ * (double) SurveyTile.BLOCKS - centreZ) * scale) + this.height / 2;
                if (held == null || held.id() == null) {
                    g.fill(sx, sy, sx + tileSide, sy + tileSide, 0x2018201A);
                    continue;
                }
                long age = Math.max(0L, clock - held.stamped());
                int alpha = 255 - (int) Math.min(160L, age * 160L / STALE_TICKS);
                g.setColor(1.0f, 1.0f, 1.0f, alpha / 255.0f);
                g.blit(held.id(), sx, sy, 0.0f, 0.0f, tileSide, tileSide, tileSide, tileSide);
                g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
                g.renderOutline(sx, sy, tileSide, tileSide, 0x30FFFFFF);
            }
        }

        double worldX = centreX + (mouseX - this.width / 2.0) / scale;
        double worldZ = centreZ + (mouseY - this.height / 2.0) / scale;
        SurveyCache.Held under = SurveyCache.tile(SurveyTile.tileOf(worldX), SurveyTile.tileOf(worldZ), mode);
        String age = under == null ? "not imaged"
                : String.format(Locale.ROOT, "%ds old, %d%% imaged",
                        Math.max(0L, clock - under.stamped()) / 20L,
                        under.imaged() * 100 / (SurveyTile.PIXELS * SurveyTile.PIXELS));

        g.fill(0, 0, this.width, 22, 0xC0101410);
        g.drawString(this.font, Component.literal(String.format(Locale.ROOT,
                        "net %s   %s   x%d   %.0f, %.0f   %s",
                        Long.toHexString(netId), mode, ZOOMS[zoom], worldX, worldZ, age))
                .withStyle(ChatFormatting.GREEN), 6, 7, 0xFFFFFF, false);
        g.fill(0, this.height - 16, this.width, this.height, 0xC0101410);
        g.drawString(this.font, Component.literal(
                        "drag to pan   scroll to zoom   M for terrain/pan/elevation/change   dimmer is older")
                .withStyle(ChatFormatting.DARK_GRAY), 6, this.height - 12, 0xFFFFFF, false);
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double scale = ZOOMS[zoom] / (double) SurveyTile.BLOCKS_PER_PIXEL;
        centreX -= dragX / scale;
        centreZ -= dragY / scale;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        zoom = Mth.clamp(zoom + (scrollY > 0 ? 1 : -1), 0, ZOOMS.length - 1);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 77) {
            SurveyCache.Mode[] modes = SurveyCache.Mode.values();
            mode = modes[(mode.ordinal() + 1) % modes.length];
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        SurveyCache.clear();
        super.onClose();
    }
}
