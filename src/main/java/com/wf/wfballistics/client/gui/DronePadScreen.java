package com.wf.wfballistics.client.gui;

import com.wf.wfballistics.block.entity.DronePadBlockEntity;
import com.wf.wfballistics.drone.DroneBattery;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.drone.DroneMission;
import com.wf.wfballistics.drone.DroneProgram;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.exchange.ExchangeMode;
import com.wf.wfballistics.exchange.StationCode;
import com.wf.wfballistics.drone.PowerProfile;
import com.wf.wfballistics.drone.ai.PowerPolicy;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.ai.coord.CoordinationModels;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import com.wf.wfballistics.menu.DronePadMenu;
import com.wf.wfballistics.network.DronePadConfigPacket;
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

/**
 * Configure, launch and debug a drone flight from the pad. The counterpart of
 * {@link MissileDispenserScreen}, with the same shape: cycle buttons, typed numbers, and a live readout that
 * tells you whether what you just typed will actually work.
 *
 * <p>The readout is honest because it runs the real code: {@link PowerPolicy} and {@link Steering} are pure
 * functions of a mission, with no world access, so the screen calls exactly what the drone's brain will call
 * a moment later. Range, battery cost, one-way vs round trip and the ballistic release lead are all the
 * flight's own arithmetic, not a GUI-side approximation of it.
 */
public class DronePadScreen extends AbstractContainerScreen<DronePadMenu> {

    private static final List<ResourceLocation> FORMATIONS = new ArrayList<>(Formations.ids());
    private static final List<ResourceLocation> COORDINATION = new ArrayList<>(CoordinationModels.ids());
    private static final List<ResourceLocation> WARHEADS = new ArrayList<>(WarheadRegistry.ids());
    private static final String[] KIND_LABELS = {"Delivery (crate)", "Strike (warhead)"};

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
    private boolean seeded;

    private Button kindButton;
    private Button modeButton;
    private EditBox recipientBox;
    private ExchangeMode mode = ExchangeMode.DIRECT;
    /**
     * The queued steps, edited on {@link DroneProgramScreen} and carried out with the mission. Empty is the
     * one-stop mission this screen has always built, and it stays the default.
     */
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

