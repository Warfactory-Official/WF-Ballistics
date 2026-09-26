package com.wf.wflib.rail.excavate;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What one carve does: a volume to open, and the shell to line it with first.
 *
 * <p><b>The order is the whole of the fluid story.</b> Clearing the bore and then lining it opens the
 * tunnel to whatever the bore cut into, and an aquifer at y=0 fills it in the seconds before the wall
 * arrives. Lining first puts a complete watertight tube inside solid rock, and only then is the inside
 * taken out, so there is no moment at which the tunnel is both open and unlined. That holds for the
 * attended path, which really does change the world block by block over many ticks; the stored path
 * writes the chunk as a unit and nothing ever sees a half-state.</p>
 *
 * <p>The shell is placed unconditionally rather than only where something is missing. A tunnel wall
 * that is deepslate bricks in some places and whatever the bore happened to hit in others is not a
 * lining, it is a patch, and the one cell it skips is the one the water comes through.</p>
 *
 * @param bore what becomes air
 * @param shell the skin around it, or null for an unlined carve
 * @param lining the block id the shell is made of
 */
public record CarvePlan(CarveVolume bore, @Nullable CarveVolume shell, @Nullable CarveVolume lights,
                        String lining, String torch, LightingPolicy lighting, Stage stage) {

    /**
     * Which of the three passes this carve is.
     *
     * <p>They are separate submissions rather than three phases of one column, and that is the whole
     * lesson of the first attempt. Per column, the order was right and the result was still wrong: a
     * column that had been lined, cut and dried sat next to a column that was still full of lake, and
     * the lake came through the join. Doing each pass over the <em>whole</em> tunnel before starting the
     * next is the only version where the tube is sealed everywhere before any of it is opened.</p>
     */
    public enum Stage {

        /** Put the whole shell in, while the inside is still solid rock or still full of water. */
        LINE,

        /**
         * Take the fluid out of the sealed tube.
         *
         * <p>Its own pass because placing the lining tells every fluid block it touches to flow, and
         * those scheduled flows are still in the post for a few ticks afterwards. Removing fluid without
         * notifying anything schedules nothing new, so each pass leaves strictly less than it found and
         * repeating converges.</p>
         */
        DEWATER,

        /** Take the rock out. Nothing can flood it: the tube is sealed and there is no fluid left in it. */
        BORE,

        /**
         * Put the torches in.
         *
         * <p>After the bore, necessarily: a pass that cleared the tunnel would take them straight out
         * again. The positions come from the section's own drawing rather than from a rule about
         * walls, so a profile decides where its lights go.</p>
         */
        LIGHT
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What a tunnel is lined with when nothing says otherwise. */
    public static final String DEFAULT_LINING = "minecraft:deepslate_bricks";

    /** What a tunnel is lit with. A plain torch, which is a block with no state to carry. */
    public static final String DEFAULT_TORCH = "minecraft:torch";

    /** An unlined carve: terrain removed, nothing put back. */
    public static CarvePlan open(CarveVolume bore, LightingPolicy lighting) {
        return new CarvePlan(bore, null, null, DEFAULT_LINING, DEFAULT_TORCH, lighting, Stage.BORE);
    }

    /** A tunnel of a drawn section, with its own lining and its own lights. */
    public static CarvePlan tunnel(CarveVolume bore, CarveVolume shell, @Nullable CarveVolume lights,
                                   String lining, LightingPolicy lighting) {
        return new CarvePlan(bore, shell, lights, lining, DEFAULT_TORCH, lighting, Stage.LINE);
    }

    /** The same plan, as a different pass over the same volumes. */
    public CarvePlan at(Stage stage) {
        return new CarvePlan(this.bore, this.shell, this.lights, this.lining, this.torch, this.lighting,
                stage);
    }

    /** The same plan with a different bore, which is how one stretch of a longer tunnel is cut. */
    public CarvePlan withBore(CarveVolume bore) {
        return new CarvePlan(bore, this.shell, this.lights, this.lining, this.torch, this.lighting,
                this.stage);
    }

    /** @return whether this pass has anything at all to do. */
    public boolean hasWork() {
        return switch (this.stage) {
            case LINE -> this.shell != null;
            case LIGHT -> this.lights != null;
            default -> true;
        };
    }

    /** The block this pass places, or null for a pass that only removes. */
    public String blockFor() {
        return switch (this.stage) {
            case LINE -> this.lining;
            case LIGHT -> this.torch;
            default -> null;
        };
    }

    /** The volume this pass acts on. */
    public CarveVolume volumeFor() {
        return switch (this.stage) {
            case LINE -> this.shell;
            case LIGHT -> this.lights;
            default -> this.bore;
        };
    }

    /**
     * A lined tunnel: the corridor, wrapped in a one-block skin.
     *
     * <p>The skin wraps the ends as well as the sides, so a tunnel that stops in the middle of an
     * aquifer is capped rather than open. Continuing it later simply bores through its own end wall.</p>
     */
    public static CarvePlan tunnel(CarveVolume.Corridor bore, String lining, LightingPolicy lighting) {
        return tunnel(bore, bore, lining, lighting);
    }

    /**
     * One stretch of a longer tunnel.
     *
     * <p>The shell is the skin of this stretch minus the <em>whole</em> tunnel's bore, not minus this
     * stretch's. A stretch's skin reaches one block past its own ends and so overlaps the next stretch's
     * interior; subtracting only its own bore would let a stretch that lands late brick up a wall across
     * a piece of tunnel that was already open. Subtracting the whole bore makes the order the stretches
     * complete in stop mattering, which for a carve that is partly on worker threads and partly on the
     * server thread is the only workable answer.</p>
     *
     * @param stretch what this carve opens
     * @param whole every stretch's bore together, which nothing may line over
     */
    public static CarvePlan tunnel(CarveVolume.Corridor stretch, CarveVolume whole, String lining,
                                   LightingPolicy lighting) {
        return new CarvePlan(stretch, CarveVolume.difference(stretch.grown(1), whole), null, lining,
                DEFAULT_TORCH, lighting, Stage.LINE);
    }

    public boolean lined() {
        return this.shell != null;
    }

    /** Everything this carve touches, which is the shell when there is one. */
    public BoundingBox bounds() {
        return this.shell == null ? this.bore.bounds() : this.shell.bounds();
    }

    /** @return the block this pass places, as a state, or air when it places nothing. */
    public BlockState placedState() {
        String block = blockFor();
        return block == null ? Blocks.AIR.defaultBlockState() : stateOf(block);
    }

    /**
     * The lining as a block state.
     *
     * <p>Checked against the registry rather than taken from {@code get}, which answers with air for an
     * id it does not know: a mistyped lining would then be a tunnel with no walls at all and nothing
     * said about it.</p>
     */
    public BlockState liningState() {
        return stateOf(this.lining);
    }

    /** A block id as a state, falling back to the default lining when the id is not a block. */
    public static BlockState stateOf(String block) {
        ResourceLocation id = ResourceLocation.tryParse(block);
        if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
            return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        }
        LOGGER.warn("[wflib] no block '{}' to build a tunnel with; using deepslate bricks", block);
        return Blocks.DEEPSLATE_BRICKS.defaultBlockState();
    }
}
