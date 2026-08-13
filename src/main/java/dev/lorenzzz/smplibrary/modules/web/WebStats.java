package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import dev.lorenzzz.smplibrary.modules.playerstats.PlayerStatsModule;
import dev.lorenzzz.smplibrary.modules.playerstats.PlayerStatsModule.Peak;
import dev.lorenzzz.smplibrary.modules.playerstats.PlayerStatsModule.Sample;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of live JVM + plugin state, hand-serialised to JSON.
 * ponytail: no Jackson dependency, ~one spot builds the whole string.
 */
public final class WebStats {

    /** Upper bound on points sent to the chart — more than this is invisible at chart width. */
    private static final int CHART_POINTS = 180;

    private final Smp_library plugin;
    private final long startedAt;

    public WebStats(Smp_library plugin, long startedAt) {
        this.plugin = plugin;
        this.startedAt = startedAt;
    }

    public String snapshot() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long used = total - runtime.freeMemory();

        double[] tps = { 20, 20, 20 };
        try {
            double[] live = Bukkit.getTPS();
            if (live != null && live.length >= 3) tps = live;
        } catch (Throwable ignored) {
            // not a Paper server, or TPS not available yet
        }

        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        json.append("\"now\":").append(System.currentTimeMillis()).append(',');
        json.append("\"uptime\":").append(System.currentTimeMillis() - startedAt).append(',');
        json.append("\"server\":").append(jsonStr(Bukkit.getVersion())).append(',');
        json.append("\"plugin\":").append(jsonStr(plugin.getPluginMeta().getVersion())).append(',');
        json.append("\"tps\":{\"1m\":").append(round(tps[0]))
                .append(",\"5m\":").append(round(tps[1]))
                .append(",\"15m\":").append(round(tps[2])).append("},");
        json.append("\"threads\":{\"count\":").append(threads.getThreadCount())
                .append(",\"daemon\":").append(threads.getDaemonThreadCount()).append("},");
        json.append("\"heap\":{\"used\":").append(used)
                .append(",\"total\":").append(total)
                .append(",\"max\":").append(runtime.maxMemory()).append("},");
        json.append("\"players\":{\"online\":").append(Bukkit.getOnlinePlayers().size())
                .append(",\"max\":").append(Bukkit.getMaxPlayers()).append("},");
        json.append("\"modules\":").append(modulesJson()).append(',');
        json.append("\"chatgames\":").append(chatgamesJson());
        json.append('}');
        return json.toString();
    }

    private String modulesJson() {
        ModuleManager modules = plugin.modules();
        if (modules == null) return "[]";

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Module module : modules.active()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(jsonStr(module.id()))
                    .append(",\"enabled\":").append(modules.isEnabled(module.id()))
                    // enabled in config but not started = it threw on boot; worth surfacing
                    .append(",\"running\":").append(modules.isRunning(module.id()))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private String chatgamesJson() {
        ModuleManager modules = plugin.modules();
        ChatGamesModule chatGames = modules == null ? null : modules.module("chatgames", ChatGamesModule.class);
        if (chatGames == null) return "{\"enabled\":false}";

        StringBuilder json = new StringBuilder("{\"enabled\":true");
        json.append(",\"active\":").append(chatGames.currentAnswer() != null);
        json.append(",\"type\":").append(jsonStr(chatGames.currentType() == null
                ? null : chatGames.currentType().name()));
        json.append(",\"prompt\":").append(jsonStr(chatGames.currentPrompt()));
        json.append(",\"answer\":").append(jsonStr(chatGames.currentAnswer()));
        json.append(",\"endsAt\":").append(chatGames.roundEndsAt());
        json.append(",\"history\":[");

        List<Map<String, String>> history = chatGames.recentGames();
        for (int i = 0; i < history.size(); i++) {
            Map<String, String> entry = history.get(i);
            if (i > 0) json.append(',');
            json.append("{\"time\":").append(jsonStr(entry.getOrDefault("time", "")))
                    .append(",\"event\":").append(jsonStr(entry.getOrDefault("event", "")))
                    .append(",\"type\":").append(jsonStr(entry.getOrDefault("type", "")))
                    .append(",\"detail\":").append(jsonStr(entry.getOrDefault("detail", "")))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    /**
     * The player-count history for the chart, as {@code [[timestamp, online], …]}.
     *
     * <p>Samples are bucketed down to at most {@link #CHART_POINTS} points — a day at one
     * sample a minute is 1440 readings, far more than a chart that wide can show. Each bucket
     * keeps its <em>maximum</em>, not its mean, because the peak is the thing being asked
     * about and averaging would flatten it away.
     */
    public String playerHistory() {
        ModuleManager modules = plugin.modules();
        PlayerStatsModule playerStats = modules == null
                ? null : modules.module("playerstats", PlayerStatsModule.class);
        if (playerStats == null) {
            return "{\"enabled\":false,\"points\":[],\"peak\":null}";
        }

        List<Sample> samples = playerStats.window();
        Peak peak = playerStats.peakToday();

        // The spacing of the points we actually send — which is the bucket width once
        // downsampling kicks in, not the raw sample interval. The chart uses this to tell a
        // real outage apart from normal spacing; sending the raw interval made every
        // downsampled point look like a gap and drew nothing at all.
        long resolution = playerStats.sampleIntervalMs();
        List<Sample> plotted = samples;
        if (samples.size() > CHART_POINTS) {
            long span = samples.get(samples.size() - 1).timestamp() - samples.get(0).timestamp();
            resolution = Math.max(resolution, Math.max(1L, span / CHART_POINTS));
            plotted = downsample(samples, resolution);
        }

        StringBuilder json = new StringBuilder(4096);
        json.append("{\"enabled\":true");
        json.append(",\"now\":").append(System.currentTimeMillis());
        json.append(",\"sampleInterval\":").append(playerStats.sampleIntervalMs());
        json.append(",\"resolution\":").append(resolution);
        json.append(",\"average\":").append(round(playerStats.average()));
        json.append(",\"peak\":");
        if (peak.online() <= 0) json.append("null");
        else json.append("{\"online\":").append(peak.online())
                .append(",\"at\":").append(peak.timestamp()).append('}');
        json.append(",\"points\":[");

        boolean first = true;
        for (Sample sample : plotted) {
            if (!first) json.append(',');
            first = false;
            json.append('[').append(sample.timestamp()).append(',').append(sample.online()).append(']');
        }
        return json.append("]}").toString();
    }

    /** Buckets by time so gaps stay gaps, keeping the max (and its real timestamp) per bucket. */
    private static List<Sample> downsample(List<Sample> samples, long bucketMs) {
        long from = samples.get(0).timestamp();
        List<Sample> out = new ArrayList<>(CHART_POINTS + 1);
        Sample best = null;
        long bucketEnd = from + bucketMs;

        for (Sample sample : samples) {
            if (sample.timestamp() > bucketEnd) {
                if (best != null) out.add(best);
                best = null;
                bucketEnd = sample.timestamp() + bucketMs;
            }
            if (best == null || sample.online() > best.online()) best = sample;
        }
        if (best != null) out.add(best);
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static String jsonStr(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
