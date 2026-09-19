package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.combat.CombatModule;
import dev.lorenzzz.smplibrary.modules.adaptive.AdaptiveToolModule;
import dev.lorenzzz.smplibrary.modules.amethyst.AmethystToolModule;
import dev.lorenzzz.smplibrary.modules.antipearl.AntiPearlGlitchModule;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import dev.lorenzzz.smplibrary.modules.competitors.CompetitorsModule;
import dev.lorenzzz.smplibrary.modules.debug.DebugModule;
import dev.lorenzzz.smplibrary.modules.dupewatch.DupeWatchModule;
import dev.lorenzzz.smplibrary.modules.firstjoin.FirstJoinModule;
import dev.lorenzzz.smplibrary.modules.ggwave.GgWaveModule;
import dev.lorenzzz.smplibrary.modules.gui.GuiModule;
import dev.lorenzzz.smplibrary.modules.info.InfoModule;
import dev.lorenzzz.smplibrary.modules.keyall.KeyAllModule;
import dev.lorenzzz.smplibrary.modules.lootguard.LootGuardModule;
import dev.lorenzzz.smplibrary.modules.nightvision.NightVisionModule;
import dev.lorenzzz.smplibrary.modules.pay.PayModule;
import dev.lorenzzz.smplibrary.modules.playerstats.PlayerStatsModule;
import dev.lorenzzz.smplibrary.modules.playtime.PlaytimeModule;
import dev.lorenzzz.smplibrary.modules.settings.SettingsModule;
import dev.lorenzzz.smplibrary.modules.shop.ShopModule;
import dev.lorenzzz.smplibrary.modules.spawn.SpawnModule;
import dev.lorenzzz.smplibrary.modules.streak.StreakModule;
import dev.lorenzzz.smplibrary.modules.tpauto.TpAutoModule;
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
        register(new AntiPearlGlitchModule(plugin));
        register(new LootGuardModule(plugin));
        register(new FirstJoinModule(plugin));
        register(new ShopModule(plugin));
        register(new PayModule(plugin));
        register(new PlaytimeModule(plugin));
        register(new PlayerStatsModule(plugin));
        register(new CompetitorsModule(plugin));
        register(new SettingsModule(plugin));
        register(new GuiModule(plugin));
        register(new TpAutoModule(plugin));
        register(new DebugModule(plugin));
        register(new DupeWatchModule(plugin));
        register(new SpawnModule(plugin));
        register(new CombatModule(plugin));
        register(new NightVisionModule(plugin));
        register(new StreakModule(plugin));
        register(new InfoModule(plugin));
        register(new GgWaveModule(plugin));
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
        reload(Set.of());
    }

    /**
     * Same as {@link #reload()}, but leaves the named modules exactly as they are.
     *
     * <p>The web dashboard needs this: it is itself a module, and a blanket reload would stop
     * and restart the HTTP server that is still serving the request asking for the reload —
     * dropping the event stream and invalidating the caller's session mid-flight.
     */
    public void reload(Set<String> skip) {
        for (String id : List.copyOf(running)) {
            if (!skip.contains(id)) stopModule(id);
        }
        startEnabledModules(skip);
    }

    /**
     * Starts or stops a single module in place, without disturbing its neighbours.
     * Returns {@code null} on success, otherwise a message fit to show the caller.
     */
    public String setEnabled(String id, boolean enabled) {
        if (!modules.containsKey(id)) return "unknown module '" + id + "'";
        if (!enabled) {
            stopModule(id);
            return null;
        }
        if (running.contains(id)) return null;
        return startModule(id) ? null : "module '" + id + "' threw while starting — check the console";
    }

    private void startEnabledModules() {
        startEnabledModules(Set.of());
    }

    private void startEnabledModules(Set<String> skip) {
        ConfigurationSection section = config.modules();
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            if (skip.contains(id) || !config.isEnabled(id)) continue;
            startModule(id);
        }
    }

    /** Boots one module. Returns false when it is unknown or threw on the way up. */
    private boolean startModule(String id) {
        Module module = modules.get(id);
        if (module == null) return false;
        if (running.contains(id)) return true;
        try {
            module.bind(managers);
            module.onEnable();
            running.add(id);
            C.info("Module '" + id + "' enabled.");
            return true;
        } catch (Exception e) {
            C.warning("Error loading module '" + id + "': " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "Module '" + id + "' failed to start", e);
            return false;
        }
    }

    /** Stops one module, but only if it actually started — onDisable must never run unpaired. */
    private void stopModule(String id) {
        Module module = modules.get(id);
        if (module == null || !running.remove(id)) return;
        try {
            module.onDisable();
            C.info("Module '" + id + "' disabled.");
        } catch (Exception e) {
            C.warning("Error disabling module '" + id + "': " + e.getMessage());
        }
    }

    /** Disables only the modules that actually started, so onDisable never runs unpaired. */
    private void stopRunningModules() {
        for (String id : List.copyOf(running)) stopModule(id);
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