    public DronePadScreen(DronePadMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 220;
        // Two rows taller than it was: the coordination picker (a button row) and the launch gap (a labelled
        // box row). The panel is drawn to this height rather than measured from the widgets, so it has to be
        // grown by hand whenever a row is added or the bottom ones fall outside it.
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
            this.kindIndex = m.isStrike() ? 1 : 0;
            this.mode = m.mode;
            this.formationIndex = Math.max(0, FORMATIONS.indexOf(m.formationId));
        this.coordinationIndex = Math.max(0, COORDINATION.indexOf(m.coordinationId));
            if (m.payloadId != null) {
                this.warheadIndex = Math.max(0, WARHEADS.indexOf(m.payloadId));
            }
            this.program = m.program;
        }
    }

    /**
     * @return where a freshly added program step should default to: whatever destination is currently typed
     * in, falling back to the pad itself. Saves retyping a coordinate that is already on screen.
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
            warheadIndex = (warheadIndex + 1) % Math.max(1, WARHEADS.size());
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
            // A handshake has no destination to type. The recipient's station code goes in instead, and
            // where it resolves to is decided on the server and never sent back here.
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
                prev(speedBox, num(seedMission != null ? seedMission.cruiseSpeed : DroneEntity.DEFAULT_CRUISE_SPEED)),
                "Speed");
        altitudeBox = makeBox(x + 2 * (THIRD + GAP), y, THIRD,
                prev(altitudeBox, num(seedMission != null
                        ? seedMission.cruiseAltitude : DroneEntity.DEFAULT_CRUISE_ALTITUDE)), "Altitude");
        y += BOX_H + ROW_GAP;

        y += LABEL_H;
        batteryBox = makeBox(x, y, THIRD,
                prev(batteryBox, num(seedMission != null ? seedMission.batteryCapacity : DroneBattery.DEFAULT_CAPACITY)),
                "Battery");
        releaseBox = makeBox(x + THIRD + GAP, y, THIRD,
                prev(releaseBox, num(seedMission != null ? seedMission.releaseSpeed : DroneEntity.DEFAULT_RELEASE_SPEED)),
                "Release");
        // How loosely the flight holds its shape. Next to the formation picker in meaning if not in pixels:
        // that one chooses the shape, this one chooses how big it is.
        spacingBox = makeBox(x + 2 * (THIRD + GAP), y, THIRD,
                prev(spacingBox, num(seedMission != null ? seedMission.formationSpacing : Formation.DEFAULT_SPACING)),
                "Spacing");
        y += BOX_H + ROW_GAP;

        // Ticks between one drone leaving the pad and the next. 0 puts the whole flight up at once, which is
        // what this did before there was a choice; anything else launches them in series and holds the flight
        // over the pad until everyone is up.
        y += LABEL_H;
        intervalBox = makeBox(x, y, THIRD,
                prev(intervalBox, Integer.toString(seedMission != null
                        ? seedMission.launchInterval : DroneMission.DEFAULT_LAUNCH_INTERVAL)),
                "Launch gap");
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
        // The shape says where the slots are; this says what they are measured against, which is what
        // decides how well they are actually held. Only meaningful for a flight of more than one.
        coordinationButton.setMessage(Component.literal("Hold: "
                + COORDINATION.get(Math.floorMod(coordinationIndex, COORDINATION.size())).getPath()
                        .replace('_', ' ')));
        // The warhead only matters on a strike; grey the wording out on a delivery rather than hiding it, so
        // the layout doesn't jump when the mission kind is toggled.
        warheadButton.setMessage(Component.literal((isStrike() ? "Warhead: " : "(warhead) ")
                + WARHEADS.get(Math.floorMod(warheadIndex, WARHEADS.size())).getPath()));
        warheadButton.active = isStrike();
        if (programButton != null) {
            programButton.setMessage(Component.literal(program.isEmpty()
                    ? "Program: none (single destination)"
                    : "Program: " + program.tasks().size() + " step(s)"));
        }
    }

    private boolean isStrike() {
        return kindIndex == 1;
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
        m.cruiseSpeed = parseDouble(speedBox.getValue(), DroneEntity.DEFAULT_CRUISE_SPEED);
        m.cruiseAltitude = parseDouble(altitudeBox.getValue(), DroneEntity.DEFAULT_CRUISE_ALTITUDE);
        m.batteryCapacity = parseDouble(batteryBox.getValue(), DroneBattery.DEFAULT_CAPACITY);
        m.releaseSpeed = parseDouble(releaseBox.getValue(), DroneEntity.DEFAULT_RELEASE_SPEED);
        m.formationSpacing = Formation.clampSpacing(
                parseDouble(spacingBox.getValue(), Formation.DEFAULT_SPACING));
        m.launchInterval = DroneMission.clampInterval((int) Math.round(
                parseDouble(intervalBox.getValue(), DroneMission.DEFAULT_LAUNCH_INTERVAL)));
        m.payloadId = isStrike() ? WARHEADS.get(Math.floorMod(warheadIndex, WARHEADS.size())) : null;
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
        // With a program the range is the whole route, not the first hop: the same figure the dispatcher
        // will refuse or accept the mission on.
        boolean programmed = !m.program.isEmpty();
        double range = programmed ? m.outboundDistance(origin) : origin.distanceTo(m.destination);
        boolean cargo = !isStrike();

        double outbound = PowerProfile.DEFAULT.costToTravel(Airframe.QUADCOPTER, range, m.cruiseSpeed, cargo);
        int outPercent = (int) Math.round(100.0 * outbound / Math.max(1.0, m.batteryCapacity));
        double shortfall = m.shortfall(origin, cargo);
        boolean roundTrip = m.canRoundTrip(origin, cargo);

        int ticks = (int) Math.round(range / Math.max(1.0E-3, m.cruiseSpeed)
                + m.cruiseAltitude / DroneEntity.DEFAULT_CLIMB_RATE);
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

        // The speed box will take any number, but the airframe will not. Solving the top speed from the same
        // model that flies the drone means this warns about exactly the speeds it will actually fail to hold.
        double top = Airframe.QUADCOPTER.topSpeed(PowerProfile.DEFAULT.massFactor(cargo));
        double commanded = isStrike() ? Math.max(m.cruiseSpeed, m.releaseSpeed) : m.cruiseSpeed;
        if (commanded > top) {
            lines.add(Component.literal(String.format("%.2f b/t is past this airframe's %.2f b/t limit",
                    commanded, top)).withStyle(s -> s.withColor(0xFFC060)));
        }

        if (isStrike()) {
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

    /**
     * What a handshake can honestly say.
     *
     * <p>Deliberately says nothing about range, direction or battery, because this screen does not know any
     * of it and must not: the destination is resolved on the server from the recipient's code, and the whole
     * point of the mode is that the machine you are standing at cannot tell you where your package went.
     * Even the refusal, if the battery is short, comes back without a distance attached.
     */
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
        this.renderBackground(gg, mouseX, mouseY, partialTick);
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
