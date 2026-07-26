package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/** Reads config.yml — the MODULES block the user entries live in. Hot-reloadable. */
public final class ConfigManager implements Manager {

    private final Smp_library plugin;
    private File file;
    private YamlConfiguration yaml;

    public ConfigManager(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "config"; }

    @Override
    public void enable() {
        file = new File(plugin.getDataFolder(), "config.yml");
        reload();
    }

    @Override public void disable() { yaml = null; }

    public void reload() {
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    public YamlConfiguration raw() { return yaml; }

    public boolean isEnabled(String moduleId) {
        return yaml.getBoolean("MODULES." + moduleId + ".enabled", false);
    }

    public ConfigurationSection module(String moduleId) {
        return yaml.getConfigurationSection("MODULES." + moduleId);
    }

    public ConfigurationSection modules() {
        return yaml.getConfigurationSection("MODULES");
    }
}