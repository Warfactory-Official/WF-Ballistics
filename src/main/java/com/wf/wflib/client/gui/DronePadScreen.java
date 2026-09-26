package com.wf.wflib.client.gui;

import com.wf.wflib.block.entity.DronePadBlockEntity;
import com.wf.wflib.drone.DroneBattery;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneFlight;
import com.wf.wflib.drone.DroneMission;
import com.wf.wflib.drone.MineLoad;
import com.wf.wflib.item.MinePresetRegistry;
import com.wf.wflib.drone.DroneProgram;
import com.wf.wflib.drone.DroneModels;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.exchange.ExchangeMode;
import com.wf.wflib.exchange.StationCode;
import com.wf.wflib.drone.PowerProfile;
import com.wf.wflib.drone.ai.PowerPolicy;
import com.wf.wflib.drone.ai.Steering;
import com.wf.wflib.drone.ai.state.Tuning;
import com.wf.wflib.drone.ai.coord.CoordinationModels;
import com.wf.wflib.drone.squad.Formation;
import com.wf.wflib.drone.squad.Formations;
import com.wf.wflib.menu.DronePadMenu;
import com.wf.wflib.network.DronePadConfigPacket;
import com.wf.wflib.network.WFNetwork;
import com.wf.wflib.warhead.WarheadRegistry;
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

/** Configure, launch and debug a drone flight from the pad. */
public class DronePadScreen extends AbstractContainerScreen<DronePadMenu> {

    private static final List<ResourceLocation> FORMATIONS = new ArrayList<>(Formations.ids());
    private static final List<ResourceLocation> COORDINATION = new ArrayList<>(CoordinationModels.ids());
    private static final List<ResourceLocation> WARHEADS = new ArrayList<>(WarheadRegistry.ids());
    /** The mines a rack can be loaded with, air-delivered first. */
    private static final List<ResourceLocation> MINES = mineChoices();
    private static final String[] KIND_LABELS = {"Delivery (crate)", "Strike (warhead)", "Minelay (rack)"};

    private static List<ResourceLocation> mineChoices() {
        List<ResourceLocation> out = new ArrayList<>();
        MinePresetRegistry.bootstrap();
        MinePresetRegistry.sown().forEach(preset -> out.add(preset.id()));
        MinePresetRegistry.all().stream()
                .filter(preset -> !preset.sownOnly())
                .forEach(preset -> out.add(preset.id()));
        return out;
    }

    private static final int PAD = 8;
    private static final int W_FULL = 204;
    private static final int GAP = 6;
    private static final int ROW_GAP = 4;
    private static final int BTN_H = 18;
    private static final int BOX_H = 16;
    private static final int LABEL_H = 10;
    private static final int START_Y = 16;
    private static final int THIRD = (W_FULL - 2 * GAP) / 3;
    private static final int HALF = (W_FULL - GAP) / 2;

    private final List<EditBox> editBoxes = new ArrayList<>();
    private final List<String> editHints = new ArrayList<>();

    private int kindIndex;
    private int formationIndex;
    private int coordinationIndex;
    private int warheadIndex;
    private int mineIndex;
    private boolean seeded;

    private Button kindButton;
    private Button modeButton;
    private EditBox recipientBox;
    private ExchangeMode mode = ExchangeMode.DIRECT;
    /** The queued steps, edited on {@link DroneProgramScreen} and carried out with the mission. */
    private DroneProgram program = DroneProgram.EMPTY;
    private Button programButton;
    private Button formationButton;
    private Button coordinationButton;
    private Button warheadButton;
    private EditBox destX;
    private EditBox destY;
    private EditBox destZ;
    private EditBox countBox;
    private EditBox speedBox;
    private EditBox altitudeBox;
    private EditBox batteryBox;
    private EditBox releaseBox;
    private EditBox spacingBox;
    private EditBox intervalBox;
    private EditBox minesBox;
    private EditBox layGapBox;

