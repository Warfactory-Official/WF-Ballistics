package com.wf.wflib.build;

import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.drone.DroneProgram;
import com.wf.wflib.drone.DroneSelfTest.Result;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.PowerProfile;
import com.wf.wflib.drone.ai.DroneBrain;
import com.wf.wflib.drone.ai.DroneNav;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.coord.CoordinationModels;
import com.wf.wflib.drone.ai.state.MusterHandler;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.drone.squad.Formations;
import com.wf.wflib.exchange.StationKind;
import com.wf.wflib.exchange.StationRecord;
import com.wf.wflib.exchange.StationRole;
import com.wf.wflib.work.WorkAssignment;
import com.wf.wflib.work.WorkJob;
import com.wf.wflib.work.WorkOrder;
import com.wf.wflib.work.WorkQueue;
import com.wf.wflib.work.WorkStatus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Checks for the construction foundations: the blueprint readers, the work queue's sharing rules, and the ordering
 * the plans impose.
 */
public final class BuildSelfTest {

    private BuildSelfTest() {
    }

    public static List<Result> runAll() {
        List<Result> out = new ArrayList<>();
        litematica(out);
        structures(out);
        queue(out);
        plans(out);
        roles(out);
        territory(out);
        working(out);
        return out;
    }

    // --- the territory gate ---

    private static void territory(List<Result> out) {
        BoundingBox anywhere = new BoundingBox(0, 60, 0, 32, 80, 32);
        TerritoryVerdict open = SiteRules.survey(null, null, anywhere);
        out.add(new Result("territory/unknowable-ground-is-allowed", open.allowed(),
                (WarforgeCompat.isActive() ? "WarForge present" : "no WarForge") + ", verdict " + open));

        boolean explained = true;
        for (TerritoryVerdict verdict : TerritoryVerdict.values()) {
            boolean hasReason = verdict.reason() != null && !verdict.reason().isBlank();
            explained &= verdict.allowed() != hasReason;
        }
        out.add(new Result("territory/every-refusal-gives-a-reason", explained,
                "checked " + TerritoryVerdict.values().length + " verdicts"));

        boolean was = WarforgeCompat.territoryProtectionEnabled();
        WarforgeCompat.setTerritoryProtectionEnabled(false);
        boolean offAllows = SiteRules.survey(null, null, anywhere).allowed();
        WarforgeCompat.setTerritoryProtectionEnabled(was);
        out.add(new Result("territory/gate-can-be-disabled",
                offAllows && WarforgeCompat.territoryProtectionEnabled() == was,
                "disabled allows: " + offAllows + ", restored to " + was));

        BoundingBox straddling = new BoundingBox(-20, 60, -20, 40, 70, 40);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        SiteRules.walk(straddling, null, pos -> {
            seen.add(net.minecraft.world.level.ChunkPos.asLong(pos));
            return TerritoryVerdict.ALLOWED;
        });
        java.util.Set<Long> needed = new java.util.HashSet<>();
        for (int x = straddling.minX(); x <= straddling.maxX(); x++) {
            for (int z = straddling.minZ(); z <= straddling.maxZ(); z++) {
                needed.add(net.minecraft.world.level.ChunkPos.asLong(new BlockPos(x, 60, z)));
            }
        }
        out.add(new Result("territory/survey-covers-every-chunk", seen.equals(needed),
                seen.size() + " chunks sampled, " + needed.size() + " touched by the box"));

        java.util.List<BlockPos> samples = new java.util.ArrayList<>();
        SiteRules.walk(straddling, null, pos -> {
            samples.add(pos);
            return TerritoryVerdict.ALLOWED;
        });
        boolean inside = samples.stream().allMatch(straddling::isInside);
        out.add(new Result("territory/samples-land-inside-the-job", inside,
                inside ? "every sample within the bounds" : "a sample fell outside the job"));

        // The first refusal is the one reported, and it reports where.
        BlockPos.MutableBlockPos where = new BlockPos.MutableBlockPos();
        TerritoryVerdict refused = SiteRules.walk(straddling, where,
                pos -> pos.getX() > 0 ? TerritoryVerdict.WAR_ZONE : TerritoryVerdict.ALLOWED);
        out.add(new Result("territory/refusal-reports-where",
                refused == TerritoryVerdict.WAR_ZONE && where.getX() > 0
                        && straddling.isInside(where),
                refused + " at " + where.toShortString()));
    }

