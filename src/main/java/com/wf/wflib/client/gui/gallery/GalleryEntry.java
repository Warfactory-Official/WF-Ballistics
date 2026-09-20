package com.wf.wflib.client.gui.gallery;

import com.wf.gemrender.gltf.AnimationDrive;
import com.wf.gemrender.gltf.GltfAnimation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** One model the gallery can draw, whatever kind of model it is. */
public interface GalleryEntry {

    /** What the registry calls this model. Unique within a category, not necessarily across them. */
    ResourceLocation id();

    /** The name under the cell. The path alone, since a gallery of one mod's models is all one namespace. */
    default String label() {
        return id().getPath();
    }

    /** The lines shown in the tooltip and beside an inspected model. Rebuilt per frame, so keep it cheap. */
    List<Component> info();

    /** Whether the model is loaded and can be drawn at all. */
    boolean ready();

    /** The clips this model can be scrubbed through. Empty for anything that does not move. */
    default List<Clip> clips() {
        return List.of();
    }

    /** The finishes this model carries, as GemRender variants. Empty for a model with only its base. */
    default List<Variant> variants() {
        return List.of();
    }

    /** Draws the model into the square the request describes. */
    void draw(Request request);

    /**
     * A clip plus the parameter that scrubs it.
     *
     * @param drive null for the entry that means "do not animate this at all"
     */
    record Clip(String name, @Nullable AnimationDrive drive) {

        /** The clip itself, or null for the still entry. */
        @Nullable
        public GltfAnimation animation() {
            return drive == null ? null : drive.clip();
        }

        /** Clip-local seconds for a phase in {@code 0..1}, in whatever units this drive was built in. */
        public float timeAt(float phase) {
            if (drive == null) {
                return 0.0f;
            }
            float capped = drive.cyclic() ? phase : Math.min(phase, 0.999f);
            return drive.timeAt(drive.from() + capped * (drive.to() - drive.from()));
        }
    }

    /**
     * One finish of a model, as the tile of the packed sheet it lives in.
     *
     * @param index the model's own variant index, which is not the position in this list: a mine
     *      declares a tile only for a camouflage somebody has drawn artwork for, so the slots
     *      are sparse and the gallery must carry the mapping rather than assume it
     */
    record Variant(String name, int index) {
    }

    /**
     * Everything a cell's draw needs, in one argument so that adding a control to the screen is not a change to two
     * draw signatures.
     *
     * @param size half the square's side in GUI pixels: a model is fitted so its longest axis spans the
     *      square, so this is the scale its unit box is drawn at
     * @param phase where through the selected clip to pose, {@code 0..1}
     */
    record Request(GuiGraphics graphics, MultiBufferSource.BufferSource buffers, float x, float y,
                   float size, float pitch, float yaw, int clip, float phase, int variant,
                   boolean normals, boolean cull) {
    }
}
