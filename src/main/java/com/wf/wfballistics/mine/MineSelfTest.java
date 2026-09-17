package com.wf.wfballistics.mine;

import com.wf.wfballistics.drone.DroneSelfTest.Result;
import com.wf.wfballistics.item.MinePreset;
import com.wf.wfballistics.item.MinePresetRegistry;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-game checks on the mine models as they are actually shipped: that every asset is in the jar and parses, that
 * each one came in the right way up and the right way round, and that every preset draws as itself.
 */
public final class MineSelfTest {

    private MineSelfTest() {
    }

    public static List<Result> runAll() {
        List<Result> out = new ArrayList<>();
        models(out);
        presets(out);
        camo(out);
        return out;
    }

    private static void models(List<Result> out) {
        List<ResourceLocation> expected = List.of(MineModels.rl("ap"), MineModels.rl("bounding"),
                MineModels.rl("claymore"), MineModels.rl("heat"), MineModels.rl("naval"),
                MineModels.rl("scatter_ap"), MineModels.rl("scatter_at"),
                MineModels.rl("dispenser_ap"), MineModels.rl("dispenser_at"));

        out.add(new Result("mine/registered", MineModels.ids().containsAll(expected),
                "all nine shipped mines should be registered, got " + MineModels.ids()));
        out.add(new Result("mine/default-is-ap", MineModels.DEFAULT.equals(MineModels.rl("ap")),
                "the anti-personnel mine is the default model, got " + MineModels.DEFAULT));

        out.add(new Result("mine/retired-id-migrates",
                MineModels.parse("placeholder").equals(MineModels.DEFAULT),
                "a mine saved as the old box placeholder should come back as the default model, got "
                        + MineModels.parse("placeholder")));

        Set<ResourceLocation> assets = new HashSet<>();
        for (ResourceLocation id : MineModels.ids()) {
            String name = id.getPath();
            Vec3 size = MineModels.size(id);
            Vec3 center = MineModels.center(id);

            out.add(new Result("mine/" + name + "/asset-read",
                    Math.abs(size.x - 1.0) > 1.0E-6 || Math.abs(size.y - 1.0) > 1.0E-6,
                    id + " measured exactly 1x1x1, which is what an unreadable asset measures"));

            out.add(new Result("mine/" + name + "/sits-on-its-feet",
                    Math.abs(center.y - size.y / 2.0) < 0.02,
                    id + " is not centred half its height above its origin: centre " + center.y
                            + ", height " + size.y));

            out.add(new Result("mine/" + name + "/is-a-mine-sized-object",
                    size.x > 0.2 && size.x < 2.0 && size.y > 0.05 && size.y < 2.0,
                    id + " measures " + size + ", which is not a size anything lays in a minefield"));

            out.add(new Result("mine/" + name + "/has-its-own-asset", assets.add(MineModels.asset(id)),
                    id + " shares an asset with another model: " + MineModels.asset(id)));

            out.add(new Result("mine/" + name + "/id-round-trips",
                    MineModels.parse(id.toString()).equals(id) && MineModels.parse(name).equals(id),
                    id + " does not survive being written to NBT and read back"));
        }

        Vec3 ap = MineModels.size(MineModels.rl("ap"));
        out.add(new Result("mine/ap/lies-flat", ap.y < ap.x * 0.5,
                "the anti-personnel mine should be much wider than it is tall, got " + ap));
        Vec3 bounding = MineModels.size(MineModels.rl("bounding"));
        out.add(new Result("mine/bounding/stands-up", bounding.y > bounding.x,
                "the bounding mine should stand taller than it is wide, got " + bounding));

        Vec3 scatterAp = MineModels.size(MineModels.rl("scatter_ap"));
        Vec3 scatterAt = MineModels.size(MineModels.rl("scatter_at"));
        out.add(new Result("mine/scatter/ap-is-smaller-than-at",
                scatterAt.x > scatterAp.x && scatterAt.y > scatterAp.y,
                "the AP scatter mine should be smaller than the AT one in every dimension, got "
                        + scatterAp + " and " + scatterAt));

        Vec3 dispenserAp = MineModels.size(MineModels.rl("dispenser_ap"));
        Vec3 dispenserAt = MineModels.size(MineModels.rl("dispenser_at"));
        out.add(new Result("mine/scatter/a-rack-dwarfs-its-mine",
                dispenserAp.x > scatterAp.x * 2.0 && dispenserAt.x > scatterAt.x * 2.0,
                "a dispenser holds six of its mine and should look like it: got racks "
                        + dispenserAp + " / " + dispenserAt + " for mines " + scatterAp + " / "
                        + scatterAt));
    }

