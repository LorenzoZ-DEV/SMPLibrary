package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of live JVM + plugin state, hand-serialised to JSON.
 * ponytail: no Jackson dependency, ~one spot builds the whole string.
 */
public final class WebStats {

    private final Smp_library plugin;
    private final long startedAt;

    public WebStats(Smp_library plugin, long startedAt) {
        this.plugin = plugin;
        this.startedAt = startedAt;
    }

    public String snapshot() {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        Runtime r = Runtime.getRuntime();
        long total = r.totalMemory();
        long free = r.freeMemory();
        long used = total - free;
        long max = r.maxMemory();

        double tps1 = 20, tps5 = 20, tps15 = 20;
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null) { tps1 = tps[0]; tps5 = tps[1]; tps15 = tps[2]; }
        } catch (Throwable ignored) {}

        int online = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        long uptimeMs = System.currentTimeMillis() - startedAt;

        return snap(tps1, tps5, tps15,
                tb.getThreadCount(), tb.getDaemonThreadCount(),
                used, total, max,
                online, maxPlayers, uptimeMs);
    }

    private String snap(double tps1, double tps5, double tps15,
                        int threads, int daemons,
                        long heapUsed, long heapTotal, long heapMax,
                        int online, int maxPlayers, long uptimeMs) {
        StringBuilder s = new StringBuilder(800);
        s.append('{');
        s.append("\"uptime\":").append(uptimeMs).append(',');
        s.append("\"tps\":{\"1m\":").append(round(tps1))
         .append(",\"5m\":").append(round(tps5))
         .append(",\"15m\":").append(round(tps15)).append("},");
        s.append("\"threads\":{\"count\":").append(threads)
         .append(",\"daemon\":").append(daemons).append("},");
        s.append("\"heap\":{\"used\":").append(heapUsed)
         .append(",\"total\":").append(heapTotal)
         .append(",\"max\":").append(heapMax).append("},");
        s.append("\"players\":{\"online\":").append(online)
         .append(",\"max\":").append(maxPlayers).append("},");
        s.append("\"modules\":").append(modulesJson()).append(',');
        s.append("\"chatgames\":").append(chatgamesJson());
        s.append('}');
        return s.toString();
    }

    private String modulesJson() {
        ModuleManager mm = plugin.modules();
        if (mm == null) return "[]";
        Collection<Module> mods = mm.active();
        StringBuilder b = new StringBuilder();
        b.append('[');
        boolean first = true;
        for (Module m : mods) {
            if (!first) b.append(',');
            first = false;
            String id = jsonStr(m.id());
            boolean enabled = mm.isEnabled(m.id());
            b.append("{\"id\":").append(id)
             .append(",\"enabled\":").append(enabled).append('}');
        }
        b.append(']');
        return b.toString();
    }

    private String chatgamesJson() {
        ModuleManager mm = plugin.modules();
        if (mm == null) return "{}";
        Module m = mm.module("chatgames");
        if (!(m instanceof ChatGamesModule cgm)) return "{\"enabled\":false}";
        boolean active = cgm.currentAnswer() != null;
        String type = cgm.currentType() != null ? cgm.currentType().name() : null;
        List<Map<String, String>> recent = cgm.recentGames();
        StringBuilder b = new StringBuilder();
        b.append("{\"enabled\":true,\"active\":").append(active);
        b.append(",\"type\":").append(type == null ? "null" : jsonStr(type));
        b.append(",\"answer\":").append(jsonStr(cgm.currentAnswer()));
        b.append(",\"history\":[");
        boolean first = true;
        for (Map<String, String> e : recent) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"time\":").append(jsonStr(e.getOrDefault("time", "")))
             .append(",\"event\":").append(jsonStr(e.getOrDefault("event", "")))
             .append(",\"type\":").append(jsonStr(e.getOrDefault("type", "")))
             .append(",\"detail\":").append(jsonStr(e.getOrDefault("detail", "")))
             .append('}');
        }
        b.append("]}");
        return b.toString();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
        return b.toString();
    }
}
