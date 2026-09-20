package com.wf.wflib.build;

import com.wf.wflib.build.BlueprintFormat.BlueprintException;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** The blueprints a server has on disk, in {@code <world>/wflib/blueprints/}. */
public final class BlueprintLibrary {

    /**
     * Where blueprints live, relative to the world folder.
     */
    public static final String FOLDER = "wflib/blueprints";
    /**
     * Largest file that will be opened, before decompression.
     */
    public static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    /** Quota on the decompressed tag. */
    public static final long MAX_TAG_BYTES = 64L * 1024L * 1024L;

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private BlueprintLibrary() {
    }

    public static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
    }

    /**
     * @return every file in the folder a registered format claims to read, by bare name, sorted. Cheap: this
     *      lists the directory and does not open anything
     */
    public static List<String> names(MinecraftServer server) {
        Path dir = directory(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String file = path.getFileName().toString();
                if (BlueprintFormats.forFile(file) != null) {
                    names.add(file);
                }
            });
        } catch (IOException ignored) {
            return List.of();
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /**
     * Load a blueprint by name, with or without its extension.
     *
     * @throws BlueprintException if the name escapes the folder, no file matches it, it is too big, or its
     *      format rejects it
     */
    public static Blueprint load(MinecraftServer server, String name) throws BlueprintException {
        Path path = resolve(server, name);
        String file = path.getFileName().toString();
        BlueprintFormat format = BlueprintFormats.forFile(file);
        if (format == null) {
            throw new BlueprintException("no reader for '" + file + "' (known: "
                    + String.join(", ", BlueprintFormats.extensions()) + ")");
        }
        long size;
        long modified;
        try {
            size = Files.size(path);
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new BlueprintException("cannot read '" + file + "': " + e.getMessage(), e);
        }
        if (size > MAX_FILE_BYTES) {
            throw new BlueprintException("'" + file + "' is " + (size / 1024L) + " KiB, limit is "
                    + (MAX_FILE_BYTES / 1024L) + " KiB");
        }
        Cached cached = CACHE.get(file);
        if (cached != null && cached.size == size && cached.modified == modified) {
            return cached.blueprint;
        }
        CompoundTag root = read(path, file);
        String bare = file.substring(0, file.lastIndexOf('.'));
        Blueprint blueprint = format.read(root, blocks(), bare);
        CACHE.put(file, new Cached(blueprint, size, modified));
        return blueprint;
    }

    /**
     * @return the block registry as a lookup. Taken from the built-in registries rather than a level, because
     *      blocks are not datapack-registered and a format should be loadable without a world, which is what lets
     *      {@code DroneSelfTest} drive the readers directly
     */
    public static HolderGetter<Block> blocks() {
        return BuiltInRegistries.BLOCK.asLookup();
    }

    private static CompoundTag read(Path path, String file) throws BlueprintException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            NbtAccounter quota = NbtAccounter.create(MAX_TAG_BYTES);
            in.mark(2);
            boolean gzipped = in.read() == 0x1F && in.read() == 0x8B;
            in.reset();
            return gzipped ? NbtIo.readCompressed(in, quota) : NbtIo.read(new DataInputStream(in), quota);
        } catch (IOException e) {
            throw new BlueprintException("cannot read '" + file + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new BlueprintException("'" + file + "' is not valid NBT: " + e, e);
        }
    }

    /**
     * @return the file this name refers to.
     */
    private static Path resolve(MinecraftServer server, String name) throws BlueprintException {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            throw new BlueprintException("no blueprint named");
        }
        List<String> available = names(server);
        for (String file : available) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (lower.equals(wanted) || lower.substring(0, lower.lastIndexOf('.')).equals(wanted)) {
                return directory(server).resolve(file);
            }
        }
        throw new BlueprintException(available.isEmpty()
                ? "no blueprints in " + FOLDER
                : "no blueprint '" + name + "' (have: " + String.join(", ", available) + ")");
    }

    /**
     * Forget everything cached. For {@code /wflib blueprint reload}, and for tests.
     */
    public static void invalidate() {
        CACHE.clear();
    }

    private record Cached(Blueprint blueprint, long size, long modified) {
    }
}
