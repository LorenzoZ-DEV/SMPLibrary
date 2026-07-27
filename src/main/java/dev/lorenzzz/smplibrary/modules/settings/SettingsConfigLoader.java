package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class SettingsConfigLoader {

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
        config = parse(yaml);
    }

    public SettingsConfig reload() {
        load();
        return config;
    }

    public SettingsConfig get() {
        return config;
    }

    private SettingsConfig parse(YamlConfiguration yaml) {
        int size = yaml.getInt("size", 27);
        String name = yaml.getString("name", "&#8b00ff&lIMPOSTAZIONI");

        FillItemConfig fill = parseFillItem(yaml.getConfigurationSection("fillItem"));

        Map<String, SettingsItemConfig> items = parseItems(yaml.getConfigurationSection("items"));

        return new SettingsConfig(size, name, fill, items);
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

            java.util.List<String> commands = itemSec.getStringList("commands");
            boolean close = itemSec.getBoolean("close", true);

            map.put(key, new SettingsItemConfig(slot, item, commands, close));
        }
        return map;
    }

    private ItemConfig parseItemConfig(ConfigurationSection sec) {
        if (sec == null) return new ItemConfig("STONE", "", java.util.List.of(), false);
        return new ItemConfig(
                sec.getString("material", "STONE"),
                sec.getString("name", ""),
                sec.getStringList("lore"),
                sec.getBoolean("glow", false)
        );
    }
}
