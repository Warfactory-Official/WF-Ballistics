package com.wf.wflib.stream;

import com.wf.wflib.config.WFConfig;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chunk streaming log, per category; both sides. */
public final class StreamDebug {

    public enum Category {
        SESSION,
        TICKET,
        CHUNK,
        CENTER,
        WAKEUP,
        CLIENT,
        CACHE
    }

    private static final Logger LOG = LogManager.getLogger("wflib|chunk-stream");
    private static final Map<String, String> LAST_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static volatile EnumSet<Category> override;
    private static volatile EnumSet<Category> resolved = EnumSet.noneOf(Category.class);

    private StreamDebug() {
    }

    /** @param categories null => follow config */
    public static void setOverride(EnumSet<Category> categories) {
        override = categories;
        refresh();
        LOG.info("debug categories overridden -> {}", categories == null ? "config" : categories);
    }

    public static void refresh() {
        EnumSet<Category> current = override;
        if (current != null) {
            resolved = current;
            return;
        }
        EnumSet<Category> fromConfig = EnumSet.noneOf(Category.class);
        if (WFConfig.SPEC.isLoaded()) {
            for (String raw : WFConfig.STREAM_DEBUG.get()) {
                String name = raw.trim().toUpperCase(Locale.ROOT);
                if (name.equals("ALL")) {
                    fromConfig = EnumSet.allOf(Category.class);
                    break;
                }
                fromConfig.add(Category.valueOf(name));
            }
        }
        resolved = fromConfig;
    }

    public static EnumSet<Category> active() {
        return resolved;
    }

    public static boolean on(Category category) {
        return resolved.contains(category);
    }

    public static void log(Category category, String format, Object... args) {
        if (on(category)) {
            LOG.info("[" + category + "] " + format, args);
        }
    }

    /** Unconditional: command output. */
    public static void report(String format, Object... args) {
        LOG.info(format, args);
    }

    public static void warn(Category category, String format, Object... args) {
        if (on(category)) {
            LOG.warn("[" + category + "] " + format, args);
        }
    }

    /** Logs {@code state} only when it differs from the last one under {@code key}. */
    public static void state(Category category, String key, String state) {
        String previous = LAST_STATE.put(key, state);
        if (!state.equals(previous) && on(category)) {
            LOG.info("[{}] {}: {}", category, key, state);
        }
    }

    public static int heartbeatTicks() {
        return WFConfig.SPEC.isLoaded() ? WFConfig.STREAM_DEBUG_HEARTBEAT.get() : 40;
    }

    public static Session session(UUID playerId, String playerName) {
        return SESSIONS.computeIfAbsent(playerId, id -> {
            log(Category.SESSION, "open {} ({})", playerName, shortId(id));
            return new Session(playerName);
        });
    }

    public static void endSession(UUID playerId, String reason) {
        Session session = SESSIONS.remove(playerId);
        if (session != null) {
            log(Category.SESSION, "close {} ({}) reason={} | {}", session.playerName, shortId(playerId), reason,
                    session.summary());
        }
        LAST_STATE.keySet().removeIf(key -> key.contains(shortId(playerId)));
    }

    public static List<String> status() {
        List<String> lines = new ArrayList<>();
        EnumSet<Category> categories = active();
        lines.add("categories=" + (categories.isEmpty() ? "off" : categories.toString())
                + (override != null ? " (command override)" : " (config)"));
        lines.add("heartbeat=" + heartbeatTicks() + "t radius=" + WFConfig.DETACHED_STREAM_RADIUS.get()
                + " budget=" + WFConfig.DETACHED_CHUNKS_PER_TICK.get() + "/tick");
        if (SESSIONS.isEmpty()) {
            lines.add("no active detached sessions");
        }
        for (Session session : SESSIONS.values()) {
            lines.add(session.playerName + ": " + session.summary());
        }
        lines.add("streamed chunks claimed: " + ChunkStreams.claimCount());
        return lines;
    }

    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public static final class Session {

        private final String playerName;
        public long chunksSent;
        public long ticketsIssued;
        public long centerUpdates;
        public int radius;
        public String host = "-";
        public ChunkPos center;
        private long sentAtLastHeartbeat;

        private Session(String playerName) {
            this.playerName = playerName;
        }

        public void heartbeat() {
            log(Category.SESSION, "heartbeat {}: {} (+{} chunks since last)",
                    this.playerName, summary(), this.chunksSent - this.sentAtLastHeartbeat);
            this.sentAtLastHeartbeat = this.chunksSent;
        }

        public String summary() {
            return String.format(Locale.ROOT, "host=%s center=%s r=%d sent=%d tickets=%d center-updates=%d",
                    this.host, this.center, this.radius, this.chunksSent, this.ticketsIssued, this.centerUpdates);
        }
    }
}