    private static void presets(List<Result> out) {
        MinePresetRegistry.bootstrap();

        boolean registered = true;
        boolean sized = true;
        for (MinePreset preset : MinePresetRegistry.all()) {
            registered &= MineModels.exists(preset.modelId());
            sized &= MineModels.size(preset.modelId()).y > 0.0;
        }

        out.add(new Result("mine/preset-models-registered", registered,
                "a preset names a model that is not registered: " + MinePresetRegistry.all()
                        .stream()
                        .map(p -> p.id() + " -> " + p.modelId())
                        .toList()));
        List<String> shares = new ArrayList<>();
        Map<ResourceLocation, List<MinePreset>> byModel = new HashMap<>();
        for (MinePreset preset : MinePresetRegistry.all()) {
            byModel.computeIfAbsent(preset.modelId(), key -> new ArrayList<>())
                    .add(preset);
        }
        for (Map.Entry<ResourceLocation, List<MinePreset>> shared : byModel.entrySet()) {
            if (shared.getValue()
                    .size() > 1) {
                shares.add(shared.getKey() + " <- " + shared.getValue()
                        .stream()
                        .map(preset -> preset.id()
                                .getPath())
                        .toList());
            }
        }
        out.add(new Result("mine/preset-models-are-distinct", shares.isEmpty(),
                "two mines that draw identically are one mine: " + shares));
        out.add(new Result("mine/preset-models-have-a-size", sized,
                "a preset's model measured no height, so the mine it lays would be flat"));

        Set<String> buriable = MinePresetRegistry.all()
                .stream()
                .filter(MinePreset::buriable)
                .map(preset -> preset.id()
                        .getPath())
                .collect(java.util.stream.Collectors.toSet());
        out.add(new Result("mine/buriable-are-the-ground-mines",
                buriable.equals(Set.of("ap", "bounding", "heat")),
                "the mines that can be dug in should be the three that sit on the earth, got " + buriable));

        warheads(out);
        sownMines(out);
        dispensers(out);
        defusal(out);
    }

