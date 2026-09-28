package com.wf.wflib.round.gametest;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.RoundPierceEvent;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.round.pen.PenMaterial;
import com.wf.wflib.round.pen.PenTable;
import com.wf.wflib.round.terrain.AsyncTerrainSource;
import com.wf.wflib.round.terrain.SectionSolidity;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * {@code wflib:penetration} data map: a world datapack value overrides the class, {@code /reload} rebuilds the table
 * both ways and re-decodes unloaded terrain, drill and blockPen read the same value.
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class PenTableGameTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPLATE = "empty";
    private static final String PACK = "wflib_pen_test";
    /** Used by no other test: the reload cannot move another test's numbers. */
    private static final Block BLOCK = Blocks.DRIED_KELP_BLOCK;
    /** Its entry is conditioned on a mod that is not loaded. */
    private static final Block ABSENT_MOD_BLOCK = Blocks.HAY_BLOCK;
    private static final float OVERRIDE = 123.0f;
    private static final float BLOCK_PEN = 200.0f;
    private static final double SPEED = 40.0;
    private static final ResourceLocation PIERCER = KineticPresetRegistry.rl("test_table_pierce");
    private static final ResourceLocation DRILL_AT = KineticPresetRegistry.rl("test_table_drill_at");
    private static final ResourceLocation DRILL_BELOW = KineticPresetRegistry.rl("test_table_drill_below");
    private static final List<RoundPierceEvent> PIERCES = new CopyOnWriteArrayList<>();

    static {
        KineticPresetRegistry.register(KineticPreset.builder(PIERCER, null, WarheadRegistry.rl("inert")).speed(SPEED)
                .drag(0.0).gravity(0.0).life(100).mass(0.004).caliber(5.56).blockPen(BLOCK_PEN).noChunkLoading()
                .build());
        KineticPresetRegistry.register(KineticPreset.builder(DRILL_AT, null, WarheadRegistry.rl("inert")).speed(4.0)
                .drag(0.0).gravity(0.0).life(100).penetration(1, OVERRIDE).build());
        KineticPresetRegistry.register(KineticPreset.builder(DRILL_BELOW, null, WarheadRegistry.rl("inert"))
                .speed(4.0).drag(0.0).gravity(0.0).life(100).penetration(1, OVERRIDE - 0.5).build());
    }

    @SubscribeEvent
    public static void onPierce(RoundPierceEvent event) {
        if (event.preset().id().equals(PIERCER)) {
            PIERCES.add(event);
        }
    }

    @GameTest(template = TEMPLATE, batch = "wflib_pen_table", timeoutTicks = 1200)
    public static void aDatapackValueOverridesTheClassUntilReloadedAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
        float before = PenTable.resistance(BLOCK.defaultBlockState());
        float absentBefore = PenTable.resistance(ABSENT_MOD_BLOCK.defaultBlockState());
        List<CompletableFuture<Void>> reload = new ArrayList<>(1);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos disk = new ChunkPos((o.getX() - 3200) >> 4, o.getZ() >> 4);
        ChunkPos build = new ChunkPos(disk.x, disk.z + VirtualFlightGameTest.BUILD_OFFSET);
        BlockPos kelp = new BlockPos(disk.getMinBlockX() + 5, 150, disk.getMinBlockZ() + 6);
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(build.x, build.z) != null,
                        "build chunk not loaded"))
                .thenExecute(() -> {
                    BlockPos b = kelp.offset(0, 0, VirtualFlightGameTest.BUILD_OFFSET * 16);
                    level.setBlockAndUpdate(b, BLOCK.defaultBlockState());
                    VirtualFlightGameTest.plant(level, build, disk);
                    level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
                    ForcedChunks.set(level, build.x, build.z, false);
                    helper.assertTrue(level.getChunkSource().getChunkNow(disk.x, disk.z) == null, "setup: loaded");
                })
                .thenWaitUntil(() -> helper.assertTrue(onDisk(terrain, kelp) == before, "disk " + onDisk(terrain, kelp)))
                .thenExecute(() -> {
                    helper.assertTrue(before != OVERRIDE, "class value already " + before);
                    writePack(pack);
                    reload.add(select(server, true));
                })
                .thenWaitUntil(() -> helper.assertTrue(reload.get(0).isDone(), "reloading"))
                .thenExecute(() -> {
                    reload.get(0).join();
                    float now = PenTable.resistance(BLOCK.defaultBlockState());
                    LOGGER.info("pen table: {} class {} -> datapack {}", BLOCK, before, now);
                    helper.assertTrue(now == OVERRIDE, "after reload " + now);
                    helper.assertTrue(terrain.peek(disk.x, disk.z) == null, "decoded column survived the reload");
                })
                .thenWaitUntil(() -> helper.assertTrue(onDisk(terrain, kelp) == OVERRIDE,
                        "disk after reload " + onDisk(terrain, kelp)))
                .thenExecute(() -> {
                    float absent = PenTable.resistance(ABSENT_MOD_BLOCK.defaultBlockState());
                    helper.assertTrue(absent == absentBefore, "entry of an absent mod applied: " + absent);
                    agree(helper, level);
                    reload.set(0, select(server, false));
                })
                .thenWaitUntil(() -> helper.assertTrue(reload.get(0).isDone(), "reloading"))
                .thenExecute(() -> {
                    reload.get(0).join();
                    deleteTree(pack);
                    float now = PenTable.resistance(BLOCK.defaultBlockState());
                    helper.assertTrue(now == before, "pack removed, still " + now);
                })
                .thenSucceed();
    }

    /** Shipped drill presets: METAL class drilled, ARMOR class stops them. */
    @GameTest(template = TEMPLATE)
    public static void apDrillsIronButNotObsidian(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos iron = BlockPos.containing(centre(helper, 8.0));
        BlockPos obsidian = iron.below();
        level.setBlockAndUpdate(iron, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(obsidian, Blocks.OBSIDIAN.defaultBlockState());
        long key = Rounds.launch(level, KineticPresetRegistry.get(KineticPresetRegistry.rl("ap")),
                centre(helper, 12.0), new Vec3(0.0, -12.0, 0.0), null, null);
        boolean flying = Rounds.step(level, key);
        boolean ironGone = level.getBlockState(iron).isAir();
        boolean obsidianKept = level.getBlockState(obsidian).is(Blocks.OBSIDIAN);
        level.setBlockAndUpdate(iron, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(obsidian, Blocks.AIR.defaultBlockState());
        helper.assertFalse(flying, "obsidian did not stop it");
        helper.assertTrue(ironGone && obsidianKept, "iron drilled " + ironGone + ", obsidian kept " + obsidianKept);
        float heat = KineticPresetRegistry.get(KineticPresetRegistry.rl("heat")).penetrationResistance();
        helper.assertTrue(heat >= PenMaterial.METAL.resistance && heat < PenMaterial.ARMOR.resistance, "heat " + heat);
        helper.succeed();
    }

    /** Stone: class value; data map absent for it. */
    @GameTest(template = TEMPLATE)
    public static void aBlockWithoutAValueKeepsItsClass(GameTestHelper helper) {
        helper.assertTrue(PenTable.resistance(Blocks.STONE.defaultBlockState()) == PenMaterial.STONE.resistance
                        && PenTable.resistance(Blocks.OAK_PLANKS.defaultBlockState()) == PenMaterial.WOOD.resistance
                        && PenTable.resistance(Blocks.BEDROCK.defaultBlockState()) == Float.POSITIVE_INFINITY,
                "class values moved");
        helper.succeed();
    }

    /** Decoded unloaded resistance at {@code pos}; NaN while decoding. */
    private static float onDisk(AsyncTerrainSource terrain, BlockPos pos) {
        SectionSolidity[] column = terrain.column(pos.getX() >> 4, pos.getZ() >> 4);
        return column == null ? Float.NaN : column[(pos.getY() >> 4) - terrain.minSection()].resistance(
                SectionSolidity.index(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * One block, both readers: blockPen 200 exits with {@code 1 - 123/200} of its energy; a drill rated 123 takes it,
     * rated 122.5 does not.
     */
    private static void agree(GameTestHelper helper, ServerLevel level) {
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(wall, BLOCK.defaultBlockState());
        PIERCES.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(PIERCER), centre(helper, 5.0),
                new Vec3(0.0, SPEED, 0.0), null, null);
        Rounds.step(level, key);
        Vec3 v = Rounds.velocity(level, key);
        Rounds.destroy(level, key);
        helper.assertTrue(v != null && PIERCES.size() == 1, "pierce " + PIERCES.size());
        double energy = v.lengthSqr() / (SPEED * SPEED);
        helper.assertTrue(Math.abs(energy - (1.0 - OVERRIDE / BLOCK_PEN)) < 1.0e-6, "energy out " + energy);
        helper.assertTrue(level.getBlockState(wall).is(BLOCK), "blockPen destroyed it");
        BlockPos floor = BlockPos.containing(centre(helper, 12.0));
        for (ResourceLocation drill : new ResourceLocation[]{DRILL_BELOW, DRILL_AT}) {
            level.setBlockAndUpdate(floor, BLOCK.defaultBlockState());
            long d = Rounds.launch(level, KineticPresetRegistry.get(drill), centre(helper, 15.0),
                    new Vec3(0.0, -4.0, 0.0), null, null);
            Rounds.step(level, d);
            Rounds.destroy(level, d);
            boolean drilled = level.getBlockState(floor).isAir();
            helper.assertTrue(drilled == (drill == DRILL_AT), drill + " drilled " + drilled);
        }
        level.setBlockAndUpdate(floor, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
    }

    private static void writePack(Path pack) {
        try {
            Path map = pack.resolve("data/wflib/data_maps/block/penetration.json");
            Files.createDirectories(map.getParent());
            Files.writeString(pack.resolve("pack.mcmeta"),
                    "{\"pack\": {\"pack_format\": 48, \"description\": \"wflib pen table test\"}}");
            Files.writeString(map, "{\"values\": {\"" + BuiltInRegistries.BLOCK.getKey(BLOCK) + "\": " + conditional(
                    WFLib.MODID, OVERRIDE) + ", \"" + BuiltInRegistries.BLOCK.getKey(ABSENT_MOD_BLOCK) + "\": "
                    + conditional("wflib_absent_test_mod", OVERRIDE) + "}}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The shipped file's form ({@code wfcore} entries). */
    private static String conditional(String mod, float resistance) {
        return "{\"neoforge:conditions\": [{\"type\": \"neoforge:mod_loaded\", \"modid\": \"" + mod
                + "\"}], \"resistance\": " + resistance + "}";
    }

    /** Test pack on/off, then {@code /reload}'s path. */
    private static CompletableFuture<Void> select(MinecraftServer server, boolean on) {
        server.getPackRepository().reload();
        List<String> ids = new ArrayList<>(server.getPackRepository().getSelectedIds());
        ids.remove("file/" + PACK);
        if (on) {
            ids.add("file/" + PACK);
        }
        return server.reloadResources(ids);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Vec3 centre(GameTestHelper helper, double height) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, origin.getY() + height, origin.getZ() + 0.5);
    }
}
