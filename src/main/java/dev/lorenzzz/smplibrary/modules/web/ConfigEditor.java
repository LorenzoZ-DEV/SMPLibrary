package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.gui.GuiModule;
import dev.lorenzzz.smplibrary.modules.shop.ShopModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class ConfigEditor {

    private static final Set<String> EDITABLE = Set.of("config", "messages", "shop", "gui");
    private static final Set<String> READONLY = Set.of("storage");

    private final Smp_library plugin;
    private final WebLog log;

    public ConfigEditor(Smp_library plugin, WebLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    public static boolean isEditable(String scope) { return EDITABLE.contains(scope); }
    public static boolean isReadOnly(String scope) { return READONLY.contains(scope); }
    public static Set<String> scopes() {
        var all = new java.util.LinkedHashSet<>(EDITABLE);
        all.addAll(READONLY);
        return Set.copyOf(all);
    }

    private Path pathFor(String scope) {
        return switch (scope) {
            case "config"   -> plugin.getDataFolder().toPath().resolve("config.yml");
            case "messages" -> plugin.getDataFolder().toPath().resolve("messages.yml");
            case "shop"     -> plugin.getDataFolder().toPath().resolve("inventories").resolve("shop.yml");
            case "gui"      -> plugin.getDataFolder().toPath().resolve("inventories").resolve("gui.yml");
            case "storage"  -> plugin.getDataFolder().toPath().resolve("storage.yml");
            default         -> null;
        };
    }

    public String read(String scope) throws IOException {
        Path p = pathFor(scope);
        if (p == null || !Files.exists(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public String apply(String scope, String content, String who) {
        if (!EDITABLE.contains(scope)) return "scope '" + scope + "' is read-only";
        Path p = pathFor(scope);
        if (p == null) return "unknown scope";
        Path bak = p.resolveSibling(p.getFileName() + ".bak");

        try {
            if (Files.exists(p)) Files.copy(p, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            C.warning("web: backup failed for " + scope + ": " + e);
        }

        try {
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "write failed: " + e.getMessage();
        }

        try {
            YamlConfiguration.loadConfiguration(p.toFile());
        } catch (Exception e) {
            try { if (Files.exists(bak)) Files.copy(bak, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) {}
            return "invalid YAML, reverted: " + e.getMessage();
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (scope) {
                case "config" -> {
                    // Snapshot the dashboard's own settings before the reload, so we can tell
                    // whether restarting it is actually necessary — see below.
                    String webBefore = webSettings();
                    plugin.reloadConfig();
                    if (plugin.getManagers() != null) {
                        plugin.getManagers().config().reload();
                        plugin.getManagers().messages().reload();
                        // Restarting the web module drops this connection and every session with
                        // it, so only do it when its own section changed (host, port, token…).
                        boolean webChanged = !webBefore.equals(webSettings());
                        if (webChanged) log.log("MODULES.web changed — restarting the dashboard");
                        plugin.getManagers().modules().reload(webChanged ? Set.of() : Set.of("web"));
                    }
                }
                case "messages" -> {
                    if (plugin.getManagers() != null) plugin.getManagers().messages().reload();
                }
                case "shop" -> {
                    if (plugin.getManagers() != null) {
                        // Typed lookup: a shop module that isn't running has no loader to reload.
                        ShopModule shop = plugin.getManagers().modules().module("shop", ShopModule.class);
                        if (shop != null && shop.getConfigLoader() != null) shop.getConfigLoader().reload();
                    }
                }
                case "gui" -> {
                    if (plugin.getManagers() != null) {
                        GuiModule gui = plugin.getManagers().modules().module("gui", GuiModule.class);
                        if (gui != null) gui.reloadConfig();
                    }
                }
            }
        });

        log.log("Edited " + scope + " by " + who);
        return null;
    }

    /**
     * Outcome of a toggle. {@code error} non-null means nothing changed; otherwise the two
     * flags are the module's state <em>after</em> the flip, so the caller can answer the HTTP
     * request with the new truth instead of leaving the browser to guess.
     */
    public record ToggleResult(String error, String id, boolean enabled, boolean running) {
        static ToggleResult failed(String message) { return new ToggleResult(message, null, false, false); }
    }

    /**
     * Flips {@code MODULES.<id>.enabled} and starts or stops that one module.
     *
     * <p>Must run on the server thread — enabling a module registers listeners and schedules
     * tasks. It used to hand the work to the scheduler and return immediately, which meant the
     * HTTP response went out before anything had actually changed.
     *
     * <p>It also used to call {@code modules().reload()}, restarting <em>every</em> module. That
     * included the web module: the dashboard tore down its own HTTP server mid-request, closing
     * the event stream and throwing away the session that had just asked for the toggle. The
     * browser then reconnected into a fresh {@link WebAuth} that had never heard of its cookie,
     * so the UI sat there frozen on the state from before the click. Touching only the module
     * being toggled is both the correct fix and much cheaper.
     */
    public ToggleResult toggleModule(String moduleId, String who) {
        if (moduleId == null || moduleId.isBlank()) return ToggleResult.failed("invalid module id");
        // Toggling web off from the web dashboard would kill the connection mid-request.
        if ("web".equals(moduleId)) return ToggleResult.failed("cannot toggle the web module from the dashboard");
        if (plugin.getManagers() == null || plugin.getManagers().modules().module(moduleId) == null) {
            return ToggleResult.failed("unknown module '" + moduleId + "'");
        }

        // Re-read first: the editor writes config.yml straight to disk, so the copy behind
        // plugin.getConfig() can be stale, and saveConfig() would write those stale values back.
        plugin.reloadConfig();

        String path = "MODULES." + moduleId + ".enabled";
        boolean newState = !plugin.getConfig().getBoolean(path, false);

        plugin.getConfig().set(path, newState);
        plugin.saveConfig();
        // ConfigManager keeps its own copy loaded from the file — that is the one ModuleManager
        // and /api/stats read, so it has to be re-read too or the dashboard reports the old flag.
        plugin.getManagers().config().reload();

        String error = plugin.getManagers().modules().setEnabled(moduleId, newState);
        boolean running = plugin.getManagers().modules().isRunning(moduleId);

        log.log("Toggled module " + moduleId + " " + (newState ? "ON" : "OFF") + " by " + who
                + (error == null ? "" : " (" + error + ")"));

        return new ToggleResult(error, moduleId, newState, running);
    }

    /**
     * The dashboard settings that decide whether it must be rebound, as a comparable string.
     *
     * <p>Only these four matter: everything else under {@code MODULES.web} is read per request.
     * Comparing the {@code ConfigurationSection}s themselves does not work — two separately
     * loaded sections are never equal, so every save would have looked like a change.
     */
    private String webSettings() {
        return String.valueOf(plugin.getConfig().get("MODULES.web.enabled"))
                + '|' + plugin.getConfig().get("MODULES.web.host")
                + '|' + plugin.getConfig().get("MODULES.web.port")
                + '|' + plugin.getConfig().get("MODULES.web.token");
    }

    public Map<String, Boolean> scopeFlags() {
        var flags = new java.util.LinkedHashMap<String, Boolean>();
        for (String s : scopes()) flags.put(s, READONLY.contains(s));
        return flags;
    }
}
