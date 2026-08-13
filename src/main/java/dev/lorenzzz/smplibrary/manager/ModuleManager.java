package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.adaptive.AdaptiveToolModule;
import dev.lorenzzz.smplibrary.modules.amethyst.AmethystToolModule;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import dev.lorenzzz.smplibrary.modules.firstjoin.FirstJoinModule;
import dev.lorenzzz.smplibrary.modules.keyall.KeyAllModule;
import dev.lorenzzz.smplibrary.modules.pay.PayModule;
import dev.lorenzzz.smplibrary.modules.playerstats.PlayerStatsModule;
import dev.lorenzzz.smplibrary.modules.playtime.PlaytimeModule;
import dev.lorenzzz.smplibrary.modules.settings.SettingsModule;
import dev.lorenzzz.smplibrary.modules.shop.ShopModule;
import dev.lorenzzz.smplibrary.modules.trivella.TrivellaModule;
import dev.lorenzzz.smplibrary.modules.web.WebModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Registry + lifecycle for features: keeps module id -> Module and boots only the ones
 * flagged {@code enabled: true} under the MODULES block in config.yml. Missing sections
 * are auto-written from the bundled config.yml (or from the module's own defaults).
 */
public final class ModuleManager implements Manager {

    private static final String ROOT = "MODULES";

    private final Smp_library plugin;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    private ManagerService managers;
    private ConfigManager config;

    public ModuleManager(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "modules"; }

    @Override
    public void enable() {
        managers = plugin.getManagers();
        config = managers.config();

        // Register concrete modules here as they land.
        register(new KeyAllModule(plugin));
        register(new ChatGamesModule(plugin));
        register(new AmethystToolModule(plugin));
        register(new AdaptiveToolModule(plugin));
        register(new TrivellaModule(plugin));
        register(new FirstJoinModule(plugin));
        register(new ShopModule(plugin));
        register(new PayModule(plugin));
        register(new PlaytimeModule(plugin));
        register(new PlayerStatsModule(plugin));
        register(new SettingsModule(plugin));
        register(new WebModule(plugin));

        ensureConfig();
        startEnabledModules();
    }

    /** Registers a module. Must happen before {@link #enable()} boots them. */
    public ModuleManager register(Module module) {
        modules.put(module.id(), module);
        return this;
    }

    /** Stops everything that is running, then boots the modules currently flagged enabled. */
    public void reload() {
        stopRunningModules();
        startEnabledModules();
    }

    private void startEnabledModules() {
        ConfigurationSection section = config.modules();
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            Module module = modules.get(id);
            if (module == null || !config.isEnabled(id)) continue;
            try {
                module.bind(managers);
                module.onEnable();
                running.add(id);
                C.info("Module '" + id + "' enabled.");
            } catch (Exception e) {
                C.warning("Error loading module '" + id + "': " + e.getMessage());
                plugin.getLogger().log(Level.WARNING, "Module '" + id + "' failed to start", e);
            }
        }
    }

    /** Disables only the modules that actually started, so onDisable never runs unpaired. */
    private void stopRunningModules() {
        for (String id : List.copyOf(running)) {
            Module module = modules.get(id);
            if (module == null) continue;
            try {
                module.onDisable();
            } catch (Exception e) {
                C.warning("Error disabling module '" + id + "': " + e.getMessage());
            }
        }
        running.clear();
    }

    /**
     * Writes a MODULES section for any module the config doesn't know yet — copied from the
     * bundled config.yml when it has one, otherwise from the module's own {@link Module#defaults()}.
     */
    private void ensureConfig() {
        YamlConfiguration bundled = bundledConfig();
        boolean changed = false;

        for (Module module : modules.values()) {
            String path = ROOT + "." + module.id();
            if (config.raw() != null && config.raw().contains(path)) continue;

            ConfigurationSection bundledSection = bundled == null ? null : bundled.getConfigurationSection(path);
            if (bundledSection != null) {
                plugin.getConfig().set(path, bundledSection.getValues(true));
                C.info("Auto-added config section for module '" + module.id() + "'");
            } else {
                for (Map.Entry<String, Object> entry : module.defaults().entrySet()) {
                    plugin.getConfig().set(path + "." + entry.getKey(), entry.getValue());
                }
                C.info("Auto-added default config for module '" + module.id() + "'");
            }
            changed = true;
        }

        if (changed) {
            plugin.saveConfig();
            config.reload();
        }
    }

    private YamlConfiguration bundledConfig() {
        try (InputStream in = plugin.getResource("config.yml")) {
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isEnabled(String id) { return config != null && config.isEnabled(id); }
    public boolean isRunning(String id) { return running.contains(id); }
    public Collection<Module> active() { return modules.values(); }

    /** Lookup by module id — for command classes that need a specific module instance. */
    public Module module(String id) { return modules.get(id); }

    /** Lookup by id and type in one step; {@code null} when absent, not running or of another type. */
    public <T extends Module> T module(String id, Class<T> type) {
        Module module = modules.get(id);
        return type.isInstance(module) && running.contains(id) ? type.cast(module) : null;
    }

    @Override
    public void disable() {
        stopRunningModules();
        modules.clear();
    }
}
