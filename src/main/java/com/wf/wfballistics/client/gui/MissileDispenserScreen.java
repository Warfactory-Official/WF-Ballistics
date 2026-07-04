package com.wf.wfballistics.client.gui;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileEntity.Phase;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.block.entity.LaunchConfig;
import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import com.wf.wfballistics.flight.ArrivalEstimator;
import com.wf.wfballistics.flight.FlightStageRegistry;
import com.wf.wfballistics.flight.LoiterStage;
import com.wf.wfballistics.menu.MissileDispenserMenu;
import com.wf.wfballistics.network.SpawnMissilePacket;
import com.wf.wfballistics.network.WFNetwork;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;


public class MissileDispenserScreen extends AbstractContainerScreen<MissileDispenserMenu> {

    private static final List<ResourceLocation> MODELS = new ArrayList<>(MissileModels.ids());
    private static final List<ResourceLocation> WARHEADS = new ArrayList<>(WarheadRegistry.ids());
    private static final List<ResourceLocation> ASCENT_STAGES = new ArrayList<>(FlightStageRegistry.ids(Phase.ASCEND));
    private static final List<ResourceLocation> CRUISE_STAGES = new ArrayList<>(FlightStageRegistry.ids(Phase.CRUISE));
    private static final List<ResourceLocation> ATTACK_STAGES = new ArrayList<>(FlightStageRegistry.ids(Phase.ATTACK));
    private static final String[] CRUISE_LABELS = {"Terrain Follow", "High Altitude"};

    private static final int PAD = 8;
    private static final int W_FULL = 204;               // imageWidth - 2*PAD
    private static final int GAP = 6;                    // gap between columns
    private static final int ROW_GAP = 4;                // gap between rows
    private static final int BTN_H = 18;
    private static final int BOX_H = 16;
    private static final int LABEL_H = 10;               // room reserved above a labelled box row
    private static final int START_Y = 16;               // first widget, below the title bar
    private static final int THIRD = (W_FULL - 2 * GAP) / 3;
    private static final int HALF = (W_FULL - GAP) / 2;

    private final List<EditBox> editBoxes = new ArrayList<>();
    private final List<String> editHints = new ArrayList<>();

    private int modelIndex;
    private int warheadIndex;
    private int cruiseIndex; // 0 = terrain follow, 1 = high altitude
    private int ascentStageIndex; // index into ASCENT_STAGES (0 = the phase default)
    private int cruiseStageIndex;
    private int attackStageIndex;
    private boolean startInCruise;
    private boolean startArmed;

    private boolean seeded;
    private LaunchConfig seed;

    private Button modelButton;
    private Button warheadButton;
    private Button cruiseButton;
    private Button ascentStageButton;
    private Button cruiseStageButton;
    private Button attackStageButton;
    private Button inCruiseButton;
    private Button armedButton;
    private EditBox targetX;
    private EditBox targetY;
    private EditBox targetZ;
    private EditBox offsetBox;
    private EditBox altitudeBox;
    private EditBox fragmentBox;
    private EditBox speedBox;
    private EditBox turnRateBox;
    private EditBox healthBox;

    public MissileDispenserScreen(MissileDispenserMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 220;
        this.imageHeight = 274;
        this.modelIndex = Math.max(0, MODELS.indexOf(MissileModels.DEFAULT));
        this.warheadIndex = Math.max(0, WARHEADS.indexOf(WarheadRegistry.defaultId()));
    }

    private static String prev(EditBox box, String fallback) {
        return box != null ? box.getValue() : fallback;
    }

    private static ResourceLocation stageAt(List<ResourceLocation> stages, int index) {
        return stages.isEmpty() ? null : stages.get(Math.floorMod(index, stages.size()));
    }

