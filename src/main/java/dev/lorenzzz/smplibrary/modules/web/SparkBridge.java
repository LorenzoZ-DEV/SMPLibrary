package dev.lorenzzz.smplibrary.modules.web;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.StatisticWindow.CpuUsage;
import me.lucko.spark.api.statistic.StatisticWindow.MillisPerTick;
import me.lucko.spark.api.statistic.StatisticWindow.TicksPerSecond;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only class in the plugin that names spark's API types.
 *
 * <p>Loading this class resolves {@code me.lucko.spark.api.*}, so it must never be touched
 * unless spark is actually installed — {@link SparkHook} is the gate that guarantees that.
 * The constructor throws when the API is not reachable, which the hook reads as "try again
 * in a moment".
 *
 * <p>Every window spark exposes is polled and shipped, so the dashboard can show the real
 * numbers rather than a rounded subset: TPS over 5s/10s/1m/5m/15m, MSPT (mean, min, max,
 * median, 95th) over 10s/1m/5m, process and system CPU over 10s/1m/15m, and one entry per
 * garbage collector.
 */
final class SparkBridge {

    private final Spark spark;
    private final String source;

    SparkBridge() {
        Spark resolved = null;
        String how = null;

        // Preferred route: the Bukkit service registration, which is scoped to this server.
        // It only exists when spark runs as a plugin, and the lookup itself can throw on a
        // server that has the API classes but no registration, so it is guarded.
        try {
            RegisteredServiceProvider<Spark> registration =
                    Bukkit.getServicesManager().getRegistration(Spark.class);
            if (registration != null) {
                resolved = registration.getProvider();
                how = "the Bukkit service registration";
            }
        } catch (Throwable notRegistered) {
            // fall through to the singleton
        }

        // The route that works when spark is compiled into the server rather than installed as
        // a plugin (UniverseSpigot and friends): spark publishes a static instance regardless.
        // Throws IllegalStateException while spark is still starting, which the hook retries.
        if (resolved == null) {
            resolved = SparkProvider.get();
            how = "the SparkProvider singleton";
        }

        if (resolved == null) throw new IllegalStateException("spark API not available");
        this.spark = resolved;
        this.source = how;
    }

    /** How the handle was obtained — surfaced on the dashboard to confirm the integration. */
    String source() {
        return source;
    }

    String json() {
        StringBuilder json = new StringBuilder(768);
        json.append("{\"available\":true");
        json.append(",\"source\":").append(WebStats.jsonStr(source));
        json.append(",\"tps\":").append(tpsJson());
        json.append(",\"mspt\":").append(msptJson());
        json.append(",\"cpu\":").append(cpuJson());
        json.append(",\"gc\":").append(gcJson());
        return json.append('}').toString();
    }

    // -------------------------------------------------------------------- tps

    private String tpsJson() {
        DoubleStatistic<TicksPerSecond> tps = spark.tps();
        if (tps == null) return "null";   // not a ticking platform (a proxy, say)

        return "{\"5s\":" + num(tps.poll(TicksPerSecond.SECONDS_5))
                + ",\"10s\":" + num(tps.poll(TicksPerSecond.SECONDS_10))
                + ",\"1m\":" + num(tps.poll(TicksPerSecond.MINUTES_1))
                + ",\"5m\":" + num(tps.poll(TicksPerSecond.MINUTES_5))
                + ",\"15m\":" + num(tps.poll(TicksPerSecond.MINUTES_15)) + '}';
    }

    // ------------------------------------------------------------------- mspt

    private String msptJson() {
        GenericStatistic<DoubleAverageInfo, MillisPerTick> mspt = spark.mspt();
        if (mspt == null) return "null";

        return "{\"10s\":" + averageJson(mspt.poll(MillisPerTick.SECONDS_10))
                + ",\"1m\":" + averageJson(mspt.poll(MillisPerTick.MINUTES_1))
                + ",\"5m\":" + averageJson(mspt.poll(MillisPerTick.MINUTES_5)) + '}';
    }

    private static String averageJson(DoubleAverageInfo info) {
        if (info == null) return "null";
        return "{\"mean\":" + num(info.mean())
                + ",\"min\":" + num(info.min())
                + ",\"max\":" + num(info.max())
                + ",\"median\":" + num(info.median())
                + ",\"p95\":" + num(info.percentile95th()) + '}';
    }

    // -------------------------------------------------------------------- cpu

    private String cpuJson() {
        return "{\"process\":" + cpuSeriesJson(spark.cpuProcess())
                + ",\"system\":" + cpuSeriesJson(spark.cpuSystem()) + '}';
    }

    private static String cpuSeriesJson(DoubleStatistic<CpuUsage> usage) {
        if (usage == null) return "null";
        // spark reports a 0-1 fraction; the dashboard wants percent.
        return "{\"10s\":" + percent(usage.poll(CpuUsage.SECONDS_10))
                + ",\"1m\":" + percent(usage.poll(CpuUsage.MINUTES_1))
                + ",\"15m\":" + percent(usage.poll(CpuUsage.MINUTES_15)) + '}';
    }

    // --------------------------------------------------------------------- gc

    private String gcJson() {
        Map<String, GarbageCollector> collectors = spark.gc();
        if (collectors == null || collectors.isEmpty()) return "[]";

        List<GarbageCollector> sorted = new ArrayList<>(collectors.values());
        sorted.sort((a, b) -> Long.compare(b.totalTime(), a.totalTime()));

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            GarbageCollector gc = sorted.get(i);
            if (i > 0) json.append(',');
            json.append("{\"name\":").append(WebStats.jsonStr(gc.name()))
                    .append(",\"collections\":").append(gc.totalCollections())
                    .append(",\"totalTime\":").append(gc.totalTime())
                    .append(",\"avgTime\":").append(num(gc.avgTime()))
                    .append(",\"avgFrequency\":").append(gc.avgFrequency())
                    .append('}');
        }
        return json.append(']').toString();
    }

    // --------------------------------------------------------------- plumbing

    /**
     * JSON has no NaN or Infinity, and spark hands both back for a window it has not filled
     * yet. Emitting them raw produced a payload the browser refused to parse, which took the
     * whole snapshot down with it — so anything non-finite becomes {@code null}.
     */
    private static String num(double value) {
        if (!Double.isFinite(value)) return "null";
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    private static String percent(double fraction) {
        return num(fraction * 100.0);
    }
}
