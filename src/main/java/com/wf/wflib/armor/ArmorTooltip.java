package com.wf.wflib.armor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the piece actually does, on the piece.
 *
 * <p>Not decoration. The model is entirely made of numbers the player never otherwise sees, and a
 * model a player cannot see reads as random damage: "my rifle bounces off that guy" is a tactic only
 * if you can find out why.
 */
public final class ArmorTooltip {

    private static final String SEGMENT = "\u25A0";
    private static final String BULLET = " \u25B8 ";
    private static final String DOT = " \u00B7 ";

    private ArmorTooltip() {
    }

    public static void append(ItemStack stack, List<Component> tooltip, TooltipFlag flag) {
        ArmorSpec spec = ArmorProfiles.specFor(stack);
        if (spec == null) {
            return;
        }
        double fraction = ArmorStacks.conditionFraction(stack);
        tooltip.add(Component.literal("Condition ").withStyle(ChatFormatting.GRAY)
                .append(bar(fraction, 10))
                .append(Component.literal(" " + percent(fraction)).withStyle(conditionColor(fraction))));

        boolean dt = false;
        boolean dr = false;
        boolean tier = false;
        List<Component> rows = new ArrayList<>();
        for (ProtectionType type : ProtectionType.VALUES) {
            ProtectionRow row = spec.row(type);
            if (row.dt() <= 0.0D && row.dr() <= 0.0D && row.tier() <= 0.0D) {
                continue;
            }
            ChatFormatting colour = colour(type);
            MutableComponent line = Component.literal(BULLET).withStyle(colour)
                    .append(Component.literal(name(type)).withStyle(colour));
            String sep = "  ";
            if (row.dt() > 0.0D) {
                line.append(stat(sep, "DT ", trim(row.dt())));
                sep = DOT;
                dt = true;
            }
            if (row.dr() > 0.0D) {
                line.append(stat(sep, "DR ", percent(row.dr())));
                sep = DOT;
                dr = true;
            }
            if (row.tier() > 0.0D) {
                line.append(stat(sep, "tier ", trim(row.tier())));
                tier = true;
            }
            rows.add(line);
        }
        if (!rows.isEmpty()) {
            tooltip.add(Component.literal("Protection").withStyle(ChatFormatting.GOLD));
            tooltip.addAll(rows);
        }

        if (spec.insertSlots() > 0) {
            List<ItemStack> inserts = ArmorStacks.inserts(stack);
            MutableComponent header = Component.literal("Inserts ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(inserts.size() + "/" + spec.insertSlots()).withStyle(ChatFormatting.WHITE));
            if (!spec.insertFilter().isEmpty()) {
                header.append(Component.literal("  " + String.join(", ", spec.insertFilter().stream()
                        .sorted().map(ArmorTooltip::name).toList())).withStyle(ChatFormatting.DARK_GRAY));
            }
            tooltip.add(header);
            for (ItemStack insert : inserts) {
                double worn = ArmorStacks.conditionFraction(insert);
                tooltip.add(Component.literal(BULLET).withStyle(ChatFormatting.DARK_AQUA)
                        .append(insert.getHoverName().copy().withStyle(ChatFormatting.AQUA))
                        .append(Component.literal("  "))
                        .append(bar(worn, 5))
                        .append(Component.literal(" " + percent(worn)).withStyle(conditionColor(worn))));
            }
            for (int i = inserts.size(); i < spec.insertSlots(); i++) {
                tooltip.add(Component.literal(BULLET + "empty").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }

        if (!flag.hasShiftDown()) {
            tooltip.add(Component.literal("Hold Shift for details").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            return;
        }
        if (dt) {
            tooltip.add(legend("DT", "damage stopped outright per hit"));
        }
        if (dr) {
            tooltip.add(legend("DR", "share of the rest removed"));
        }
        if (tier) {
            tooltip.add(legend("tier", "hazard rate shrugged off entirely"));
        }
        if (spec.wornFloor() > 0.0D) {
            tooltip.add(legend("Spent", "keeps " + percent(spec.wornFloor()) + " of its protection"));
        }
    }

    private static Component stat(String sep, String label, String value) {
        return Component.literal(sep).withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(label).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static Component legend(String key, String text) {
        return Component.literal(key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component bar(double fraction, int segments) {
        int lit = (int) Math.ceil(fraction * segments - 1e-9);
        return Component.literal(SEGMENT.repeat(lit)).withStyle(conditionColor(fraction))
                .append(Component.literal(SEGMENT.repeat(segments - lit)).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static ChatFormatting conditionColor(double fraction) {
        return fraction > 0.5D ? ChatFormatting.GREEN : fraction > 0.2D ? ChatFormatting.YELLOW : ChatFormatting.RED;
    }

    private static ChatFormatting colour(ProtectionType type) {
        return switch (type) {
            case KINETIC -> ChatFormatting.YELLOW;
            case SHARP -> ChatFormatting.AQUA;
            case IMPACT -> ChatFormatting.BLUE;
            case BLAST -> ChatFormatting.GOLD;
            case ELECTRIC -> ChatFormatting.LIGHT_PURPLE;
            case THERMAL -> ChatFormatting.RED;
            case CHEMICAL -> ChatFormatting.GREEN;
            case RADIATION -> ChatFormatting.DARK_GREEN;
        };
    }

    private static String name(ProtectionType type) {
        String raw = type.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100.0D) + "%";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
