package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

/** Small HTTP dashboard for editing config.yml and watching the server. Configured under MODULES.web. */
public final class WebModule extends AbstractModule {

    private static final List<String> CONNECTION_KEYS = List.of("host", "port", "token");
    private static final int PORT_SCAN_RANGE = 20;
    /** Five seconds — long enough for a bundled or plugin spark to have published its API. */
    private static final long SPARK_PROBE_DELAY_TICKS = 100L;

    private WebServer server;
    private WebAuth auth;
    private WebLog log;
    private SparkHook spark;
    private String boundHost;
    private int boundPort = -1;

    public WebModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "web"; }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = resolveConfig();
        if (cfg == null) {
            C.info("Web dashboard: no config section found, skipping.");
            return;
        }

        String host = cfg.getString("host", "0.0.0.0");
        int preferred = cfg.getInt("port", 8080);
        int port = findAvailablePort(preferred, preferred + PORT_SCAN_RANGE);
        if (port == -1) {
            C.warning("Web dashboard: no available port in range " + preferred + "-" + (preferred + PORT_SCAN_RANGE));
            return;
        }

        auth = new WebAuth(cfg.getString("token", null));
        spark = new SparkHook();
        log = new WebLog();
        server = new WebServer(plugin, auth,
                new WebStats(plugin, System.currentTimeMillis(), spark),
                new ConfigEditor(plugin, log), log);

        try {
            server.start(host, port);
            this.boundHost = host;
            this.boundPort = port;
            C.info("Web dashboard started on " + host + ":" + port);
            log.log("Web dashboard started on " + host + ":" + port);

            // Probe spark once the server has settled, purely so the console reports the
            // integration at boot. Without this the hook only attaches — and only logs — when
            // somebody first opens the dashboard. Failure here is not interesting: the hook
            // keeps retrying on its own, and the Performance tab explains the current state.
            later(() -> spark.attached(), SPARK_PROBE_DELAY_TICKS);
        } catch (Exception e) {
            C.warning("Failed to start web dashboard: " + e.getMessage());
            server = null;
            log = null;
            boundPort = -1;
        }
    }

    /**
     * The dashboard's live log, for modules that want their events on the site's log panel
     * (the shop does, for purchases). {@code null} whenever the dashboard is not serving —
     * callers must treat "no dashboard" as "nothing to log to", not as an error.
     */
    public WebLog log() {
        return log;
    }

    /**
     * Reads MODULES.web, back-filling from a legacy {@code web:} block at the config root.
     * Older configs kept the connection settings there and silently lost them on upgrade.
     */
    private ConfigurationSection resolveConfig() {
        ConfigurationSection cfg = config();
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("web");
        if (legacy == null || !hasConnectionSettings(legacy)) return cfg;
        if (cfg != null && hasConnectionSettings(cfg)) return cfg;

        C.warning("Web dashboard: 'host'/'port' trovati fuori da MODULES.web. "
                + "Sposta la sezione 'web' dentro MODULES per evitare ambiguita'. "
                + "Uso temporaneamente i valori dalla radice.");
        if (cfg == null) return legacy;

        for (String key : CONNECTION_KEYS) {
            if (!cfg.contains(key) && legacy.contains(key)) cfg.set(key, legacy.get(key));
        }
        return cfg;
    }

    private static boolean hasConnectionSettings(ConfigurationSection section) {
        return CONNECTION_KEYS.stream().anyMatch(section::contains);
    }

    public String boundHost() { return boundHost; }

    public int boundPort() { return boundPort; }

    public String generateTempToken() {
        return auth == null ? null : auth.generateTempToken();
    }

    private static int findAvailablePort(int start, int end) {
        for (int port = start; port <= end; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return socket.getLocalPort();
            } catch (IOException ignored) {
                // in use — try the next one
            }
        }
        return -1;
    }

    @Override
    protected void onShutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        auth = null;
        log = null;
        if (spark != null) {
            spark.detach();
            spark = null;
        }
        boundHost = null;
        boundPort = -1;
    }
}