    /**
     * The rack is a directional weapon, and these are the two halves of that being true: it watches a sector, and
     * it throws into the same one.
     */
    private static void dispensers(List<Result> out) {
        for (String rack : List.of("dispenser_ap", "dispenser_at")) {
            MinePreset preset = MinePresetRegistry.get(MinePresetRegistry.rl(rack));
            out.add(new Result("mine/" + rack + "/is-directional",
                    preset != null && preset.arc() == MineWarheads.DISPENSER_ARC
                            && preset.arc() < 360.0,
                    rack + " should watch the " + MineWarheads.DISPENSER_ARC + "-degree front it can "
                            + "cover, got " + (preset == null ? "no such preset" : preset.arc())));
        }

        for (String rack : List.of("dispenser_ap", "dispenser_at")) {
            MinePreset preset = MinePresetRegistry.get(MinePresetRegistry.rl(rack));
            int nodes = preset == null ? -1 : MineModels.canisterNodes(preset.modelId());
            out.add(new Result("mine/" + rack + "/canisters-match-the-model",
                    preset != null && preset.canisterCount() == nodes,
                    rack + " declares " + (preset == null ? "no preset" : preset.canisterCount())
                            + " canisters but its model has " + nodes + " canister nodes"));

            int payload = preset == null ? -1 : MineModels.payloadNodes(preset.modelId());
            int caps = preset == null ? -1 : MineModels.tubeCapNodes(preset.modelId());
            out.add(new Result("mine/" + rack + "/every-tube-holds-a-mine-and-a-cap",
                    payload == nodes && caps == nodes,
                    rack + " has " + nodes + " tube(s) but " + payload + " stowed mine(s) and "
                            + caps + " cap(s); they should match"));

            out.add(new Result("mine/" + rack + "/can-be-packed-up",
                    preset != null && preset.defuseMethod() != DefuseMethod.NONE,
                    rack + " is equipment meant to be recovered, so it needs a way of being packed up"));

            MinePreset load = preset == null || preset.canisterMineId() == null ? null
                    : MinePresetRegistry.get(preset.canisterMineId());
            out.add(new Result("mine/" + rack + "/loads-a-sown-mine",
                    load != null && load.sownOnly(),
                    rack + " should hold a sown-only mine, got "
                            + (preset == null ? "no preset"
                            : load == null ? "the unregistered " + preset.canisterMineId()
                            : load.id() + " which can also be laid by hand")));
        }

        List<String> racks = MinePresetRegistry.all()
                .stream()
                .filter(preset -> preset.canisterCount() > 0)
                .map(preset -> preset.id()
                        .getPath())
                .toList();
        out.add(new Result("mine/only-the-racks-carry-canisters",
                racks.equals(List.of("dispenser_ap", "dispenser_at")),
                "the dispensers should be the only mines that hold canisters, got " + racks));

        double spread = Math.toRadians(MineWarheads.DISPENSER_ARC);
        double half = spread * 0.5;
        int slots = MineWarheads.DISPENSER_CANISTERS;
        double jitter = MineWarheads.canisterJitter(spread, slots);
        List<String> escaped = new ArrayList<>();
        for (int canister = 0; canister < MineWarheads.DISPENSER_CANISTERS; canister++) {
            double offset = MineWarheads.canisterOffset(canister, spread, slots);
            if (Math.abs(offset) + jitter > half + 1.0E-9) {
                escaped.add("canister " + canister + " at "
                        + Math.round(Math.toDegrees(offset)) + " deg +/- "
                        + Math.round(Math.toDegrees(jitter)));
            }
        }
        out.add(new Result("mine/dispense/every-canister-lands-inside-the-arc", escaped.isEmpty(),
                "a rack must not sow outside the sector it watches, but these can: " + escaped));

        double landing = 8.0;
        double chord = 2.0 * landing * Math.sin(spread / MineWarheads.DISPENSER_CANISTERS / 2.0);
        MinePreset sownAp = MinePresetRegistry.get(MinePresetRegistry.rl("scatter_ap"));
        double cover = sownAp == null ? 0.0 : sownAp.triggerRange() * 2.0;
        out.add(new Result("mine/dispense/the-belt-has-no-gap", chord < cover,
                "canisters land " + String.format("%.1f", chord) + " blocks apart but each mine only "
                        + "covers " + String.format("%.1f", cover) + ", so the field has walk-through gaps"));
    }

    /** Which mines can be made safe again, and with what. */
    private static void defusal(List<Result> out) {
        List<String> wrongTag = MinePresetRegistry.all()
                .stream()
                .filter(preset -> preset.defuseMethod() == DefuseMethod.TOOL)
                .filter(preset -> !MineTags.DEFUSER.equals(preset.defuseTool()))
                .map(preset -> preset.id()
                        .getPath() + " -> " + preset.defuseTool())
                .toList();
        out.add(new Result("mine/defuse/tool-mines-name-the-defuser-tag", wrongTag.isEmpty(),
                "these mines want a tool nothing is tagged as: " + wrongTag));

        boolean anyTool = MinePresetRegistry.all()
                .stream()
                .anyMatch(preset -> preset.defuseMethod() == DefuseMethod.TOOL);
        boolean anyHand = MinePresetRegistry.all()
                .stream()
                .anyMatch(preset -> preset.defuseMethod() == DefuseMethod.HAND);
        boolean anyNone = MinePresetRegistry.all()
                .stream()
                .anyMatch(preset -> preset.defuseMethod() == DefuseMethod.NONE);
        out.add(new Result("mine/defuse/all-three-methods-are-used", anyTool && anyHand && anyNone,
                "there should be mines that need a defuser, mines that come apart by hand and mines that "
                        + "cannot be defused at all, got tool=" + anyTool + " hand=" + anyHand
                        + " none=" + anyNone));

        int defusers = BuiltInRegistries.ITEM.getTag(MineTags.DEFUSER)
                .map(HolderSet::size)
                .orElse(0);
        out.add(new Result("mine/defuse/the-defuser-tag-is-populated", defusers > 0,
                "nothing is tagged " + MineTags.DEFUSER.location() + ", so no mine that wants a tool can "
                        + "ever be defused: check the tag JSON is under tags/item"));

        List<String> toolless = MinePresetRegistry.all()
                .stream()
                .filter(preset -> preset.defuseMethod() == DefuseMethod.TOOL
                        && preset.defuseTool() == null)
                .map(preset -> preset.id()
                        .getPath())
                .toList();
        out.add(new Result("mine/defuse/a-tool-mine-names-its-tool", toolless.isEmpty(),
                "these mines claim to need a tool but name none, so they defuse bare-handed: " + toolless));
    }

