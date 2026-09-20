package com.wf.wflib.debug;

import com.wf.wflib.WFLib;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GlyphidDeaths {

    /** One damage type's contribution, as hits landed and glyphids finished off. */
    private static final class Toll {
        int hits;
        int kills;
        double damage;
        /** Summed so the report can give a mean: whether they die at the lip or at its foot is the question. */
        double deathY;
    }

    private static final Map<String, Toll> TOLLS = new HashMap<>();
    private static boolean enabled;

    private GlyphidDeaths() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            TOLLS.clear();
        }
    }

    public static void reset() {
        TOLLS.clear();
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!enabled || !(event.getEntity() instanceof EntityGlyphid)) {
            return;
        }
        Toll toll = TOLLS.computeIfAbsent(event.getSource().getMsgId(), k -> new Toll());
        toll.hits++;
        toll.damage += event.getNewDamage();
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!enabled || !(event.getEntity() instanceof EntityGlyphid glyphid)) {
            return;
        }
        Toll toll = TOLLS.computeIfAbsent(event.getSource().getMsgId(), k -> new Toll());
        toll.kills++;
        toll.deathY += glyphid.getY();
    }

    /**
     * @return one line per damage type, deadliest first, or a single line saying nothing has died
     */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        if (!enabled) {
            lines.add("Death tally is off.");
            return lines;
        }
        List<Map.Entry<String, Toll>> ranked = new ArrayList<>(TOLLS.entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<String, Toll> e) -> -e.getValue().kills)
                .thenComparingDouble(e -> -e.getValue().damage));
        if (ranked.isEmpty()) {
            lines.add("Nothing has hurt a glyphid since the tally was cleared.");
            return lines;
        }
        for (Map.Entry<String, Toll> entry : ranked) {
            Toll toll = entry.getValue();
            lines.add(String.format(Locale.ROOT, "  %-16s %4d killed, %5d hits, %.0f damage%s",
                    entry.getKey(), toll.kills, toll.hits, toll.damage,
                    toll.kills == 0 ? "" : String.format(Locale.ROOT, ", mean death y=%.1f",
                            toll.deathY / toll.kills)));
        }
        return lines;
    }

    /** The tally as one machine-readable line: {@code deaths total=N type:kills:hits ...}, deadliest first. */
    public static String line() {
        List<Map.Entry<String, Toll>> ranked = new ArrayList<>(TOLLS.entrySet());
        ranked.sort(Comparator.comparingInt(e -> -e.getValue().kills));
        StringBuilder out = new StringBuilder("deaths total=");
        int total = 0;
        for (Map.Entry<String, Toll> entry : ranked) {
            total += entry.getValue().kills;
        }
        out.append(total);
        for (Map.Entry<String, Toll> entry : ranked) {
            Toll toll = entry.getValue();
            out.append(' ').append(entry.getKey()).append(':').append(toll.kills).append(':').append(toll.hits);
        }
        return out.toString();
    }

    public static int command(CommandSourceStack source) {
        for (String line : report()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal(line()), false);
        return 1;
    }

    public static int command(CommandSourceStack source, boolean on) {
        setEnabled(on);
        source.sendSuccess(() -> Component.literal("Glyphid death tally " + (on ? "on, cleared." : "off.")), false);
        return 1;
    }
}