    private static String stageLabel(List<ResourceLocation> stages, int index) {
        ResourceLocation stage = stageAt(stages, index);
        return stage == null ? "" : stage.getPath();
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private void seed() {
        if (this.seeded) {
            return;
        }
        this.seeded = true;
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        if (this.minecraft.level.getBlockEntity(menu.pos()) instanceof MissileDispenserBlockEntity be
                && be.getConfig() != null) {
            LaunchConfig c = be.getConfig();
            this.seed = c;
            this.modelIndex = Math.max(0, MODELS.indexOf(c.modelId));
            this.warheadIndex = Math.max(0, WARHEADS.indexOf(c.warheadId));
            this.cruiseIndex = c.highAltitude ? 1 : 0;
            this.ascentStageIndex = Math.max(0, ASCENT_STAGES.indexOf(c.ascentStageId));
            this.cruiseStageIndex = Math.max(0, CRUISE_STAGES.indexOf(c.cruiseStageId));
            this.attackStageIndex = Math.max(0, ATTACK_STAGES.indexOf(c.attackStageId));
            this.startInCruise = c.startInCruise;
            this.startArmed = c.startArmed;
        }
    }

    @Override
    protected void init() {
        super.init();
        seed();
        this.editBoxes.clear();
        this.editHints.clear();

        int x = leftPos + PAD;
        int y = topPos + START_Y;

        modelButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            modelIndex = (modelIndex + 1) % MODELS.size();
            refreshButtonLabels();
        }).bounds(x, y, W_FULL, BTN_H).build());
        y += BTN_H + ROW_GAP;

        warheadButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            warheadIndex = (warheadIndex + 1) % WARHEADS.size();
            refreshButtonLabels();
        }).bounds(x, y, W_FULL, BTN_H).build());
        y += BTN_H + ROW_GAP;

        cruiseButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            cruiseIndex = (cruiseIndex + 1) % CRUISE_LABELS.length;
            refreshButtonLabels();
        }).bounds(x, y, W_FULL, BTN_H).build());
        y += BTN_H + ROW_GAP;

        // Flight-stage selectors (ascent / cruise / attack). Each cycles the stages registered for its phase,
        // so the missile's flight can be reconfigured at will (e.g. cruise -> loiter to make a drone).
        ascentStageButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            ascentStageIndex = (ascentStageIndex + 1) % Math.max(1, ASCENT_STAGES.size());
            refreshButtonLabels();
        }).bounds(x, y, THIRD, BTN_H).build());
        cruiseStageButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            cruiseStageIndex = (cruiseStageIndex + 1) % Math.max(1, CRUISE_STAGES.size());
            refreshButtonLabels();
        }).bounds(x + THIRD + GAP, y, THIRD, BTN_H).build());
        attackStageButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            attackStageIndex = (attackStageIndex + 1) % Math.max(1, ATTACK_STAGES.size());
            refreshButtonLabels();
        }).bounds(x + 2 * (THIRD + GAP), y, THIRD, BTN_H).build());
        y += BTN_H + ROW_GAP;

        // Target row (three boxes). Default to a stored config, else the player's rounded position.
        BlockPos self = menu.pos();
        String defX = prev(targetX, Integer.toString(self.getX()));
        String defY = prev(targetY, Integer.toString(self.getY()));
        String defZ = prev(targetZ, Integer.toString(self.getZ()));
        if (targetX == null && seed != null) {
            defX = num(seed.targetX);
            defY = num(seed.targetY);
            defZ = num(seed.targetZ);
        } else if (targetX == null && this.minecraft != null && this.minecraft.player != null) {
            defX = Long.toString(Math.round(this.minecraft.player.getX()));
            defY = Long.toString(Math.round(this.minecraft.player.getY()));
            defZ = Long.toString(Math.round(this.minecraft.player.getZ()));
        }
        y += LABEL_H;
        targetX = makeBox(x, y, THIRD, defX, "Target X");
        targetY = makeBox(x + THIRD + GAP, y, THIRD, defY, "Y");
        targetZ = makeBox(x + 2 * (THIRD + GAP), y, THIRD, defZ, "Z");
        y += BOX_H + ROW_GAP;

        y += LABEL_H;
        offsetBox = makeBox(x, y, HALF, prev(offsetBox, seed != null ? num(seed.explosionOffset) : "0"), "Airburst offset");
        altitudeBox = makeBox(x + HALF + GAP, y, HALF, prev(altitudeBox, seed != null ? num(seed.altitudeParam) : "24"), "Altitude / Clearance");
        y += BOX_H + ROW_GAP;

        y += LABEL_H;
        fragmentBox = makeBox(x, y, HALF, prev(fragmentBox, seed != null ? Integer.toString(seed.fragmentCount) : "24"), "Fragment count");
        speedBox = makeBox(x + HALF + GAP, y, HALF, prev(speedBox, seed != null ? num(seed.cruiseSpeed) : "1.0"), "Cruise speed");
        y += BOX_H + ROW_GAP;

        y += LABEL_H;
        turnRateBox = makeBox(x, y, HALF, prev(turnRateBox, seed != null ? num(seed.turnRate) : "0"), "Turn rate (0=auto)");
        healthBox = makeBox(x + HALF + GAP, y, HALF, prev(healthBox, seed != null ? num(seed.health) : Float.toString(MissileEntity.DEFAULT_HEALTH)), "Health");
        y += BOX_H + ROW_GAP;

        // Toggle row.
        inCruiseButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            startInCruise = !startInCruise;
            refreshButtonLabels();
        }).bounds(x, y, HALF, BTN_H).build());
        armedButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            startArmed = !startArmed;
            refreshButtonLabels();
        }).bounds(x + HALF + GAP, y, HALF, BTN_H).build());
        y += BTN_H + ROW_GAP;

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(x, y, HALF, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Launch"), b -> spawn())
                .bounds(x + HALF + GAP, y, HALF, BTN_H).build());

        refreshButtonLabels();
    }

    private EditBox makeBox(int x, int y, int width, String value, String hint) {
        EditBox box = new EditBox(this.font, x, y, width, BOX_H, Component.literal(hint));
        box.setMaxLength(24);
        box.setValue(value);
        addRenderableWidget(box);
        editBoxes.add(box);
        editHints.add(hint);
        return box;
    }

    private void refreshButtonLabels() {
        modelButton.setMessage(Component.literal("Model: " + MODELS.get(modelIndex).getPath()));
        warheadButton.setMessage(Component.literal("Warhead: " + WARHEADS.get(warheadIndex).getPath()));
        cruiseButton.setMessage(Component.literal("Cruise: " + CRUISE_LABELS[cruiseIndex]));
        ascentStageButton.setMessage(Component.literal("A:" + stageLabel(ASCENT_STAGES, ascentStageIndex)));
        cruiseStageButton.setMessage(Component.literal("C:" + stageLabel(CRUISE_STAGES, cruiseStageIndex)));
        attackStageButton.setMessage(Component.literal("T:" + stageLabel(ATTACK_STAGES, attackStageIndex)));
        inCruiseButton.setMessage(Component.literal("Launch: " + (startInCruise ? "Cruise" : "Ascend")));
        armedButton.setMessage(Component.literal("Pre-armed: " + (startArmed ? "Yes" : "No")));
    }

    private LaunchConfig buildConfig() {
        LaunchConfig c = new LaunchConfig();
        c.modelId = MODELS.get(modelIndex);
        c.warheadId = WARHEADS.get(warheadIndex);
        c.highAltitude = cruiseIndex == 1;
        c.targetX = parseDouble(targetX.getValue(), menu.pos().getX());
        c.targetY = parseDouble(targetY.getValue(), menu.pos().getY());
        c.targetZ = parseDouble(targetZ.getValue(), menu.pos().getZ());
        c.explosionOffset = (float) parseDouble(offsetBox.getValue(), 0.0);
        c.altitudeParam = parseDouble(altitudeBox.getValue(), 24.0);
        c.fragmentCount = (int) Math.round(parseDouble(fragmentBox.getValue(), 24.0));
        c.cruiseSpeed = parseDouble(speedBox.getValue(), 1.0);
        c.turnRate = parseDouble(turnRateBox.getValue(), 0.0);
        c.health = (float) parseDouble(healthBox.getValue(), MissileEntity.DEFAULT_HEALTH);
        c.startInCruise = startInCruise;
        c.startArmed = startArmed;
        c.ascentStageId = stageAt(ASCENT_STAGES, ascentStageIndex);
        c.cruiseStageId = stageAt(CRUISE_STAGES, cruiseStageIndex);
        c.attackStageId = stageAt(ATTACK_STAGES, attackStageIndex);
        return c;
    }

    private void spawn() {
        WFNetwork.sendToServer(new SpawnMissilePacket(menu.pos(), buildConfig(), true));
    }

    private void save() {
        WFNetwork.sendToServer(new SpawnMissilePacket(menu.pos(), buildConfig(), false));
    }

    /**
     * Estimated flight time from this dispenser to the entered target with the current speed/altitude/stages.
     */
    private String etaText() {
        if (targetX == null) {
            return "";
        }
        double tx = parseDouble(targetX.getValue(), menu.pos().getX());
        double ty = parseDouble(targetY.getValue(), menu.pos().getY());
        double tz = parseDouble(targetZ.getValue(), menu.pos().getZ());
        double speed = parseDouble(speedBox.getValue(), 1.0);
        double altitude = parseDouble(altitudeBox.getValue(), 24.0);
        boolean highAltitude = cruiseIndex == 1;
        double cruiseAltitudeY = highAltitude ? altitude : menu.pos().getY() + altitude;
        boolean loiter = FlightStageRegistry.keyOf(LoiterStage.INSTANCE).equals(stageAt(CRUISE_STAGES, cruiseStageIndex));
        int loiterTicks = loiter ? LoiterStage.LOITER_TICKS : 0;
        int ticks = ArrivalEstimator.estimateTicks(Vec3.atCenterOf(menu.pos()),
                new Vec3(tx, ty, tz), speed, MissileEntity.ascentSpeedFor(speed), cruiseAltitudeY, loiterTicks);
        return String.format("ETA ~%.1fs", ticks / 20.0);
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        gg.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0101018);
        gg.fill(leftPos, topPos, leftPos + imageWidth, topPos + 14, 0xFF2B2B44);
        gg.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFF404060);
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // Suppressed default (no "Inventory" label); title + hints are drawn in render() at absolute coords.
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg); // dim the world behind the panel
        super.render(gg, mouseX, mouseY, partialTick);

        gg.drawString(this.font, this.title, leftPos + PAD, topPos + 4, 0xE0E0F0, false);
        String eta = etaText();
        gg.drawString(this.font, eta, leftPos + imageWidth - PAD - this.font.width(eta), topPos + 4, 0x90E090, false);
        for (int i = 0; i < editBoxes.size(); i++) {
            EditBox box = editBoxes.get(i);
            gg.drawString(this.font, editHints.get(i), box.getX(), box.getY() - LABEL_H + 1, 0x9090A8, false);
        }

        this.renderTooltip(gg, mouseX, mouseY);
    }

    // --- keep typing inside edit boxes from triggering the inventory-close key -------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC always closes
            this.onClose();
            return true;
        }
        for (EditBox box : editBoxes) {
            if (box.isFocused()) {
                box.keyPressed(keyCode, scanCode, modifiers);
                return true; // swallow so 'e' etc. don't close the screen while typing
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        for (EditBox box : editBoxes) {
            if (box.isFocused()) {
                return box.charTyped(c, modifiers);
            }
        }
        return super.charTyped(c, modifiers);
    }
}