    /** That every shipped mine carries a mine warhead. */
    private static void warheads(List<Result> out) {
        Set<ResourceLocation> mineWarheads = Set.of(MineWarheads.BLAST, MineWarheads.FRAG,
                MineWarheads.DIRECTIONAL, MineWarheads.HEAT, MineWarheads.NAVAL,
                MineWarheads.DISPENSE_AP, MineWarheads.DISPENSE_AT);
        List<String> borrowed = MinePresetRegistry.all()
                .stream()
                .filter(preset -> !mineWarheads.contains(preset.warheadId()))
                .map(preset -> preset.id()
                        .getPath() + " -> " + preset.warheadId()
                        .getPath())
                .toList();
        out.add(new Result("mine/warheads-are-purpose-built", borrowed.isEmpty(),
                "these presets carry a warhead that was not built for a mine: " + borrowed));

        boolean registered = mineWarheads.stream()
                .allMatch(WarheadRegistry::exists);
        out.add(new Result("mine/warheads-are-registered", registered,
                "a mine warhead is missing from the registry: " + mineWarheads.stream()
                        .filter(id -> !WarheadRegistry.exists(id))
                        .toList()));

        List<String> silent = mineWarheads.stream()
                .filter(id -> !id.equals(MineWarheads.DISPENSE_AP) && !id.equals(MineWarheads.DISPENSE_AT))
                .filter(id -> WarheadRegistry.peakEntityDamage(id) <= 0)
                .map(ResourceLocation::getPath)
                .toList();
        out.add(new Result("mine/warheads-declare-their-damage", silent.isEmpty(),
                "these mine warheads report no damage at all, so a tooltip calls them harmless: " + silent));

        double near = MineWarheads.expectedHits(400, 4.0, 4.0, 1.0);
        double far = MineWarheads.expectedHits(400, 4.0, 16.0, 1.0);
        out.add(new Result("mine/fragments-fall-off-as-an-inverse-square",
                Math.abs(near / 4.0 - far) < 1.0E-6,
                "twice the distance should be a quarter of the fragments, got " + near + " then " + far));
        out.add(new Result("mine/fragments-cannot-exceed-the-sleeve",
                MineWarheads.expectedHits(400, 0.01, 0.01, 10.0) <= 400.0,
                "a point-blank spray handed out more fragments than the sleeve holds"));
    }

