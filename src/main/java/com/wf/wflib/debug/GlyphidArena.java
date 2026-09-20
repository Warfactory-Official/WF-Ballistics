package com.wf.wflib.debug;

import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.entity.glyphid.GlyphidTracker;
import com.wf.wflib.entity.glyphid.nav.GlyphidBridges;
import com.wf.wflib.entity.glyphid.nav.GlyphidFlowFields;
import com.wf.wflib.entity.glyphid.sim.SimGlyphid;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Terrain a swarm has to solve, built on demand. */
public final class GlyphidArena {

    /** Clear of the field's CLIMB_UP, so a roof is a roof rather than a route. */
    private static final int UNCLIMBABLE = 10;

    private static final int MAZE_CELLS = 11;
    /** Corridor width plus one wall. Three wide, because a swarm in single file is not a swarm. */
    private static final int MAZE_PITCH = 4;
    private static final int MAZE_SPAN = MAZE_CELLS * MAZE_PITCH + 1;
    /** Fixed, so two runs of the maze arm are the same maze. */
    private static final long MAZE_SEED = 0x9E3779B97F4A7C15L;

    private static final int BOX_INNER = 13;
    private static final int BASE_WALL = 20;
    private static final int TERRAIN_HALF_WIDTH = 12;
    private static final int TOWER_HEIGHT = 14;

    private static @Nullable Layout current;

    /**
     * @param objective where the swarm is ordered, exactly: not heightmapped, since half of these put the
     *      goal under a roof and the heightmap would name the roof
     * @param goal the box that counts as having got there
     * @param escape when set, the swarm starts inside {@link #footprint} and the goal is being out of it
     * @param footprint everything the build wrote, so it can be wiped again
     * @param generators machines the swarm can destroy, for an arm where damage to a base is the score
     * @param expectation what this arena is supposed to prove, printed with the result so a run reads as a
     *      verdict rather than a number
     */
    public record Layout(String name, String material, BlockPos origin, Vec3 spawn, double spawnRadius,
                         BlockPos objective, AABB goal, boolean escape, AABB footprint,
                         List<BlockPos> generators, BlockState ground, String expectation) {
    }

    private GlyphidArena() {
    }

    public static final String[] NAMES = {"maze", "box", "base", "terrain", "tower"};

    public static @Nullable Layout current() {
        return current;
    }

    // --- building ---

    public static int build(CommandSourceStack source, String name, @Nullable String materialName) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        Block material = materialName == null ? null : material(materialName);
        if (materialName != null && material == null) {
            source.sendFailure(Component.literal("Unknown material '" + materialName
                    + "'. One of: stone, obsidian, bedrock."));
            return 0;
        }
        BlockState ground = level.getBlockState(origin.below());