    public DronePadScreen(DronePadMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 220;
        this.imageHeight = 280 + (BTN_H + ROW_GAP) + (LABEL_H + BOX_H + ROW_GAP);
        this.warheadIndex = Math.max(0, WARHEADS.indexOf(WarheadRegistry.defaultId()));
        this.formationIndex = Math.max(0, FORMATIONS.indexOf(Formations.DEFAULT));
        this.coordinationIndex = Math.max(0, COORDINATION.indexOf(CoordinationModels.DEFAULT));
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String prev(EditBox box, String fallback) {
        return box != null ? box.getValue() : fallback;
    }

    /**
     * Load whatever the pad already has stored, so reopening the screen shows the real mission rather than
     * defaults.
     */
    private void seed() {
        if (this.seeded || this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        this.seeded = true;
        if (this.minecraft.level.getBlockEntity(menu.pos()) instanceof DronePadBlockEntity pad) {
            DroneMission m = pad.mission();
            this.kindIndex = m.isStrike() ? 1 : m.isMinelay() ? 2 : 0;
            this.mode = m.mode;
            this.formationIndex = Math.max(0, FORMATIONS.indexOf(m.formationId));
        this.coordinationIndex = Math.max(0, COORDINATION.indexOf(m.coordinationId));
            if (m.payloadId != null) {
                this.warheadIndex = Math.max(0, WARHEADS.indexOf(m.payloadId));
            }
            if (m.mines != null) {
                this.mineIndex = Math.max(0, MINES.indexOf(m.mines.preset()));
            }
            this.program = m.program;
        }
    }

    /**
     * @return where a freshly added program step should default to: whatever destination is currently typed
     *      in, falling back to the pad itself. Saves retyping a coordinate that is already on screen.
     */
    private Vec3 destinationOrPad() {
        if (destX != null) {
            return new Vec3(parseDouble(destX.getValue(), menu.pos().getX()),
                    parseDouble(destY.getValue(), menu.pos().getY()),
                    parseDouble(destZ.getValue(), menu.pos().getZ()));
        }
        return Vec3.atCenterOf(menu.pos());
    }

    private DroneMission stored() {
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(menu.pos()) instanceof DronePadBlockEntity pad) {
            return pad.mission();
        }
        return null;
    }

    @Override
    protected void init() {
        super.init();
        seed();
        this.editBoxes.clear();
        this.editHints.clear();

        int x = leftPos + PAD;
        int y = topPos + START_Y;
        DroneMission seedMission = stored();

        kindButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            kindIndex = (kindIndex + 1) % KIND_LABELS.length;
            refreshButtonLabels();
        }).bounds(x, y, W_FULL, BTN_H).build());
        y += BTN_H + ROW_GAP;

        formationButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            formationIndex = (formationIndex + 1) % Math.max(1, FORMATIONS.size());
            refreshButtonLabels();
        }).bounds(x, y, HALF, BTN_H).build());
        warheadButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (isMinelay()) {
                mineIndex = (mineIndex + 1) % Math.max(1, MINES.size());
            } else {
                warheadIndex = (warheadIndex + 1) % Math.max(1, WARHEADS.size());
            }
            refreshButtonLabels();
        }).bounds(x + HALF + GAP, y, HALF, BTN_H).build());
        y += BTN_H + ROW_GAP;

        coordinationButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            coordinationIndex = (coordinationIndex + 1) % Math.max(1, COORDINATION.size());
            refreshButtonLabels();
        }).bounds(x, y, W_FULL, BTN_H).build());
        y += BTN_H + ROW_GAP;

        modeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            mode = mode.next();
            // The layout changes with the mode, so rebuild rather than trying to toggle widgets in place.
            this.rebuildWidgets();
        }).bounds(x, y, W_FULL, BTN_H).build());
        y += BTN_H + ROW_GAP;

        // Destination: defaults to the pad's own position so a fresh pad is obviously unconfigured.
        BlockPos self = menu.pos();
        String defX = prev(destX, Integer.toString(self.getX()));
        String defY = prev(destY, Integer.toString(self.getY()));
        String defZ = prev(destZ, Integer.toString(self.getZ()));
        if (destX == null && seedMission != null && !seedMission.destination.equals(Vec3.ZERO)) {
            defX = num(seedMission.destination.x);
            defY = num(seedMission.destination.y);
            defZ = num(seedMission.destination.z);
        }
        y += LABEL_H;
        if (mode.resolvesDestination()) {
            destX = null;
            destY = null;
            destZ = null;
            recipientBox = makeBox(x, y, W_FULL, prev(recipientBox,
                    seedMission != null && seedMission.recipientCode != null
                            ? seedMission.recipientCode : ""), "Recipient station code");
            recipientBox.setMaxLength(StationCode.LENGTH + 4);
        } else {
            recipientBox = null;
            destX = makeBox(x, y, THIRD, defX, "Destination X");
            destY = makeBox(x + THIRD + GAP, y, THIRD, defY, "Y");
            destZ = makeBox(x + 2 * (THIRD + GAP), y, THIRD, defZ, "Z");
        }
        y += BOX_H + ROW_GAP;

        y += LABEL_H;
        countBox = makeBox(x, y, THIRD,
                prev(countBox, seedMission != null ? Integer.toString(seedMission.count) : "1"), "Drones");
        speedBox = makeBox(x + THIRD + GAP, y, THIRD,
                prev(speedBox, num(seedMission != null ? seedMission.cruiseSpeed : DroneFlight.DEFAULT_CRUISE_SPEED)),
                "Speed");
        altitudeBox = makeBox(x + 2 * (THIRD + GAP), y, THIRD,
                prev(altitudeBox, num(seedMission != null
                        ? seedMission.cruiseAltitude : DroneFlight.DEFAULT_CRUISE_ALTITUDE)), "Altitude");
        y += BOX_H + ROW_GAP;

        y += LABEL_H;
        batteryBox = makeBox(x, y, THIRD,
                prev(batteryBox, num(seedMission != null ? seedMission.batteryCapacity : DroneBattery.DEFAULT_CAPACITY)),
                "Battery");
        releaseBox = makeBox(x + THIRD + GAP, y, THIRD,
                prev(releaseBox, num(seedMission != null ? seedMission.releaseSpeed : DroneFlight.DEFAULT_RELEASE_SPEED)),
                "Release");
        spacingBox = makeBox(x + 2 * (THIRD + GAP), y, THIRD,
                prev(spacingBox, num(seedMission != null ? seedMission.formationSpacing : Formation.DEFAULT_SPACING)),
                "Spacing");
        y += BOX_H + ROW_GAP;

        y += LABEL_H;
        intervalBox = makeBox(x, y, THIRD,
                prev(intervalBox, Integer.toString(seedMission != null
                        ? seedMission.launchInterval : DroneMission.DEFAULT_LAUNCH_INTERVAL)),
                "Launch gap");
        minesBox = makeBox(x + THIRD + GAP, y, THIRD,
                prev(minesBox, Integer.toString(seedMission != null && seedMission.mines != null
                        ? seedMission.mines.capacity() : MineLoad.DEFAULT_MINES)), "Mines");
        layGapBox = makeBox(x + 2 * (THIRD + GAP), y, THIRD,
                prev(layGapBox, num(seedMission != null && seedMission.mines != null
                        ? seedMission.mines.spacing() : MineLoad.DEFAULT_SPACING)), "Lay gap");
        y += BOX_H + ROW_GAP + 2;

        programButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new DroneProgramScreen(this, program,
                        destinationOrPad(), edited -> {
                    this.program = edited;
                    refreshButtonLabels();
                }));
            }
        }).bounds(x, y, W_FULL, BTN_H).build());
        y += BTN_H + ROW_GAP;

        addRenderableWidget(Button.builder(Component.literal("Dispatch"),
                b -> send(DronePadConfigPacket.Action.DISPATCH)).bounds(x, y, THIRD, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Save"),
                b -> send(DronePadConfigPacket.Action.SAVE)).bounds(x + THIRD + GAP, y, THIRD, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Recall"),
                b -> send(DronePadConfigPacket.Action.RECALL)).bounds(x + 2 * (THIRD + GAP), y, THIRD, BTN_H).build());
        y += BTN_H + ROW_GAP;

        addRenderableWidget(Button.builder(Component.literal("Clear all drones"),
                b -> send(DronePadConfigPacket.Action.CLEAR)).bounds(x, y, W_FULL, BTN_H).build());

        refreshButtonLabels();
    }

    private EditBox makeBox(int x, int y, int width, String value, String hint) {
        EditBox box = new EditBox(this.font, x, y, width, BOX_H, Component.literal(hint));
        box.setMaxLength(12);
        box.setValue(value);
        addRenderableWidget(box);
        editBoxes.add(box);
        editHints.add(hint);
        return box;
    }

    private void refreshButtonLabels() {
        kindButton.setMessage(Component.literal("Mission: " + KIND_LABELS[kindIndex]));
        modeButton.setMessage(Component.literal("Exchange: " + mode.label()
                + (mode.classified() ? " (no telemetry)" : "")));
        formationButton.setMessage(Component.literal("Form: "
                + FORMATIONS.get(Math.floorMod(formationIndex, FORMATIONS.size())).getPath()));
        coordinationButton.setMessage(Component.literal("Hold: "
                + COORDINATION.get(Math.floorMod(coordinationIndex, COORDINATION.size())).getPath()
                        .replace('_', ' ')));
        if (isMinelay()) {
            warheadButton.setMessage(Component.literal("Mine: "
                    + MINES.get(Math.floorMod(mineIndex, MINES.size())).getPath()));
        } else {
            // Greyed rather than hidden on a delivery, so the layout doesn't jump when the kind is toggled.
            warheadButton.setMessage(Component.literal((isStrike() ? "Warhead: " : "(warhead) ")
                    + WARHEADS.get(Math.floorMod(warheadIndex, WARHEADS.size())).getPath()));
        }
        warheadButton.active = isStrike() || isMinelay();
        if (minesBox != null) {
            minesBox.setEditable(isMinelay());
            layGapBox.setEditable(isMinelay());
        }
        if (programButton != null) {
            programButton.setMessage(Component.literal(program.isEmpty()
                    ? "Program: none (single destination)"
                    : "Program: " + program.tasks().size() + " step(s)"));
        }
    }

    private boolean isStrike() {
        return kindIndex == 1;
    }

    private boolean isMinelay() {
        return kindIndex == 2;
    }

    private DroneMission buildMission() {
        DroneMission m = new DroneMission();
        m.mode = mode;
        m.recipientCode = recipientBox == null ? null : StationCode.normalise(recipientBox.getValue());
        m.destination = mode.resolvesDestination() ? Vec3.ZERO
                : new Vec3(parseDouble(destX.getValue(), menu.pos().getX()),
                parseDouble(destY.getValue(), menu.pos().getY()),
                parseDouble(destZ.getValue(), menu.pos().getZ()));
        m.exfil = Vec3.atCenterOf(menu.pos().above());
        m.count = Math.max(1, (int) Math.round(parseDouble(countBox.getValue(), 1.0)));
        m.formationId = FORMATIONS.get(Math.floorMod(formationIndex, FORMATIONS.size()));
        m.coordinationId = COORDINATION.get(Math.floorMod(coordinationIndex, COORDINATION.size()));
        m.cruiseSpeed = parseDouble(speedBox.getValue(), DroneFlight.DEFAULT_CRUISE_SPEED);
        m.cruiseAltitude = parseDouble(altitudeBox.getValue(), DroneFlight.DEFAULT_CRUISE_ALTITUDE);
        m.batteryCapacity = parseDouble(batteryBox.getValue(), DroneBattery.DEFAULT_CAPACITY);
        m.releaseSpeed = parseDouble(releaseBox.getValue(), DroneFlight.DEFAULT_RELEASE_SPEED);
        m.formationSpacing = Formation.clampSpacing(
                parseDouble(spacingBox.getValue(), Formation.DEFAULT_SPACING));
        m.launchInterval = DroneMission.clampInterval((int) Math.round(
                parseDouble(intervalBox.getValue(), DroneMission.DEFAULT_LAUNCH_INTERVAL)));
        m.payloadId = isStrike() ? WARHEADS.get(Math.floorMod(warheadIndex, WARHEADS.size())) : null;
        m.mines = isMinelay()
                ? MineLoad.of(MINES.get(Math.floorMod(mineIndex, MINES.size())),
                        (int) Math.round(parseDouble(minesBox.getValue(), MineLoad.DEFAULT_MINES)),
                        parseDouble(layGapBox.getValue(), MineLoad.DEFAULT_SPACING))
                : null;
        m.program = program;
        return m;
    }

    private void send(DronePadConfigPacket.Action action) {
        WFNetwork.sendToServer(new DronePadConfigPacket(menu.pos(), buildMission(), action));
    }

    /**
     * The debug readout: what this mission will actually do, computed with the flight's own maths.
     */
    private List<Component> readout() {
        List<Component> lines = new ArrayList<>(4);
        DroneMission m = buildMission();
        Vec3 origin = Vec3.atCenterOf(menu.pos().above());
        if (m.mode.resolvesDestination()) {
            return handshakeReadout(m);
        }
        boolean programmed = !m.program.isEmpty();
        double range = programmed ? m.outboundDistance(origin) : origin.distanceTo(m.destination);
        boolean cargo = !isStrike() && !isMinelay();

        Airframe frame = DroneModels.airframe(m.modelId);
        double outbound = PowerProfile.DEFAULT.costToTravel(frame, range, m.cruiseSpeed, cargo);
        int outPercent = (int) Math.round(100.0 * outbound / Math.max(1.0, m.batteryCapacity));
        double shortfall = m.shortfall(origin, cargo);
        boolean roundTrip = m.canRoundTrip(origin, cargo);

        int ticks = (int) Math.round(range / Math.max(1.0E-3, m.cruiseSpeed)
                + m.cruiseAltitude / DroneFlight.DEFAULT_CLIMB_RATE);
        lines.add(Component.literal(programmed
                ? String.format("%d step(s), %dm of route   ETA ~%.0fs   %d%% battery out",
                        m.program.tasks().size(), (int) range, ticks / 20.0, outPercent)
                : String.format("Range %dm   ETA ~%.0fs   %d%% battery out",
                        (int) range, ticks / 20.0, outPercent)).withStyle(s -> s.withColor(0xA0A0C0)));

        if (shortfall > 0.0) {
            lines.add(Component.literal(String.format("WILL BE REFUSED: %.0f charge short", shortfall))
                    .withStyle(s -> s.withColor(0xFF6060)));
        } else if (!roundTrip) {
            lines.add(Component.literal("ONE-WAY: delivers, then lands and parks there")
                    .withStyle(s -> s.withColor(0xFFC060)));
        } else {
            lines.add(Component.literal("Round trip OK").withStyle(s -> s.withColor(0x90E090)));
        }

        double top = frame.topSpeed(PowerProfile.DEFAULT.massFactor(cargo));
        double commanded = isStrike() || isMinelay()
                ? Math.max(m.cruiseSpeed, m.releaseSpeed) : m.cruiseSpeed;
        if (commanded > top) {
            lines.add(Component.literal(String.format("%.2f b/t is past this airframe's %.2f b/t limit",
                    commanded, top)).withStyle(s -> s.withColor(0xFFC060)));
        }

        if (isMinelay()) {
            double lead = Steering.ballisticLead(m.cruiseAltitude, 0.0, m.releaseSpeed, Tuning.PAYLOAD_GRAVITY);
            lines.add(Component.literal(String.format("%d x %s each, %dm strip, first release %dm short",
                            m.mines.capacity(), m.mines.preset().getPath(), (int) m.mines.laneLength(),
                            (int) (lead + m.mines.laneLength() * 0.5)))
                    .withStyle(s -> s.withColor(0xC0A0E0)));
        } else if (isStrike()) {
            // Show the actual release solution the attack run will use, from cruise altitude at release speed.
            double lead = Steering.ballisticLead(m.cruiseAltitude, 0.0, m.releaseSpeed, Tuning.PAYLOAD_GRAVITY);
            lines.add(Component.literal(String.format("Release %dm short of target, %d bomb(s) of %s",
                            (int) lead, m.count, m.payloadId.getPath()))
                    .withStyle(s -> s.withColor(0xC0A0E0)));
        } else {
            lines.add(Component.literal("Sneak-click the pad to load its cargo crate")
                    .withStyle(s -> s.withColor(0x8080A0)));
        }
        if (programmed) {
            lines.add(Component.literal("Destination above is ignored; step 1 of the program leads")
                    .withStyle(s -> s.withColor(0x8080A0)));
        }
        return lines;
    }

    /** What a handshake can honestly say. */
    private List<Component> handshakeReadout(DroneMission m) {
        List<Component> lines = new ArrayList<>(3);
        String own = ownStationCode();
        lines.add(Component.literal("This station: " + (own == null ? "unregistered" : StationCode.pretty(own)))
                .withStyle(s -> s.withColor(0x80C0FF)));
        boolean ready = StationCode.valid(m.recipientCode);
        lines.add(Component.literal(ready
                        ? "Meeting point chosen by the server. You will not be told where it is."
                        : "Enter the recipient's 12-character station code.")
                .withStyle(s -> s.withColor(ready ? 0x90E090 : 0xFFC060)));
        lines.add(Component.literal("They must allow your code first. Any player near the drop cancels it.")
                .withStyle(s -> s.withColor(0x8080A0)));
        return lines;
    }

    private String ownStationCode() {
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(menu.pos()) instanceof DronePadBlockEntity pad) {
            return pad.knownStationCode();
        }
        return null;
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        gg.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0101018);
        gg.fill(leftPos, topPos, leftPos + imageWidth, topPos + 14, 0xFF2B2B44);
        gg.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFF404060);
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // Suppressed default; the title and hints are drawn in render() at absolute coordinates.
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);

        gg.drawString(this.font, this.title, leftPos + PAD, topPos + 4, 0xE0E0F0, false);
        for (int i = 0; i < editBoxes.size(); i++) {
            EditBox box = editBoxes.get(i);
            gg.drawString(this.font, editHints.get(i), box.getX(), box.getY() - LABEL_H + 1, 0x9090A8, false);
        }

        List<Component> lines = readout();
        int y = topPos + imageHeight - 6 - lines.size() * 10;
        for (Component line : lines) {
            gg.drawString(this.font, line, leftPos + PAD, y, 0xFFFFFF, false);
            y += 10;
        }

        this.renderTooltip(gg, mouseX, mouseY);
    }

    // --- keep typing inside edit boxes from triggering the inventory-close key ---

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        for (EditBox box : editBoxes) {
            if (box.isFocused()) {
                box.keyPressed(keyCode, scanCode, modifiers);
                return true;
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