    /** The mines that can only be sown, and the racks that sow them. */
    private static void sownMines(List<Result> out) {
        List<MinePreset> sown = MinePresetRegistry.all()
                .stream()
                .filter(MinePreset::sownOnly)
                .toList();

        out.add(new Result("mine/sown/exist", sown.size() >= 2,
                "there should be a sown mine for anti-personnel and one for anti-armour, got "
                        + sown.stream()
                        .map(preset -> preset.id()
                                .getPath())
                        .toList()));

        List<String> immortal = sown.stream()
                .filter(preset -> preset.selfDestructTicks() <= 0)
                .map(preset -> preset.id()
                        .getPath())
                .toList();
        out.add(new Result("mine/sown/self-destruct", immortal.isEmpty(),
                "these sown mines never expire, so a drone can litter a world permanently: " + immortal));

        List<String> steady = sown.stream()
                .filter(preset -> preset.tumble() <= 0.0f)
                .map(preset -> preset.id()
                        .getPath())
                .toList();
        out.add(new Result("mine/sown/tumble", steady.isEmpty(),
                "these sown mines would arrive pointing exactly where they were dropped: " + steady));

        List<String> diggable = sown.stream()
                .filter(MinePreset::buriable)
                .map(preset -> preset.id()
                        .getPath())
                .toList();
        out.add(new Result("mine/sown/are-not-buried", diggable.isEmpty(),
                "a mine that arrives from the air lands on the ground, it does not dig itself in: "
                        + diggable));

        List<String> clearable = sown.stream()
                .filter(preset -> preset.defuseMethod() != DefuseMethod.NONE)
                .map(preset -> preset.id()
                        .getPath())
                .toList();
        out.add(new Result("mine/sown/are-not-defusable", clearable.isEmpty(),
                "a scattered field is cleared by blowing it in place or waiting it out, not by walking "
                        + "into it with a shovel: " + clearable));

        for (String rack : List.of("dispenser_ap", "dispenser_at")) {
            MinePreset preset = MinePresetRegistry.get(MinePresetRegistry.rl(rack));
            out.add(new Result("mine/" + rack + "/is-a-deployable",
                    preset != null && !preset.sownOnly() && preset.requiresActivation()
                            && !preset.buriable() && preset.selfDestructTicks() == 0,
                    rack + " should be a hand-emplaced rack that is armed on purpose and does not "
                            + "expire, got " + (preset == null ? "no such preset"
                            : "sown=" + preset.sownOnly() + " armed-on-purpose="
                            + preset.requiresActivation() + " buriable=" + preset.buriable()
                            + " life=" + preset.selfDestructTicks())));
        }

        MinePreset naval = MinePresetRegistry.get(MinePresetRegistry.rl("naval"));
        MinePreset ap = MinePresetRegistry.get(MinePresetRegistry.rl("ap"));
        out.add(new Result("mine/only-the-naval-mine-waits-for-water",
                naval != null && naval.armsOnlyInWater() && ap != null && !ap.armsOnlyInWater(),
                "the naval mine should go inert out of water and a land mine should not"));

        out.add(new Result("mine/sown/cannot-also-be-laid",
                sown.stream()
                        .noneMatch(preset -> MinePresetRegistry.all()
                                .stream()
                                .anyMatch(other -> !other.sownOnly()
                                        && other.modelId().equals(preset.modelId()))),
                "a sown mine that also has a hand-laid twin is not a distinct mine"));
    }

    /** The camouflage themes. */
    private static void camo(List<Result> out) {
        out.add(new Result("mine/camo/unknown-id-is-the-default",
                MineCamo.parse("no-such-finish") == MineCamo.DEFAULT
                        && MineCamo.parse(null) == MineCamo.DEFAULT
                        && MineCamo.parse("") == MineCamo.DEFAULT,
                "an unrecognised finish should come back as the default, not as null"));

        boolean roundTrips = true;
        for (MineCamo camo : MineCamo.values()) {
            roundTrips &= MineCamo.parse(camo.id()) == camo;
        }
        out.add(new Result("mine/camo/ids-round-trip", roundTrips,
                "a finish does not survive being written to entity data and read back"));

        out.add(new Result("mine/camo/default-claims-nothing",
                MineCamo.DEFAULT.colors()
                        .isEmpty(),
                "the default finish is what an unclaimed colour falls back to, so it must claim none"));

        Map<MapColor, MineCamo> claimed = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (MineCamo camo : MineCamo.values()) {
            for (MapColor color : camo.colors()) {
                MineCamo previous = claimed.put(color, camo);
                if (previous != null) {
                    clashes.add(previous.id() + " and " + camo.id());
                }
            }
        }
        out.add(new Result("mine/camo/no-two-finishes-claim-one-ground", clashes.isEmpty(),
                "these finishes claim the same ground colour, and one of them is silently ignored: "
                        + clashes));
    }
}
