package com.wf.wflib.item;

import com.wf.wflib.WFLib;
import com.wf.wflib.mine.DefuseMethod;
import com.wf.wflib.mine.MineModels;
import com.wf.wflib.mine.MineEntity;
import com.wf.wflib.mine.MineTags;
import com.wf.wflib.mine.MineTriggers;
import com.wf.wflib.mine.MineWarheads;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registry of deploy-ready {@link MinePreset}s, keyed by {@link ResourceLocation}. */
public final class MinePresetRegistry {

    private static final ResourceLocation DEFAULT_ID = rl("ap");
    /** Ticks in a minute, so the self-destruct lives below read as the times they are. */
    private static final int MINUTES = 20 * 60;
    /** Fragments in a bounding mine's sleeve, and balls in a claymore. */
    private static final int SLEEVE_FRAGMENTS = 600;
    private static final int CLAYMORE_BALLS = 700;
    private static final Map<ResourceLocation, MinePreset> PRESETS = new LinkedHashMap<>();
    private static boolean bootstrapped = false;

    private MinePresetRegistry() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT_ID;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null ? parsed : DEFAULT_ID;
    }

    public static void register(MinePreset preset) {
        PRESETS.put(preset.id(), preset);
    }

    @Nullable
    public static MinePreset get(ResourceLocation id) {
        return PRESETS.get(id);
    }

    public static boolean exists(ResourceLocation id) {
        return PRESETS.containsKey(id);
    }

    public static Collection<MinePreset> all() {
        return Collections.unmodifiableCollection(PRESETS.values());
    }

    public static ResourceLocation defaultId() {
        return DEFAULT_ID;
    }

    /** @return the presets that can only be sown, never laid by hand, in registration order. */
    public static Collection<MinePreset> sown() {
        return PRESETS.values()
                .stream()
                .filter(MinePreset::sownOnly)
                .toList();
    }

    /**
     * @return what a minelayer loads when nobody said which mine: the first sown-only preset there is,
     *      falling back to the plain default if a pack has removed all of them.
     */
    public static ResourceLocation defaultSownId() {
        return sown().stream()
                .findFirst()
                .map(MinePreset::id)
                .orElse(DEFAULT_ID);
    }

    /** @return one of the item a preset was laid from, or empty if that preset has no item. */
    public static ItemStack stackFor(@Nullable ResourceLocation presetId) {
        if (presetId == null) {
            return ItemStack.EMPTY;
        }
        return ModItems.mineItem(presetId)
                .map(holder -> new ItemStack(holder.get()))
                .orElse(ItemStack.EMPTY);
    }

    /** The built-ins, one per fuse so each of the three shipped detection types has something that uses it. */
    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        MineWarheads.bootstrap();

        register(MinePreset.builder(rl("ap"), MineModels.rl("ap"), MineWarheads.BLAST)
                .trigger(MineTriggers.rl("living"))
                .triggerRange(2.5)
                .sneakTriggerRange(0.9)
                .buriable()
                .tumbles()
                .armDelay(40)
                .defusable(DefuseMethod.HAND)
                .defuseTicks(40)
                .build());

        register(MinePreset.builder(rl("bounding"), MineModels.rl("bounding"), MineWarheads.FRAG)
                .trigger(MineTriggers.rl("living"))
                .triggerRange(3.5)
                .sneakTriggerRange(1.2)
                .buriable()
                .bounceHeight(MineEntity.BOUNDING_HEIGHT)
                .fragmentCount(SLEEVE_FRAGMENTS)
                .armDelay(60)
                .defusable(DefuseMethod.TOOL, MineTags.DEFUSER)
                .defuseTicks(80)
                .defuseFailChance(0.15f)
                .build());

        register(MinePreset.builder(rl("claymore"), MineModels.rl("claymore"), MineWarheads.DIRECTIONAL)
                .trigger(MineTriggers.rl("living"))
                .triggerRange(4.0)
                .sneakTriggerRange(1.5)
                .arc(60.0)
                .blastAlongFacing()
                .blastHalfAngle(35.0f)
                .fragmentCount(CLAYMORE_BALLS)
                .armDelay(40)
                .defusable(DefuseMethod.HAND)
                .defuseTicks(60)
                .build());

        register(MinePreset.builder(rl("heat"), MineModels.rl("heat"), MineWarheads.HEAT)
                .trigger(MineTriggers.rl("heavy"))
                .buriable()
                .triggerRange(1.5)
                .sneakTriggerRange(1.5)
                .armDelay(60)
                .defusable(DefuseMethod.TOOL, MineTags.DEFUSER)
                .defuseTicks(100)
                .defuseFailChance(0.1f)
                .build());

        register(MinePreset.builder(rl("naval"), MineModels.rl("naval"), MineWarheads.NAVAL)
                .trigger(MineTriggers.rl("living"))
                .floats(1.5)
                .armsOnlyInWater()
                .triggerRange(6.0)
                .sneakTriggerRange(6.0)
                .scanInterval(6)
                .armDelay(100)
                .defusable(DefuseMethod.TOOL, MineTags.DEFUSER)
                .defuseTicks(120)
                .defuseFailChance(0.25f)
                .build());

        sownMines();
    }

    /** The mines that can only be sown: dispensed from a rack or a drone, never laid by hand. */
    private static void sownMines() {
        register(MinePreset.builder(rl("scatter_ap"), MineModels.rl("scatter_ap"), MineWarheads.BLAST)
                .sownOnly()
                .trigger(MineTriggers.rl("living"))
                .triggerRange(2.0)
                .sneakTriggerRange(0.8)
                .tumbles()
                .armDelay(80)
                .selfDestructs(10 * MINUTES)
                .undefusable()
                .build());

        register(MinePreset.builder(rl("scatter_at"), MineModels.rl("scatter_at"), MineWarheads.HEAT)
                .sownOnly()
                .trigger(MineTriggers.rl("heavy"))
                .triggerRange(1.8)
                .sneakTriggerRange(1.8)
                .tumbles()
                .armDelay(100)
                .selfDestructs(20 * MINUTES)
                .undefusable()
                .build());

        dispensers();
    }

    /** The racks that sow the scatter mines. */
    private static void dispensers() {
        register(MinePreset.builder(rl("dispenser_ap"), MineModels.rl("dispenser_ap"),
                        MineWarheads.DISPENSE_AP)
                .trigger(MineTriggers.rl("living"))
                .triggerRange(10.0)
                .sneakTriggerRange(5.0)
                .arc(MineWarheads.DISPENSER_ARC)
                .canisters(MineWarheads.DISPENSER_CANISTERS, rl("scatter_ap"))
                .requiresActivation()
                .armDelay(60)
                .defusable(DefuseMethod.HAND)
                .defuseTicks(60)
                .build());

        register(MinePreset.builder(rl("dispenser_at"), MineModels.rl("dispenser_at"),
                        MineWarheads.DISPENSE_AT)
                .trigger(MineTriggers.rl("heavy"))
                .triggerRange(14.0)
                .sneakTriggerRange(14.0)
                .arc(MineWarheads.DISPENSER_ARC)
                .canisters(MineWarheads.DISPENSER_CANISTERS, rl("scatter_at"))
                .requiresActivation()
                .armDelay(60)
                .defusable(DefuseMethod.HAND)
                .defuseTicks(60)
                .build());
    }
}
