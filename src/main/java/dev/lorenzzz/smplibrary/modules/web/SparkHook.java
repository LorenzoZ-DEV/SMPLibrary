package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Optional bridge to spark's Developer API — see https://spark.lucko.me/docs/Developer-API.
 *
 * <p>{@code spark-api} is {@code compileOnly}, so on a server without spark the
 * {@code me.lucko.spark.api.*} classes simply are not there. Every reference to them lives in
 * {@link SparkBridge}; this class is the only thing the rest of the plugin sees, and it does not
 * name a single spark type. The bridge is loaded lazily and only once the API is confirmed
 * loadable — touching it any earlier would blow up the first snapshot with
 * {@code NoClassDefFoundError}.
 *
 * <p>That confirmation is a classpath check, deliberately <em>not</em> a
 * {@code getPluginManager().getPlugin("spark")} check. Server forks increasingly ship spark
 * compiled into the server jar rather than as a plugin — UniverseSpigot does — and there the
 * plugin lookup returns null while the API is perfectly reachable. Asking the classloader
 * covers both shapes, and is the only question that actually matters.
 *
 * <p>Attachment is retried rather than done once at boot: load order is not ours to pick, and
 * spark commonly finishes initialising after us either way.
 */
public final class SparkHook {

    /** Never relocated by spark, precisely so third-party plugins can find it. */
    private static final String API_CLASS = "me.lucko.spark.api.Spark";

    private static final long RETRY_MS = 10_000L;

    private volatile SparkBridge bridge;
    private volatile long nextAttempt;
    private volatile boolean announced;
    private volatile String lastError;

    /**
     * Live metrics as a JSON object. Always returns a well-formed object: when spark is not
     * reachable the payload is {@code {"available":false,"reason":"…"}} and the dashboard
     * renders an explanatory panel instead of a broken tab.
     */
    public String json() {
        SparkBridge attached = bridge();
        if (attached == null) {
            return "{\"available\":false,\"reason\":" + WebStats.jsonStr(reason()) + "}";
        }
        try {
            return attached.json();
        } catch (Throwable failed) {
            // spark was disabled or reloaded under us — drop the handle and re-attach later.
            bridge = null;
            lastError = describe(failed);
            nextAttempt = System.currentTimeMillis() + RETRY_MS;
            return "{\"available\":false,\"reason\":"
                    + WebStats.jsonStr("spark call failed: " + lastError) + "}";
        }
    }

    /** True once the API handle is in hand. */
    public boolean attached() {
        return bridge() != null;
    }

    public void detach() {
        bridge = null;
        announced = false;
        lastError = null;
    }

    private SparkBridge bridge() {
        SparkBridge current = bridge;
        if (current != null) return current;

        long now = System.currentTimeMillis();
        if (now < nextAttempt) return null;
        nextAttempt = now + RETRY_MS;

        if (!apiOnClasspath()) {
            lastError = null;
            return null;
        }
        try {
            current = new SparkBridge();
        } catch (Throwable unavailable) {
            // API classes are there but spark has not published the instance yet.
            lastError = describe(unavailable);
            return null;
        }

        bridge = current;
        lastError = null;
        if (!announced) {
            announced = true;
            Log.info("&7Hooked into &fspark&7 via " + current.source()
                    + " — live performance metrics are on the dashboard.");
        }
        return current;
    }

    /**
     * Whether spark's API is loadable at all, however it got onto the server. Resolution is
     * cached by the classloader, so calling this on a retry costs nothing.
     */
    private static boolean apiOnClasspath() {
        try {
            Class.forName(API_CLASS, false, SparkHook.class.getClassLoader());
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    /** A message precise enough to act on, rather than a flat "spark not found". */
    private String reason() {
        if (!apiOnClasspath()) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("spark");
            return plugin != null
                    // A spark plugin whose API we cannot see means it was shaded and relocated.
                    ? "the spark plugin is installed but its API is not reachable — " + API_CLASS
                      + " is not on the classpath, which usually means a relocated build"
                    : "spark is not available on this server (no " + API_CLASS + " on the classpath)";
        }
        return lastError == null
                ? "spark is on this server but has not published its API yet — retrying"
                : "spark's API is present but not reachable yet (" + lastError + ")";
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
