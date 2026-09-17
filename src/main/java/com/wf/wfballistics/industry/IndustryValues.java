package com.wf.wfballistics.industry;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;

import java.util.List;

/** What a block is worth as a provocation. */
public final class IndustryValues {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Default whitelist. */
    public static final List<String> DEFAULTS = List.of(
            // Dirty: combustion and fuel burning.
            "gtceu:combustion_generator=30",
            "gtceu:advanced_combustion_generator=45",
            "gtceu:gas_turbine=25",
            "gtceu:advanced_gas_turbine=40",
            "gtceu:steam_turbine=15",
            "gtceu:large_combustion_engine=120",
            "gtceu:extreme_combustion_engine=200",
            "gtceu:large_gas_turbine=100",
            "gtceu:large_steam_turbine=60",
            "gtceu:coal_boiler=20",
            "gtceu:lava_boiler=20",
            // Dirty: chemistry and radioactives.
            "gtceu:large_chemical_reactor=60",
            "gtceu:distillation_tower=50",
            "gtceu:cracker=50",
            "gtceu:oil_cracking_unit=50",
            "gtceu:nuclear_reactor=250",
            "gtceu:fusion_reactor=400",
            // Clean production: present, but cheap.
            "gtceu:electric_blast_furnace=10",
            "gtceu:large_electric_compressor=6",
            "gtceu:processing_array=6",
            "gtceu:steam_oven=4",
            "minecraft:furnace=1",
            "minecraft:blast_furnace=2");

    private static final Object2IntOpenHashMap<ResourceLocation> WHITELIST = new Object2IntOpenHashMap<>();

    static {
        WHITELIST.defaultReturnValue(0);
    }

    private IndustryValues() {
    }

    /**
     * Replace the whitelist from config. Called on config load/reload.
     */
    public static void bake(List<? extends String> entries) {
        WHITELIST.clear();
        for (String entry : entries) {
            parse(entry);
        }
        LOGGER.debug("[wfballistics] industry whitelist baked with {} entries", WHITELIST.size());
    }

    private static void parse(String entry) {
        String text = entry.trim();
        if (text.isEmpty()) {
            return;
        }
        int split = text.indexOf('=');
        String rawId = split < 0 ? text : text.substring(0, split).trim();
        int value = 1;

        if (split >= 0) {
            try {
                value = Integer.parseInt(text.substring(split + 1).trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("[wfballistics] industry whitelist entry '{}' has an unreadable value, using 1", entry);
            }
        }

        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            LOGGER.warn("[wfballistics] industry whitelist entry '{}' is not a valid block id, skipped", entry);
            return;
        }
        if (value > 0) {
            WHITELIST.put(id, value);
        }
    }

    /** Register or override one block's value at runtime. */
    public static void put(ResourceLocation id, int value) {
        if (value > 0) {
            WHITELIST.put(id, value);
        } else {
            WHITELIST.removeInt(id);
        }
    }

    /**
     * @return what this block contributes, or 0 if it provokes nothing.
     */
    public static int valueOf(BlockState state) {
        return valueOf(state.getBlock());
    }

    public static int valueOf(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? 0 : WHITELIST.getInt(id);
    }

    public static int size() {
        return WHITELIST.size();
    }
}