    // --- the drone's side of a job ---

    private static void working(List<Result> out) {
        UUID job = UUID.nameUUIDFromBytes(new byte[]{9});
        ResourceLocation kind = BuildJobs.CONSTRUCT;

        WorkAssignment onOrder = new WorkAssignment(job, kind, 3, new BlockPos(10, 64, 10), 1, null, true);
        WorkAssignment onStation = new WorkAssignment(job, kind, -1, null, 0, new Vec3(0, 65, 0), true);
        WorkAssignment nothing = new WorkAssignment(job, kind, -1, null, 0, null, true);
        out.add(new Result("work/assignment-targets",
                onOrder.target() != null && onStation.target() != null && nothing.target() == null
                        && !onOrder.idle() && !onStation.idle() && nothing.idle(),
                "order -> " + onOrder.target() + ", station -> " + onStation.target()));

        WorkAssignment closed = onOrder.withSiteOpen(false);
        out.add(new Result("work/closed-site-drops-the-target",
                closed.target() == null && closed.idle() && closed.hasOrder(),
                "still holds the order: " + closed.hasOrder() + ", target " + closed.target()));

        out.add(new Result("work/arrival-state-follows-the-assignment",
                arrival(onOrder) == DroneState.WORK
                        && arrival(onStation) == DroneState.SUPPLY
                        && arrival(nothing) == DroneState.EXFIL
                        && arrival(closed) == DroneState.EXFIL,
                String.format("order->%s station->%s empty->%s closed->%s",
                        arrival(onOrder), arrival(onStation), arrival(nothing), arrival(closed))));

        DroneSnapshot working = jobDrone(onOrder);
        DroneSnapshot courier = jobDrone(null);
        out.add(new Result("work/job-drones-do-not-hold-formation",
                !DroneBrain.escorts(working, squadOf(working)) && DroneBrain.escorts(courier, squadOf(courier)),
                "with a job: " + DroneBrain.escorts(working, squadOf(working))
                        + ", without: " + DroneBrain.escorts(courier, squadOf(courier))));

        out.add(new Result("work/job-flights-do-not-muster",
                !MusterHandler.required(working, squadOf(working)),
                "muster required: " + MusterHandler.required(working, squadOf(working))));

        WorkAssignment back = WorkAssignment.load(onOrder.save());
        out.add(new Result("work/assignment-round-trip",
                back != null && back.jobId().equals(job) && new BlockPos(10, 64, 10).equals(back.order())
                        && back.orderId() == 3 && back.data() == 1 && back.siteOpen(),
                back == null ? "lost" : "order " + back.order() + " id " + back.orderId()));

        WorkAssignment savedClosed = WorkAssignment.load(closed.save());
        out.add(new Result("work/site-state-is-not-persisted",
                savedClosed != null && savedClosed.siteOpen(),
                "reloaded siteOpen: " + (savedClosed != null && savedClosed.siteOpen())));

        WorkQueue queue = WorkQueue.of(List.of(new WorkOrder(0, BlockPos.ZERO, 0, 1)));
        WorkJob suspendable = new WorkJob(job, kind, Level.OVERWORLD,
                new BoundingBox(BlockPos.ZERO), "test", 0L, queue, new CompoundTag());
        boolean beforeWorkable = suspendable.workable();
        suspendable.suspend("it is a war zone");
        boolean whileSuspended = suspendable.workable();
        boolean stillLive = !suspendable.over() && suspendable.queue().pending() == 1;
        suspendable.suspend(null);
        out.add(new Result("work/suspension-is-reversible",
                beforeWorkable && !whileSuspended && stillLive && suspendable.workable(),
                "workable " + beforeWorkable + " -> " + whileSuspended + " -> " + suspendable.workable()
                        + ", progress kept: " + stillLive));
    }

    private static DroneState arrival(WorkAssignment work) {
        return jobDrone(work).arrivalState();
    }

