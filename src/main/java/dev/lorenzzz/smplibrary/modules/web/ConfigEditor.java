package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
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

    private static final Set<String> EDITABLE = Set.of("config", "messages", "shop");
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
                    plugin.reloadConfig();
                    if (plugin.getManagers() != null) {
                        plugin.getManagers().config().reload();
                        plugin.getManagers().messages().reload();
                        plugin.getManagers().modules().reload();
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
            }
        });

        log.log("Edited " + scope + " by " + who);
        return null;
    }

    public String toggleModule(String moduleId, String who) {
        if (moduleId == null || moduleId.isBlank()) return "invalid module id";
        // Toggling web off from the web dashboard would kill the connection mid-request.
        if ("web".equals(moduleId)) return "cannot toggle the web module from the dashboard";
        if (plugin.getManagers() == null || plugin.getManagers().modules().module(moduleId) == null) {
            return "unknown module '" + moduleId + "'";
        }

        boolean current = plugin.getConfig().getBoolean("MODULES." + moduleId + ".enabled", false);
        boolean newState = !current;

        plugin.getConfig().set("MODULES." + moduleId + ".enabled", newState);
        plugin.saveConfig();

        String message = "Toggled module " + moduleId + " " + (newState ? "ON" : "OFF") + " by " + who;

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (plugin.getManagers() != null) {
                    plugin.reloadConfig();
                    plugin.getManagers().config().reload();
                    plugin.getManagers().modules().reload();
                }
            } finally {
                log.log(message);
            }
        });

        return null;
    }

    public Map<String, Boolean> scopeFlags() {
        var flags = new java.util.LinkedHashMap<String, Boolean>();
        for (String s : scopes()) flags.put(s, READONLY.contains(s));
        return flags;
    }
}
