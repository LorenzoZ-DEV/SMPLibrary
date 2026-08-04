package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.Module;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Does everything" for features: keeps module id -> Module, reflects enable flags,
 * boots only modules {@code enabled: true} under the MODULES block in config.yml.
 * Tracks created/controlled by ModuleManager.
 */
public final class ModuleManager implements Manager {

    private final Smp_library plugin;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final java.util.Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private ConfigManager config;
    private StorageManager storage;
    private CommandManager commands;
    private MessagesManager messages;

    public ModuleManager(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "modules"; }

    @Override
    public void enable() {
        ManagerService s = plugin.getManagers();
        config = s.config();
        storage = s.storage();
        commands = s.commands();
        messages = s.messages();

        // Register concrete modules here as they land.
        register(new dev.lorenzzz.smplibrary.modules.keyall.KeyAllModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.amethyst.AmethystToolModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.adaptive.AdaptiveToolModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.trivella.TrivellaModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.firstjoin.FirstJoinModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.shop.ShopModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.pay.PayModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.playtime.PlaytimeModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.settings.SettingsModule(plugin));
        register(new dev.lorenzzz.smplibrary.modules.web.WebModule(plugin));

        ensureConfig();

        ConfigurationSection modules = config.modules();
        if (modules == null) return;
        for (String id : modules.getKeys(false)) {
            Module m = this.modules.get(id);
            if (m == null) continue;
            if (!config.isEnabled(id)) { continue; }
            try {
                m.storage(storage).commands(commands).messages(messages).onEnable();
                running.add(id);
                C.info("Module '" + id + "' enabled.");
            } catch (Exception e) {
                C.warning("Error loading module '" + id + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void ensureConfig() {
        org.bukkit.configuration.file.YamlConfiguration defaults = null;
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in));
            }
        } catch (Exception ignored) {}

        boolean changed = false;
        for (Module m : modules.values()) {
            String id = m.id();
            if (config.raw() != null && config.raw().contains("MODULES." + id)) continue;

            if (defaults != null && defaults.contains("MODULES." + id)) {
                org.bukkit.configuration.ConfigurationSection sec = defaults.getConfigurationSection("MODULES." + id);
                if (sec != null) {
                    plugin.getConfig().set("MODULES." + id, sec.getValues(true));
                    changed = true;
                    C.info("Auto-added config section for module '" + id + "'");
                    continue;
                }
            }

            plugin.getConfig().set("MODULES." + id + ".enabled", true);
            changed = true;
            C.info("Auto-added enabled flag for module '" + id + "'");
        }

        if (changed) {
            plugin.saveConfig();
            config.reload();
        }
    }

    /** Called by AuthService/server boot order; the marker id reads config. */
    public ModuleManager register(Module module) {
        modules.put(module.id(), module);
        return this;
    }

    public boolean isEnabled(String id) { return config.isEnabled(id); }
    public boolean isRunning(String id) { return running.contains(id); }
    public Collection<Module> active() { return modules.values(); }

    public void reload() {
        for (Module m : modules.values()) {
            try { m.onDisable(); } catch (Exception e) {
                C.warning("Error disabling module '" + m.id() + "': " + e.getMessage());
            }
        }
        running.clear();
        ConfigurationSection sec = config.modules();
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            Module m = this.modules.get(id);
            if (m == null) continue;
            if (!config.isEnabled(id)) continue;
            try {
                m.storage(storage).commands(commands).messages(messages).onEnable();
                running.add(id);
            } catch (Exception e) {
                C.warning("Error reloading module '" + id + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /** Typed lookup by module id — for command classes that need a specific module instance. */
    public Module module(String id) { return modules.get(id); }

    @Override
    public void disable() {
        for (Module m : modules.values()) m.onDisable();
        modules.clear();
        running.clear();
    }
}