        Layout layout = switch (name) {
            case "maze" -> maze(level, origin, material == null ? Blocks.OBSIDIAN : material, ground);
            case "box" -> box(level, origin, material == null ? Blocks.STONE : material, ground);
            case "base" -> base(level, origin, ground);
            case "terrain" -> terrain(level, origin, ground);
            case "tower" -> tower(level, origin, material == null ? Blocks.STONE : material, ground);
            default -> null;
        };
        if (layout == null) {
            source.sendFailure(Component.literal("Unknown arena '" + name + "'. One of: "
                    + String.join(", ", NAMES)));
            return 0;
        }
        current = layout;
        // The world just changed shape under every cached route in it.
        GlyphidFlowFields.clear();
        GlyphidBridges.clear();

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Arena %s (%s) at (%d, %d, %d). Spawn (%.0f, %.0f, %.0f) r%.0f, objective (%d, %d, %d).",
                layout.name(), layout.material(), origin.getX(), origin.getY(), origin.getZ(),
                layout.spawn().x, layout.spawn().y, layout.spawn().z, layout.spawnRadius(),
                layout.objective().getX(), layout.objective().getY(), layout.objective().getZ())), false);
        source.sendSuccess(() -> Component.literal("  " + layout.expectation()), false);
        return 1;
    }

    private static String named(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static @Nullable Block material(String name) {
        return switch (name) {
            case "stone" -> Blocks.STONE;
            case "obsidian" -> Blocks.OBSIDIAN;
            case "bedrock" -> Blocks.BEDROCK;
            default -> null;
        };
    }

    /** A real maze, carved out of a solid block rather than walled off. */
    private static Layout maze(ServerLevel level, BlockPos origin, Block wall, BlockState ground) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int top = oy + UNCLIMBABLE - 1;
        fill(level, ox, oy, oz, ox + MAZE_SPAN - 1, top, oz + MAZE_SPAN - 1, wall.defaultBlockState());

        boolean[] visited = new boolean[MAZE_CELLS * MAZE_CELLS];
        boolean[] eastOpen = new boolean[MAZE_CELLS * MAZE_CELLS];
        boolean[] southOpen = new boolean[MAZE_CELLS * MAZE_CELLS];
        carveMaze(visited, eastOpen, southOpen);

        for (int i = 0; i < MAZE_CELLS; i++) {
            for (int j = 0; j < MAZE_CELLS; j++) {
                int rx = ox + i * MAZE_PITCH + 1;
                int rz = oz + j * MAZE_PITCH + 1;
                room(level, rx, oy, rz, rx + MAZE_PITCH - 2, rz + MAZE_PITCH - 2);
                if (eastOpen[i + j * MAZE_CELLS]) {
                    int wx = ox + (i + 1) * MAZE_PITCH;
                    room(level, wx, oy, rz, wx, rz + MAZE_PITCH - 2);
                }
                if (southOpen[i + j * MAZE_CELLS]) {
                    int wz = oz + (j + 1) * MAZE_PITCH;
                    room(level, rx, oy, wz, rx + MAZE_PITCH - 2, wz);
                }
            }
        }
        // The way in, through the outer wall at the corner room.
        room(level, ox, oy, oz + 1, ox, oz + MAZE_PITCH - 2);

        int centre = MAZE_CELLS / 2;
        BlockPos objective = new BlockPos(ox + centre * MAZE_PITCH + 2, oy, oz + centre * MAZE_PITCH + 2);
        return new Layout("maze", named(wall), origin,
                new Vec3(ox - 10.5, oy, oz + 2.5), 6.0, objective,
                around(objective, 2.5, 3.0), false,
                new AABB(ox - 12, oy - 1, oz - 2, ox + MAZE_SPAN + 1, top + 1, oz + MAZE_SPAN + 1),
                List.of(), ground,
                "expect: the swarm threads " + MAZE_CELLS + "x" + MAZE_CELLS
                        + " rooms to the middle without chewing a shortcut, and arrives as a column rather "
                        + "than as stragglers.");
    }

    /** Depth-first backtracker on a fixed seed, so the maze is the same one every run. */
    private static void carveMaze(boolean[] visited, boolean[] eastOpen, boolean[] southOpen) {
        RandomSource random = RandomSource.create(MAZE_SEED);
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(0);
        visited[0] = true;
        int[] order = new int[4];
        while (!stack.isEmpty()) {
            int cell = stack.peek();
            int i = cell % MAZE_CELLS;
            int j = cell / MAZE_CELLS;
            int options = 0;
            for (int direction = 0; direction < 4; direction++) {
                int ni = i + (direction == 0 ? 1 : direction == 2 ? -1 : 0);
                int nj = j + (direction == 1 ? 1 : direction == 3 ? -1 : 0);
                if (ni < 0 || ni >= MAZE_CELLS || nj < 0 || nj >= MAZE_CELLS
                        || visited[ni + nj * MAZE_CELLS]) {
                    continue;
                }
                order[options++] = direction;
            }
            if (options == 0) {
                stack.pop();
                continue;
            }
            int direction = order[random.nextInt(options)];
            int ni = i + (direction == 0 ? 1 : direction == 2 ? -1 : 0);
            int nj = j + (direction == 1 ? 1 : direction == 3 ? -1 : 0);
            switch (direction) {
                case 0 -> eastOpen[cell] = true;
                case 1 -> southOpen[cell] = true;
                case 2 -> eastOpen[ni + nj * MAZE_CELLS] = true;
                default -> southOpen[ni + nj * MAZE_CELLS] = true;
            }
            visited[ni + nj * MAZE_CELLS] = true;
            stack.push(ni + nj * MAZE_CELLS);
        }
    }

    /** Carve one corridor cell: three blocks of headroom, which is what the field asks for plus one. */
    private static void room(ServerLevel level, int x0, int y, int z0, int x1, int z1) {
        fill(level, x0, y, z0, x1, y + 2, z1, Blocks.AIR.defaultBlockState());
    }

    /** A sealed room with the swarm inside it and somewhere to be outside. */
    private static Layout box(ServerLevel level, BlockPos origin, Block wall, BlockState ground) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int half = BOX_INNER / 2;
        int roof = oy + UNCLIMBABLE - 2;
        BlockState state = wall.defaultBlockState();
        fill(level, ox - half - 1, oy - 1, oz - half - 1, ox + half + 1, roof, oz + half + 1, state);
        fill(level, ox - half, oy - 1, oz - half, ox + half, oy - 1, oz + half, ground);
        fill(level, ox - half, oy, oz - half, ox + half, roof - 1, oz + half, Blocks.AIR.defaultBlockState());

        BlockPos objective = new BlockPos(ox + 36, oy, oz);
        AABB footprint = new AABB(ox - half - 1, oy - 1, oz - half - 1,
                ox + half + 2, roof + 1, oz + half + 2);
        boolean chewable = chewableByAGrunt(wall.defaultBlockState(), level, origin);
        return new Layout("box", named(wall), origin,
                new Vec3(ox + 0.5, oy, oz + 0.5), 5.0, objective,
                around(objective, 6.0, 4.0), true, footprint, List.of(), ground,
                chewable
                        ? "expect: a grunt chews 1.5 hardness, so the swarm breaches the wall and leaves "
                        + "together through one hole rather than each digging its own."
                        : "expect: nothing gets out at all, and a swarm with no route costs no more per tick "
                        + "than a swarm with one.");
    }

    /** A walled compound with a bunker in it, two people inside and four machines to lose. */
    private static Layout base(ServerLevel level, BlockPos origin, BlockState ground) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();

        shell(level, ox - BASE_WALL, oy, oz - BASE_WALL, ox + BASE_WALL, oy + 4, oz + BASE_WALL, brick);
        // A gate, so "went over the wall" and "went through the door" are different answers.
        fill(level, ox - 1, oy, oz + BASE_WALL, ox + 1, oy + 3, oz + BASE_WALL,
                Blocks.AIR.defaultBlockState());

        shell(level, ox - 6, oy, oz - 4, ox + 6, oy + 3, oz + 4, brick);
        fill(level, ox - 6, oy + 4, oz - 4, ox + 6, oy + 4, oz + 4, brick);
        fill(level, ox - 1, oy, oz + 4, ox, oy + 1, oz + 4, Blocks.AIR.defaultBlockState());

        List<BlockPos> generators = new ArrayList<>();
        BlockState furnace = Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        for (int dx = -4; dx <= 4; dx += 8) {
            for (int dz = -2; dz <= 2; dz += 4) {
                BlockPos at = new BlockPos(ox + dx, oy, oz + dz);
                level.setBlock(at, furnace, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                generators.add(at);
            }
        }

        BenchPlayer.place(level, "Alpha", new Vec3(ox - 1.5, oy, oz + 0.5));
        BenchPlayer.place(level, "Bravo", new Vec3(ox + 1.5, oy, oz + 0.5));

        return new Layout("base", "stone_bricks", origin,
                new Vec3(ox + 0.5, oy, oz + 40.5), 14.0, origin,
                new AABB(ox - 5, oy - 1, oz - 3, ox + 6, oy + 4, oz + 4), false,
                new AABB(ox - BASE_WALL - 1, oy - 1, oz - BASE_WALL - 1,
                        ox + BASE_WALL + 2, oy + 6, oz + BASE_WALL + 2),
                generators, ground,
                "expect: the swarm reaches two defended players inside a walled compound as a body, not in "
                        + "ones; the wall slows it and does not hold it.");
    }

    /**
     * Eighty blocks of ground that is not flat: a staircase, a drop, broken pillars, a ravine, a sheer plateau and
     * water.
     */
    private static Layout terrain(ServerLevel level, BlockPos origin, BlockState ground) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int z0 = oz - TERRAIN_HALF_WIDTH;
        int z1 = oz + TERRAIN_HALF_WIDTH;
        int x0 = ox - 56;
        int x1 = ox + 8;
        BlockState rock = Blocks.STONE.defaultBlockState();
        BlockState fence = Blocks.OBSIDIAN.defaultBlockState();

        for (int side = -1; side <= 1; side += 2) {
            int z = oz + side * (TERRAIN_HALF_WIDTH + 1);
            fill(level, x0, oy, z, x1, oy + UNCLIMBABLE - 1, z, fence);
            // The lip. Ten blocks of wall is a ramp to a climber; two blocks of overhang is not.
            fill(level, x0, oy + UNCLIMBABLE, z - side * 2, x1, oy + UNCLIMBABLE, z, fence);
        }

        // A staircase up six, then a plateau, then the same six straight back down as a drop.
        for (int step = 0; step < 6; step++) {
            fill(level, ox - 40 + step, oy, z0, ox - 40 + step, oy + step, z1, rock);
        }
        fill(level, ox - 34, oy, z0, ox - 30, oy + 5, z1, rock);

        for (int x = ox - 28; x <= ox - 20; x++) {
            for (int z = z0; z <= z1; z++) {
                int height = Math.floorMod((x - ox) * 7 + (z - oz) * 13, 5) - 1;
                if (height > 0) {
                    fill(level, x, oy, z, x, oy + height - 1, z, rock);
                }
            }
        }

        int ravineFloor = Math.max(level.getMinBuildHeight() + 1, oy - 7);
        fill(level, ox - 18, ravineFloor + 1, z0, ox - 15, oy + 2, z1, Blocks.AIR.defaultBlockState());
        fill(level, ox - 18, ravineFloor, z0, ox - 15, ravineFloor, z1, rock);
        int ravineDepth = oy - ravineFloor - 1;

        // A plateau with sheer sides: only a climber gets up it.
        fill(level, ox - 13, oy, z0, ox - 6, oy + 4, z1, rock);

        // Water, one deep. Whether a swarm drowns in a moat is a base-defence question with a real answer.
        fill(level, ox - 4, oy, z0, ox - 1, oy, z1, Blocks.WATER.defaultBlockState());

        return new Layout("terrain", "stone", origin,
                new Vec3(ox - 48.5, oy, oz + 0.5), 6.0, origin,
                around(origin, 5.0, 4.0), false,
                new AABB(x0 - 1, oy - 8, z0 - 2, x1 + 1, oy + UNCLIMBABLE + 1, z1 + 2),
                List.of(), ground,
                "expect: the swarm crosses all six obstacles (ravine floor at y=" + ravineFloor + ", "
                        + ravineDepth + " deep here) and arrives still bunched; nothing is left permanently "
                        + "stuck, and the water is survivable.");
    }

    /** The classic anti-spider build: a tower with a wider top. */
    private static Layout tower(ServerLevel level, BlockPos origin, Block material, BlockState ground) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        BlockState state = material.defaultBlockState();
        fill(level, ox - 3, oy, oz - 3, ox + 3, oy + TOWER_HEIGHT - 1, oz + 3, state);
        // Three blocks of overhang on every side.
        fill(level, ox - 6, oy + TOWER_HEIGHT, oz - 6, ox + 6, oy + TOWER_HEIGHT, oz + 6, state);

        int deck = oy + TOWER_HEIGHT + 1;
        BenchPlayer.place(level, "Roof", new Vec3(ox + 0.5, deck, oz + 0.5));
        BlockPos objective = new BlockPos(ox, deck, oz);
        boolean chewable = chewableByAGrunt(state, level, origin);
        return new Layout("tower", named(material), origin,
                new Vec3(ox + 0.5, oy, oz + 34.5), 12.0, objective,
                new AABB(ox - 7, deck - 1, oz - 7, ox + 8, deck + 4, oz + 8), false,
                new AABB(ox - 8, oy - 1, oz - 8, ox + 9, deck + 2, oz + 9),
                List.of(), ground,
                chewable
                        ? "expect: the lip stops the climb, and the swarm gets up anyway by eating into the "
                        + "shaft: a stone lip buys time, not safety."
                        : "expect: the lip holds. Glyphids stack up under the overhang and none reaches the "
                        + "top, which is what makes the material worth the cost.");
    }

    /** Whether a grunt's jaws open this: its dig ceiling is 10, and negative hardness is nobody's. */
    private static boolean chewableByAGrunt(BlockState state, ServerLevel level, BlockPos at) {
        float hardness = state.getDestroySpeed(level, at);
        return hardness >= 0.0F && hardness <= 10.0F;
    }

    // --- running one ---

    public static int launch(CommandSourceStack source, int count) {
        return launch(source, count, GlyphidCaste.GRUNT);
    }

    /** Send a swarm of one caste at the current arena. */
    public static int launch(CommandSourceStack source, int count, GlyphidCaste caste) {
        Layout layout = current;
        if (layout == null) {
            source.sendFailure(Component.literal("No arena built. Try: swarmbench arena maze"));
            return 0;
        }
        double reach = Math.max(layout.spawn().distanceTo(Vec3.atCenterOf(layout.objective())),
                layout.spawnRadius()) + 24.0;
        SwarmBench.spawnAt(source, count, layout.spawnRadius(), caste, layout.spawn(), reach);
        SwarmBench.march(source, Vec3.atCenterOf(layout.objective()), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Launched %d %s at the %s arena: objective (%d, %d, %d).", count, caste.lowerName(),
                layout.name(), layout.objective().getX(), layout.objective().getY(),
                layout.objective().getZ())), false);
        return count;
    }

    /** Send more, of a different caste, into an arm already running. */
    public static int reinforce(CommandSourceStack source, int count, GlyphidCaste caste) {
        Layout layout = current;
        if (layout == null) {
            source.sendFailure(Component.literal("No arena built. Try: swarmbench arena tower"));
            return 0;
        }
        double reach = Math.max(layout.spawn().distanceTo(Vec3.atCenterOf(layout.objective())),
                layout.spawnRadius()) + 24.0;
        SwarmBench.reinforce(source, count, layout.spawnRadius(), caste, layout.spawn(), reach);
        SwarmBench.march(source, Vec3.atCenterOf(layout.objective()), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Reinforced the %s arena with %d %s.", layout.name(), count, caste.lowerName())), false);
        return count;
    }

    /** How the current arena is going. */
    public static int report(CommandSourceStack source) {
        Layout layout = current;
        ServerLevel level = source.getLevel();
        if (layout == null) {
            source.sendSuccess(() -> Component.literal("No arena built."), false);
            return 0;
        }
        int reached = 0;
        int inside = 0;
        int alive = 0;
        for (EntityGlyphid glyphid : GlyphidTracker.glyphids(level)) {
            alive++;
            boolean in = layout.footprint().contains(glyphid.position());
            if (in) {
                inside++;
            }
            if (layout.escape() ? !in : layout.goal().contains(glyphid.position())) {
                reached++;
            }
        }
        for (SimGlyphid sim : SimGlyphidRegistry.get(level).view()) {
            alive++;
            Vec3 at = new Vec3(sim.x, sim.y, sim.z);
            boolean in = layout.footprint().contains(at);
            if (in) {
                inside++;
            }
            if (layout.escape() ? !in : layout.goal().contains(at)) {
                reached++;
            }
        }
        int intact = 0;
        for (BlockPos generator : layout.generators()) {
            if (level.getBlockState(generator).is(Blocks.FURNACE)) {
                intact++;
            }
        }
        int hits = 0;
        for (BenchPlayer player : BenchPlayer.placed()) {
            hits += player.hits();
        }

        final int got = reached;
        final int in = inside;
        final int live = alive;
        final int machines = intact;
        final int struck = hits;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Arena %s (%s): %d reached the objective, %d inside the arena, %d alive.",
                layout.name(), layout.material(), got, in, live)), false);
        if (!layout.generators().isEmpty()) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  %d of %d generators intact, %d hits landed on %d defenders.",
                    machines, layout.generators().size(), struck, BenchPlayer.placed().size())), false);
        }
        source.sendSuccess(() -> Component.literal("  " + layout.expectation()), false);
        for (String line : GlyphidCensus.report(level, 6.0)) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        for (String line : GlyphidSquadCensus.report(level)) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        for (String line : GlyphidDeaths.report()) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "arena name=%s material=%s reached=%d inside=%d alive=%d generators=%d/%d hits=%d",
                layout.name(), layout.material(), got, in, live, machines,
                layout.generators().size(), struck)), false);
        source.sendSuccess(() -> Component.literal(GlyphidSquadCensus.line(level)), false);
        source.sendSuccess(() -> Component.literal(GlyphidDeaths.line()), false);
        return got;
    }

    /** Put the ground back, take the defenders out, and forget the arena. */
    public static int clear(CommandSourceStack source) {
        Layout layout = current;
        if (layout == null) {
            BenchPlayer.clear();
            source.sendSuccess(() -> Component.literal("No arena to clear."), false);
            return 0;
        }
        ServerLevel level = source.getLevel();
        AABB box = layout.footprint();
        int floor = layout.origin().getY();
        if (box.minY < floor - 1) {
            fill(level, (int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX, floor - 2,
                    (int) box.maxZ, Blocks.STONE.defaultBlockState());
        }
        fill(level, (int) box.minX, floor - 1, (int) box.minZ, (int) box.maxX, floor - 1, (int) box.maxZ,
                layout.ground());
        fill(level, (int) box.minX, floor, (int) box.minZ, (int) box.maxX, (int) box.maxY, (int) box.maxZ,
                Blocks.AIR.defaultBlockState());
        BenchPlayer.clear();
        GlyphidFlowFields.clear();
        GlyphidBridges.clear();
        current = null;
        source.sendSuccess(() -> Component.literal("Arena " + layout.name() + " wiped."), false);
        return 1;
    }

    // --- writing blocks ---

    private static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1,
                             BlockState state) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinBuildHeight(), Math.min(y0, y1));
        int maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(y0, y1));
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    /** Four walls and no lid. */
    private static void shell(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1,
                              BlockState state) {
        fill(level, x0, y0, z0, x1, y1, z0, state);
        fill(level, x0, y0, z1, x1, y1, z1, state);
        fill(level, x0, y0, z0, x0, y1, z1, state);
        fill(level, x1, y0, z0, x1, y1, z1, state);
    }

    private static AABB around(BlockPos centre, double radius, double height) {
        return new AABB(centre.getX() - radius, centre.getY() - 1, centre.getZ() - radius,
                centre.getX() + radius + 1, centre.getY() + height, centre.getZ() + radius + 1);
    }
}
