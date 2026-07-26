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
        register(new dev.lorenzzz.smplibrary.modules.menu.MenuModule(plugin));

        ConfigurationSection modules = config.modules();
        if (modules == null) return;
        for (String id : modules.getKeys(false)) {
            Module m = this.modules.get(id);
            if (m == null) continue;
            if (!config.isEnabled(id)) { continue; }
            try {
                m.storage(storage).commands(commands).messages(messages).onEnable();
                C.info("Module '" + id + "' enabled.");
            } catch (Exception e) {
                C.warning("Error loading module '" + id + "'!");
            }
        }
    }

    /** Called by AuthService/server boot order; the marker id reads config. */
    public ModuleManager register(Module module) {
        modules.put(module.id(), module);
        return this;
    }

    public boolean isEnabled(String id) { return config.isEnabled(id); }
    public Collection<Module> active() { return modules.values(); }

    /** Typed lookup by module id — for command classes that need a specific module instance. */
    public Module module(String id) { return modules.get(id); }

    @Override
    public void disable() {
        for (Module m : modules.values()) m.onDisable();
        modules.clear();
    }
}