    /**
     * A snapshot of a follower drone in transit, with or without a job.
     */
    private static DroneSnapshot jobDrone(WorkAssignment work) {
        return new DroneSnapshot(UUID.nameUUIDFromBytes(new byte[]{(byte) 0x5b}), false,
                new Vec3(0, 80, 0), Vec3.ZERO, 0.0f, FlightAttitude.LEVEL, Airframe.AMAZOG,
                DroneNav.NONE, DroneState.TRANSIT, 10, new Vec3(10, 64, 10), null, DroneProgram.EMPTY,
                Vec3.ZERO, false, false, false, true, List.of(), 1.0e9, 1.0e9, PowerProfile.DEFAULT,
                0.75, 24.0, 0.35, 1.0, 60.0, 60.0, 7L, false, 2, work, 0L, 1L);
    }

    private static SquadView squadOf(DroneSnapshot member) {
        DroneSnapshot leader = new DroneSnapshot(UUID.nameUUIDFromBytes(new byte[]{(byte) 0x5c}), false,
                new Vec3(0, 80, 0), Vec3.ZERO, 0.0f, FlightAttitude.LEVEL, Airframe.AMAZOG,
                DroneNav.NONE, DroneState.TRANSIT, 10, new Vec3(10, 64, 10), null, DroneProgram.EMPTY,
                Vec3.ZERO, false, false, false, true, List.of(), 1.0e9, 1.0e9, PowerProfile.DEFAULT,
                0.75, 24.0, 0.35, 1.0, 60.0, 60.0, 7L, true, 2, member.assignment(), 0L, 2L);
        return new SquadView(7L, Formations.DEFAULT, 12.0, CoordinationModels.DEFAULT, null, leader,
                List.of(leader, member));
    }

    // --- blueprint formats ---

    private static void litematica(List<Result> out) {
        CompoundTag root = litematic(region(1, 1, 0, -2, -2, 1,
                new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1, 0, 0, 0}));
        try {
            Blueprint bp = LitematicaFormat.INSTANCE.read(root, BlueprintLibrary.blocks(), "test");
            boolean sized = bp.size().equals(new Vec3i(2, 2, 1));
            boolean placed = bp.at(0, 0, 0).is(Blocks.STONE);
            boolean elsewhereEmpty = bp.blocks() == 1;
            out.add(new Result("blueprint/litematica-negative-size", sized && placed && elsewhereEmpty,
                    String.format("size %s, %d block(s), corner %s", bp.size(), bp.blocks(),
                            bp.at(0, 0, 0).getBlock().getName().getString())));
        } catch (BlueprintFormat.BlueprintException e) {
            out.add(new Result("blueprint/litematica-negative-size", false, e.getMessage()));
        }

