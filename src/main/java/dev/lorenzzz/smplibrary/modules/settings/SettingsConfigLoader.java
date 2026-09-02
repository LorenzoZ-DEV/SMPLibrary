package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SettingsConfigLoader {

    /** Bukkit inventories are 1..6 rows; anything bigger in the file is a slot count. */
    private static final int MAX_ROWS = 6;

    private final Smp_library plugin;
    private SettingsConfig config;

    public SettingsConfigLoader(Smp_library plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File dir = new File(plugin.getDataFolder(), "inventories");
        dir.mkdirs();

        File file = new File(dir, "settings.yml");
        if (!file.exists()) {
            try (InputStream in = plugin.getResource("inventories/settings.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                    C.info("Saved default settings.yml");
                } else {
                    C.warning("settings.yml not found in jar!");
                    return;
                }
            } catch (IOException e) {
                C.warning("Could not save settings.yml: " + e.getMessage());
                return;
            }
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        mergeDefaults(yaml, file);
        config = parse(yaml);
    }

    /**
     * Adds the keys the bundled file has and the server's copy doesn't, then writes it back.
     *
     * <p>Without this a server that already had a settings.yml would never see a new entry —
     * TPAuto would exist in the plugin and be missing from the menu — and, worse, would miss
     * the flags added to entries it already has: {@code ah_options} without its {@code toggle:
     * false} turns from a button into a switch. Values already in the file are never touched,
     * so edited names, slots and lore survive.
     */
    private void mergeDefaults(YamlConfiguration yaml, File file) {
        try (InputStream in = plugin.getResource("inventories/settings.yml")) {
            if (in == null) return;

            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in));
            int added = 0;
            for (String key : bundled.getKeys(true)) {
                // Sections are created implicitly by their leaves; setting one wholesale would
                // store a live section object instead of plain values.
                if (bundled.isConfigurationSection(key) || yaml.contains(key)) continue;
                yaml.set(key, bundled.get(key));
                added++;
            }

            if (added == 0) return;
            yaml.save(file);
            C.info("settings.yml aggiornato: " + added + " chiavi nuove aggiunte dai default.");
        } catch (IOException e) {
            C.warning("Could not merge the bundled settings.yml: " + e.getMessage());
        }
    }

    public SettingsConfig reload() {
        load();
        return config;
    }

    public SettingsConfig get() {
        return config;
    }

    private SettingsConfig parse(YamlConfiguration yaml) {
        String name = yaml.getString("name", "&#8b00ff&lIMPOSTAZIONI");

        FillItemConfig fill = parseFillItem(yaml.getConfigurationSection("fillItem"));

        Map<String, SettingsItemConfig> items = parseItems(yaml.getConfigurationSection("items"));

        return new SettingsConfig(
                rows(yaml.getInt("size", 27)),
                name,
                yaml.getString("status.enabled", "&a&lATTIVO"),
                yaml.getString("status.disabled", "&c&lDISATTIVO"),
                fill,
                items);
    }

    /** {@code size} is written as a slot count (27) in the shipped file, but rows are accepted too. */
    private int rows(int size) {
        int rows = size > MAX_ROWS ? Math.ceilDiv(size, 9) : size;
        return Math.max(1, Math.min(MAX_ROWS, rows));
    }

    private FillItemConfig parseFillItem(ConfigurationSection sec) {
        if (sec == null) return new FillItemConfig("GRAY_STAINED_GLASS_PANE", " ");
        return new FillItemConfig(
                sec.getString("material", "GRAY_STAINED_GLASS_PANE"),
                sec.getString("name", " ")
        );
    }

    private Map<String, SettingsItemConfig> parseItems(ConfigurationSection sec) {
        Map<String, SettingsItemConfig> map = new LinkedHashMap<>();
        if (sec == null) return map;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection itemSec = sec.getConfigurationSection(key);
            if (itemSec == null) continue;

            int slot = itemSec.getInt("slot", -1);
            if (slot < 0) continue;

            ConfigurationSection itemCfg = itemSec.getConfigurationSection("item");
            ItemConfig item = parseItemConfig(itemCfg);

            List<String> commands = itemSec.getStringList("commands");
            boolean close = itemSec.getBoolean("close", true);
            boolean toggle = itemSec.getBoolean("toggle", true);
            boolean defaultState = itemSec.getBoolean("default", true);
            String requiresModule = itemSec.getString("requires-module");

            map.put(key, new SettingsItemConfig(slot, item, commands, close, toggle, defaultState,
                    requiresModule == null || requiresModule.isBlank() ? null : requiresModule));
        }
        return map;
    }

    private ItemConfig parseItemConfig(ConfigurationSection sec) {
        if (sec == null) return new ItemConfig("STONE", "", List.of(), false);
        return new ItemConfig(
                sec.getString("material", "STONE"),
                sec.getString("name", ""),
                sec.getStringList("lore"),
                sec.getBoolean("glow", false)
        );
    }
}
