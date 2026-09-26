package com.wf.wflib.rail.excavate;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The tunnel sections available to build with, read from {@code config/wflib-rail-profiles}.
 *
 * <p>Plain text files rather than a datapack, because a section is a drawing and the point is that
 * somebody can open one in any editor and change the shape of their railway. Nothing here is sent to a
 * client: the carve happens on the server, so a profile is server-side data with nothing to sync.</p>
 *
 * <p>The built-in sections are written out on first run so there is something to copy, and rewritten
 * only if they are missing. A file that does not parse is named in the log and skipped rather than
 * taking the rest of them with it.</p>
 */
public final class TunnelProfiles {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String DIRECTORY = "wflib-rail-profiles";
    private static final String SUFFIX = ".txt";

    private static final Map<String, TunnelProfile> LOADED = new LinkedHashMap<>();

    /** What ships, so there is a working example of each shape to copy. */
    private static final Map<String, String> BUILT_IN = Map.of(
            "standard", """
                    @name standard
                    @torch 8
                    ########
                    #......#
                    #......#
                    #......#
                    #......#
                    #......#
                    #L....L#
                    ########
                    """,
            "branch", """
                    @name branch
                    @torch 10
                    ######
                    #....#
                    #....#
                    #....#
                    #L..L#
                    ######
                    """,
            "arched", """
                    @name arched
                    @torch 8
                      ######
                     #......#
                    #........#
                    #........#
                    #........#
                    #........#
                    #L......L#
                    ##########
                    """);

    private TunnelProfiles() {
    }

    /** The sections that ship, by file name. Exposed so a test can hold them to the same rules. */
    public static Map<String, String> builtIn() {
        return BUILT_IN;
    }

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(DIRECTORY);
    }

    /** Write the built-ins if they are missing, then read everything in the directory. */
    public static void reload() {
        LOADED.clear();
        Path dir = directory();
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> entry : BUILT_IN.entrySet()) {
                Path file = dir.resolve(entry.getKey() + SUFFIX);
                if (!Files.exists(file)) {
                    Files.writeString(file, entry.getValue());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[wflib] could not write the built-in tunnel profiles to {}", dir, e);
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().forEach(file -> {
                String name = file.getFileName().toString();
                name = name.substring(0, name.length() - SUFFIX.length()).toLowerCase(Locale.ROOT);
                try {
                    TunnelProfile profile = TunnelProfile.parse(name, Files.readAllLines(file));
                    LOADED.put(name, profile);
                } catch (IOException | IllegalArgumentException e) {
                    // Named, and skipped. One bad drawing must not take the other sections with it.
                    LOGGER.warn("[wflib] tunnel profile {} was not loaded: {}", file, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[wflib] could not read tunnel profiles from {}", dir, e);
        }
        LOGGER.info("[wflib] {} tunnel profile(s) loaded from {}", LOADED.size(), dir);
    }

    /** @return the named section, or null. Loads the directory on first use. */
    public static TunnelProfile get(String name) {
        if (LOADED.isEmpty()) {
            reload();
        }
        return name == null ? null : LOADED.get(name.toLowerCase(Locale.ROOT));
    }

    /** @return every section that loaded, in name order. */
    public static List<TunnelProfile> all() {
        if (LOADED.isEmpty()) {
            reload();
        }
        return new ArrayList<>(LOADED.values());
    }

    public static List<String> names() {
        if (LOADED.isEmpty()) {
            reload();
        }
        return new ArrayList<>(LOADED.keySet());
    }
}