        CompoundTag two = litematic(region(0, 0, 0, 1, 1, 1,
                        new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1}),
                region(3, 0, 0, 1, 1, 1,
                        new String[]{"minecraft:air", "minecraft:oak_planks"}, new int[]{1}));
        try {
            Blueprint bp = LitematicaFormat.INSTANCE.read(two, BlueprintLibrary.blocks(), "test");
            boolean spans = bp.size().equals(new Vec3i(4, 1, 1));
            boolean both = bp.at(0, 0, 0).is(Blocks.STONE) && bp.at(3, 0, 0).is(Blocks.OAK_PLANKS);
            boolean gap = bp.at(1, 0, 0).isAir() && bp.at(2, 0, 0).isAir();
            out.add(new Result("blueprint/litematica-multi-region", spans && both && gap,
                    String.format("size %s, %d block(s)", bp.size(), bp.blocks())));
        } catch (BlueprintFormat.BlueprintException e) {
            out.add(new Result("blueprint/litematica-multi-region", false, e.getMessage()));
        }

        CompoundTag noAir = litematic(region(0, 0, 0, 2, 1, 1,
                new String[]{"minecraft:stone", "minecraft:dirt"}, new int[]{0, 1}));
        try {
            Blueprint bp = LitematicaFormat.INSTANCE.read(noAir, BlueprintLibrary.blocks(), "test");
            out.add(new Result("blueprint/palette-zero-is-air",
                    bp.palette().get(0).isAir() && bp.at(0, 0, 0).is(Blocks.STONE)
                            && bp.at(1, 0, 0).is(Blocks.DIRT),
                    "palette " + bp.palette().size() + ", " + bp.blocks() + " block(s)"));
        } catch (BlueprintFormat.BlueprintException e) {
            out.add(new Result("blueprint/palette-zero-is-air", false, e.getMessage()));
        }

        // A file claiming a schema nobody has implemented is refused rather than read as garbage.
        CompoundTag future = litematic(region(0, 0, 0, 1, 1, 1,
                new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1}));
        future.putInt("Version", 99);
        boolean refused;
        try {
            LitematicaFormat.INSTANCE.read(future, BlueprintLibrary.blocks(), "test");
            refused = false;
        } catch (BlueprintFormat.BlueprintException e) {
            refused = true;
        }
        out.add(new Result("blueprint/unknown-schema-refused", refused,
                refused ? "schema 99 rejected" : "schema 99 was read anyway"));
    }

    private static void structures(List<Result> out) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("size", ints(2, 1, 1));
        ListTag palette = new ListTag();
        palette.add(named("minecraft:stone"));
        palette.add(named("minecraft:structure_void"));
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(blockEntry(0, 0, 0, 0));
        blocks.add(blockEntry(1, 0, 0, 1));
        root.put("blocks", blocks);
        try {
            Blueprint bp = StructureNbtFormat.INSTANCE.read(root, BlueprintLibrary.blocks(), "test");
            // structure_void is "leave it alone", so it must not become an order.
            out.add(new Result("blueprint/structure-void-is-empty",
                    bp.blocks() == 1 && bp.at(0, 0, 0).is(Blocks.STONE) && bp.at(1, 0, 0).isAir(),
                    bp.blocks() + " block(s) of 2 cells"));
        } catch (BlueprintFormat.BlueprintException e) {
            out.add(new Result("blueprint/structure-void-is-empty", false, e.getMessage()));
        }
    }

    // --- the work queue ---

    private static void queue(List<Result> out) {
        UUID alice = UUID.nameUUIDFromBytes(new byte[]{1});
        UUID bob = UUID.nameUUIDFromBytes(new byte[]{2});

        List<WorkOrder> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orders.add(new WorkOrder(0, new BlockPos(i, 0, 0), 0, 0));
            orders.add(new WorkOrder(0, new BlockPos(i, 20, 0), 20, 0));
        }
        WorkQueue gated = WorkQueue.of(orders);
        boolean allLow = true;
        for (int i = 0; i < 4; i++) {
            WorkOrder got = gated.claim(alice, Vec3.ZERO, 0.75, 0);
            allLow &= got != null && got.sequence() == 0;
        }
        boolean nothingLeft = gated.claim(alice, Vec3.ZERO, 0.75, 0) == null;
        out.add(new Result("work/sequence-gate", allLow && nothingLeft,
                allLow ? "held the upper layer back until the lower one was claimed"
                        : "handed out an order past the lookahead"));

        // ...and lets go once the frontier moves.
        for (int id = 0; id < 4; id++) {
            gated.complete(alice, id);
        }
        WorkOrder next = gated.claim(alice, Vec3.ZERO, 0.75, 10);
        out.add(new Result("work/frontier-advances", next != null && next.sequence() == 20,
                next == null ? "nothing offered after the lower layer finished"
                        : "offered sequence " + next.sequence()));

        WorkQueue near = WorkQueue.of(List.of(new WorkOrder(0, new BlockPos(2, 0, 0), 0, 0)));
        WorkQueue far = WorkQueue.of(List.of(new WorkOrder(0, new BlockPos(2000, 0, 0), 0, 0)));
        near.claim(alice, Vec3.ZERO, 0.75, 0);
        far.claim(alice, Vec3.ZERO, 0.75, 0);
        long nearDeadline = near.claimOf(0).deadline();
        long farDeadline = far.claimOf(0).deadline();
        long flight = (long) (2000 / 0.75);
        out.add(new Result("work/deadline-scales-with-distance",
                farDeadline > nearDeadline && farDeadline > flight,
                String.format("2 blocks -> %d ticks, 2000 blocks -> %d (bare flight time %d)",
                        nearDeadline, farDeadline, flight)));

        WorkQueue lapsing = WorkQueue.of(List.of(new WorkOrder(0, BlockPos.ZERO, 0, 0)));
        lapsing.claim(alice, Vec3.ZERO, 0.75, 0);
        lapsing.abandon(alice);
        int afterAbandon = lapsing.attemptsOf(0);
        lapsing.claim(alice, Vec3.ZERO, 0.75, 0);
        lapsing.lapse(100000);
        int afterLapse = lapsing.attemptsOf(0);
        out.add(new Result("work/abandon-is-free-lapse-is-not",
                afterAbandon == 0 && afterLapse == 1,
                "attempts after abandon " + afterAbandon + ", after lapse " + afterLapse));

        // Retried to the limit, an order is written off rather than handed out forever.
        WorkQueue stubborn = WorkQueue.of(List.of(new WorkOrder(0, BlockPos.ZERO, 0, 0)));
        for (int i = 0; i < WorkQueue.MAX_ATTEMPTS; i++) {
            WorkOrder got = stubborn.claim(alice, Vec3.ZERO, 0.75, i);
            if (got != null) {
                stubborn.release(alice, got.id(), true);
            }
        }
        out.add(new Result("work/blocked-after-max-attempts",
                stubborn.statusOf(0) == WorkStatus.BLOCKED && stubborn.finished()
                        && stubborn.blocked() == 1 && stubborn.done() == 0,
                "status " + stubborn.statusOf(0) + ", finished " + stubborn.finished()
                        + ", done " + stubborn.done()));

        // Two workers standing on the same spot must not be sent to the same corner of the site.
        List<WorkOrder> spread = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            spread.add(new WorkOrder(0, new BlockPos(i, 0, 0), 0, 0));
        }
        WorkQueue crowd = WorkQueue.of(spread);
        WorkOrder first = crowd.claim(alice, Vec3.ZERO, 0.75, 0);
        WorkOrder second = crowd.claim(bob, Vec3.ZERO, 0.75, 0);
        double apart = first == null || second == null ? 0.0
                : Math.sqrt(first.at().distSqr(second.at()));
        out.add(new Result("work/claims-repel", apart >= WorkQueue.CLAIM_SPACING,
                String.format("two workers at the same point claimed %.1f blocks apart (want >= %.1f)",
                        apart, WorkQueue.CLAIM_SPACING)));

        WorkQueue owned = WorkQueue.of(List.of(new WorkOrder(0, BlockPos.ZERO, 0, 0)));
        owned.claim(alice, Vec3.ZERO, 0.75, 0);
        boolean stranger = owned.complete(bob, 0);
        boolean holder = owned.complete(alice, 0);
        out.add(new Result("work/only-the-holder-completes", !stranger && holder,
                "stranger accepted: " + stranger + ", holder accepted: " + holder));

        // Round-trip, including a live claim. A build outlives a session.
        WorkQueue saved = WorkQueue.of(spread);
        saved.claim(alice, Vec3.ZERO, 0.75, 50);
        saved.complete(alice, saved.claimsOf(alice).get(0));
        saved.claim(bob, Vec3.ZERO, 0.75, 60);
        WorkQueue back = WorkQueue.load(saved.save());
        boolean same = back.size() == saved.size() && back.done() == saved.done()
                && back.claimed() == saved.claimed() && back.pending() == saved.pending()
                && back.claimsOf(bob).equals(saved.claimsOf(bob));
        out.add(new Result("work/nbt-round-trip", same,
                String.format("%d orders, %d done, %d claimed", back.size(), back.done(), back.claimed())));

        CompoundTag orphaned = saved.save();
        orphaned.put("Claims", new ListTag());
        WorkQueue recovered = WorkQueue.load(orphaned);
        out.add(new Result("work/orphaned-claims-recovered",
                recovered.claimed() == 0 && recovered.pending() == saved.pending() + 1
                        && recovered.attemptsOf(0) == 0,
                recovered.pending() + " pending, " + recovered.claimed() + " claimed"));
    }

    // --- plans ---

    private static void plans(List<Result> out) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState torch = Blocks.TORCH.defaultBlockState();

        // Within one layer, structure before the things that hang off it.
        Blueprint sideBySide = new Blueprint("t", new Vec3i(2, 1, 1),
                List.of(Blocks.AIR.defaultBlockState(), stone, torch), new int[]{1, 2}, 0, 0);
        WorkPlan flat = ConstructionPlan.of(sideBySide, BlockPos.ZERO);
        int stoneSeq = sequenceAt(flat, new BlockPos(0, 0, 0));
        int torchSeq = sequenceAt(flat, new BlockPos(1, 0, 0));
        out.add(new Result("plan/attachments-follow-structure", stoneSeq < torchSeq,
                "stone at sequence " + stoneSeq + ", torch at " + torchSeq));

        // ...and layers ascend regardless.
        Blueprint stacked = new Blueprint("t", new Vec3i(1, 3, 1),
                List.of(Blocks.AIR.defaultBlockState(), stone), new int[]{1, 1, 1}, 0, 0);
        WorkPlan tower = ConstructionPlan.of(stacked, BlockPos.ZERO);
        boolean ascending = sequenceAt(tower, new BlockPos(0, 0, 0)) < sequenceAt(tower, new BlockPos(0, 1, 0))
                && sequenceAt(tower, new BlockPos(0, 1, 0)) < sequenceAt(tower, new BlockPos(0, 2, 0));
        out.add(new Result("plan/layers-ascend", ascending && tower.size() == 3,
                tower.size() + " orders, ascending " + ascending));

        // A door is one order and one item, not two of each: placing the bottom half creates the top.
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        Blueprint doorway = new Blueprint("t", new Vec3i(1, 2, 1),
                List.of(Blocks.AIR.defaultBlockState(), lower, upper), new int[]{1, 2}, 0, 0);
        WorkPlan door = ConstructionPlan.of(doorway, BlockPos.ZERO);
        Integer doors = door.bill().get(Items.OAK_DOOR);
        out.add(new Result("plan/door-halves-count-once",
                door.size() == 1 && door.skipped() == 1 && doors != null && doors == 1,
                door.size() + " order(s), " + door.skipped() + " skipped, bill " + doors));

        Blueprint wall = new Blueprint("t", new Vec3i(4, 2, 1),
                List.of(Blocks.AIR.defaultBlockState(), stone), new int[]{1, 1, 1, 1, 1, 1, 1, 1}, 0, 0);
        WorkPlan billed = ConstructionPlan.of(wall, BlockPos.ZERO);
        Integer stones = billed.bill().get(Items.STONE);
        out.add(new Result("plan/bill-matches-order-count",
                stones != null && stones == billed.size() && stones == 8,
                billed.size() + " orders, bill " + stones));

        // Orders land where the origin says, not at the blueprint's own coordinates.
        WorkPlan offset = ConstructionPlan.of(stacked, new BlockPos(100, 64, -200));
        boolean placed = offset.orders().stream()
                .anyMatch(o -> o.at().equals(new BlockPos(100, 66, -200)));
        out.add(new Result("plan/origin-offsets-orders", placed,
                placed ? "top of the tower at 100, 66, -200" : "orders are not at the origin"));

        // Salvage is the mirror in both axes: down the layers, and attachments off before their support.
        int topStructure = Placement.salvageSequence(10, 10, stone);
        int topTorch = Placement.salvageSequence(10, 10, torch);
        int lowStructure = Placement.salvageSequence(0, 10, stone);
        out.add(new Result("plan/salvage-is-the-mirror",
                topTorch < topStructure && topStructure < lowStructure,
                String.format("top torch %d < top stone %d < bottom stone %d",
                        topTorch, topStructure, lowStructure)));

        Blueprint odd = new Blueprint("t", new Vec3i(2, 1, 1),
                List.of(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                        Blocks.POTTED_POPPY.defaultBlockState()), new int[]{1, 2}, 0, 0);
        WorkPlan noItem = ConstructionPlan.of(odd, BlockPos.ZERO);
        out.add(new Result("plan/itemless-blocks-reported",
                noItem.size() == 0 && noItem.unbuildable() == 2,
                noItem.size() + " orders, " + noItem.unbuildable() + " unbuildable"));

        Blueprint torches = new Blueprint("t", new Vec3i(1, 1, 1),
                List.of(Blocks.AIR.defaultBlockState(), Blocks.WALL_TORCH.defaultBlockState()),
                new int[]{1}, 0, 0);
        WorkPlan wallTorch = ConstructionPlan.of(torches, BlockPos.ZERO);
        out.add(new Result("plan/wall-variants-resolve-to-their-item",
                wallTorch.size() == 1 && wallTorch.bill().containsKey(Items.TORCH),
                wallTorch.size() + " order(s), bill " + wallTorch.bill().keySet()));
    }

    private static int sequenceAt(WorkPlan plan, BlockPos at) {
        for (WorkOrder order : plan.orders()) {
            if (order.at().equals(at)) {
                return order.sequence();
            }
        }
        return -1;
    }

    // --- station roles ---

    private static void roles(List<Result> out) {
        ResourceKey<Level> overworld = Level.OVERWORLD;

        // A station saved before roles existed did everything, and must keep doing everything.
        StationRecord legacy = new StationRecord("AAAAAAAAAAAA", overworld, BlockPos.ZERO, 0L);
        CompoundTag old = legacy.save();
        old.remove("Roles");
        StationRecord loaded = StationRecord.load(old);
        out.add(new Result("station/legacy-keeps-every-role",
                loaded.roles().equals(StationKind.STATION.roles()),
                "loaded as " + loaded.kind()));

        // Deliberately turning everything off is not the same as never having been asked.
        StationRecord inert = new StationRecord("BBBBBBBBBBBB", overworld, BlockPos.ZERO, 0L);
        inert.setRoles(EnumSet.noneOf(StationRole.class));
        StationRecord inertBack = StationRecord.load(inert.save());
        out.add(new Result("station/empty-roles-survive-a-save",
                inertBack.roles().isEmpty(), "loaded as " + inertBack.kind()));

        // The preset is a name for a set, not a type: set the roles by hand and the name follows.
        StationRecord provider = new StationRecord("CCCCCCCCCCCC", overworld, BlockPos.ZERO, 0L);
        provider.setRoles(StationKind.PROVIDER.roles());
        StationRecord providerBack = StationRecord.load(provider.save());
        boolean supplies = providerBack.has(StationRole.MATERIALS);
        boolean receives = providerBack.has(StationRole.DEPOT);
        out.add(new Result("station/provider-supplies-but-does-not-receive",
                supplies && !receives && "provider".equals(providerBack.kind()),
                "kind " + providerBack.kind() + ", materials " + supplies + ", depot " + receives));

        // An unnamed combination is a legitimate station, not a broken one.
        StationRecord custom = new StationRecord("DDDDDDDDDDDD", overworld, BlockPos.ZERO, 0L);
        custom.setRoles(EnumSet.of(StationRole.MATERIALS, StationRole.RECHARGE));
        out.add(new Result("station/custom-role-sets-are-legal",
                custom.kind().startsWith("custom") && custom.has(StationRole.RECHARGE),
                "kind " + custom.kind()));
    }

    // --- tag builders ---

    private static CompoundTag litematic(CompoundTag... regions) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion",
                SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        CompoundTag all = new CompoundTag();
        for (int i = 0; i < regions.length; i++) {
            all.put("r" + i, regions[i]);
        }
        root.put("Regions", all);
        return root;
    }

    /**
     * @param sizeX signed extents, exactly as Litematica writes them: negative means the region runs back
     *      from {@code posX}
     * @param cells palette indices in the region's own y/z/x order, from its <em>minimum</em> corner
     */
    private static CompoundTag region(int posX, int posY, int posZ, int sizeX, int sizeY, int sizeZ,
                                      String[] names, int[] cells) {
        CompoundTag tag = new CompoundTag();
        tag.put("Position", xyz(posX, posY, posZ));
        tag.put("Size", xyz(sizeX, sizeY, sizeZ));
        ListTag palette = new ListTag();
        for (String name : names) {
            palette.add(named(name));
        }
        tag.put("BlockStatePalette", palette);
        tag.putLongArray("BlockStates", pack(cells, PackedIndices.bitsFor(names.length)));
        return tag;
    }

    private static long[] pack(int[] values, int bits) {
        long[] out = new long[PackedIndices.longsFor(values.length, bits)];
        for (int i = 0; i < values.length; i++) {
            long start = (long) i * bits;
            int first = (int) (start >> 6);
            int offset = (int) (start & 63L);
            out[first] |= ((long) values[i] & 0xFFFFFFFFL) << offset;
            if (offset + bits > 64) {
                out[first + 1] |= ((long) values[i] & 0xFFFFFFFFL) >>> (64 - offset);
            }
        }
        return out;
    }

    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    private static CompoundTag named(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        return list;
    }

    private static CompoundTag blockEntry(int x, int y, int z, int state) {
        CompoundTag tag = new CompoundTag();
        tag.put("pos", ints(x, y, z));
        tag.putInt("state", state);
        return tag;
    }
}
