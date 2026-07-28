package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.net.ServerSocket;

public final class WebModule implements Module {

    private final Smp_library plugin;
    private WebServer server;
    private WebAuth auth;
    private String boundHost;
    private int boundPort = -1;

    public WebModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "web"; }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("MODULES.web");
        // Fallback: sezione "web" alla radice del config (per compat con config legacy)
        if (cfg == null || (!cfg.contains("host") && !cfg.contains("port") && !cfg.contains("token"))) {
            ConfigurationSection rootCfg = plugin.getConfig().getConfigurationSection("web");
            if (rootCfg != null && (rootCfg.contains("host") || rootCfg.contains("port") || rootCfg.contains("token"))) {
                C.warning("Web dashboard: 'host'/'port' troori fuori da MODULES.web. "
                        + "Sposta la sezione 'web' dentro MODULES per evitare ambiguita'. "
                        + "Uso temporaneamente i valori dalla radice.");
                if (cfg == null) {
                    cfg = rootCfg;
                } else {
                    // merga solo i campi mancanti
                    org.bukkit.configuration.ConfigurationSection merged = cfg;
                    if (!merged.contains("host") && rootCfg.contains("host")) merged.set("host", rootCfg.get("host"));
                    if (!merged.contains("port") && rootCfg.contains("port")) merged.set("port", rootCfg.get("port"));
                    if (!merged.contains("token") && rootCfg.contains("token")) merged.set("token", rootCfg.get("token"));
                }
            }
        }
        if (cfg == null) {
            C.info("Web dashboard: no config section found, skipping.");
            return;
        }

        String host = cfg.getString("host", "0.0.0.0");
        int preferred = cfg.getInt("port", 8080);
        String token = cfg.getString("token", null);
        int actualPort = findAvailablePort(preferred, preferred + 20);

        if (actualPort == -1) {
            C.warning("Web dashboard: no available port in range " + preferred + "-" + (preferred + 20));
            return;
        }

        auth = new WebAuth(token);
        WebLog log = new WebLog();
        WebStats stats = new WebStats(plugin, System.currentTimeMillis());
        ConfigEditor editor = new ConfigEditor(plugin, log);
        server = new WebServer(plugin, auth, stats, editor, log);

        try {
            server.start(host, actualPort);
            this.boundHost = host;
            this.boundPort = actualPort;
            C.info("Web dashboard started on " + host + ":" + actualPort);
            log.log("Web dashboard started on " + host + ":" + actualPort);
        } catch (Exception e) {
            C.warning("Failed to start web dashboard: " + e.getMessage());
            server = null;
            boundPort = -1;
        }
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop();
            server = null;
        }
        boundPort = -1;
    }

    public String boundHost() { return boundHost; }
    public int boundPort() { return boundPort; }

    public String generateTempToken() {
        return auth != null ? auth.generateTempToken() : null;
    }

    private static int findAvailablePort(int start, int end) {
        for (int port = start; port <= end; port++) {
            try (ServerSocket ss = new ServerSocket(port)) {
                return port;
            } catch (IOException ignored) {}
        }
        return -1;
    }

    @Override
    public Module storage(StorageManager storage) { return this; }
    @Override
    public Module commands(CommandManager commands) { return this; }
    @Override
    public Module messages(MessagesManager messages) { return this; }
}
