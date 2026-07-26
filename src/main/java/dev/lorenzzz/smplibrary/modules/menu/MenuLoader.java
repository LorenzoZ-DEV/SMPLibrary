package dev.lorenzzz.smplibrary.modules.menu;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.*;

public class MenuLoader {

    private final Smp_library plugin;
    private final Map<String, MenuConfig> menus = new LinkedHashMap<>();

    public MenuLoader(Smp_library plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        File dir = new File(plugin.getDataFolder(), "inventories");
        if (!dir.exists()) {
            saveDefaultInventories(dir);
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                MenuConfig config = loadFile(file);
                if (config != null && config.enabled()) {
                    menus.put(config.name(), config);
                }
            } catch (Exception e) {
                C.warning("Failed to load inventory: " + file.getName() + " - " + e.getMessage());
            }
        }
        C.info("Loaded " + menus.size() + " inventory menus.");
    }

    private void saveDefaultInventories(File dir) {
        dir.mkdirs();

        try {
            // Save all inventory files from the jar
            for (String name : List.of(
                    "pistacchi-main.yml", "pistacchi-crates.yml", "pistacchi-spawners.yml",
                    "pistacchi-tools.yml", "pistacchi-upgrades.yml", "settings_inventory.yml",
                    "live_inventory.yml", "confirm-adaptive-tool.yml", "confirm-amethyst-pickaxe.yml",
                    "confirm-amethyst-sword.yml", "confirm-blaze-spawner.yml", "confirm-common-crate.yml",
                    "confirm-creeper-spawner.yml", "confirm-devil-crate.yml", "confirm-enderman-spawner.yml",
                    "confirm-home-upgrade.yml", "confirm-iron-golem-spawner.yml", "confirm-skeleton-spawner.yml",
                    "confirm-spawners-crate.yml", "confirm-spider-spawner.yml", "confirm-trivella.yml",
                    "confirm-venrix-crate.yml", "confirm-vote-crate.yml", "confirm-zombie-spawner.yml"
            )) {
                File out = new File(dir, name);
                if (!out.exists()) {
                    try (InputStream in = plugin.getResource("inventories/" + name)) {
                        if (in != null) Files.copy(in, out.toPath());
                    }
                }
            }
        } catch (IOException e) {
            C.warning("Could not save default inventories: " + e.getMessage());
        }
    }

    private MenuConfig loadFile(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        String name = yaml.getString("name", file.getName().replace(".yml", ""));
        String title = yaml.getString("title", "");
        int size = yaml.getInt("size", 27);
        boolean enabled = yaml.getBoolean("enable", true);

        if (!enabled) return null;

        MenuItemConfig fillItem = null;
        ConfigurationSection fillSection = yaml.getConfigurationSection("fillItem");
        if (fillSection != null) {
            fillItem = new MenuItemConfig(
                    "fill", -1, fillSection.getValues(true), null, null, null, null, null
            );
        }

        List<MenuItemConfig> items = new ArrayList<>();
        ConfigurationSection itemsSection = yaml.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection sec = itemsSection.getConfigurationSection(key);
                if (sec == null) continue;

                int slot = sec.getInt("slot", -1);
                Map<String, Object> itemMap = new LinkedHashMap<>();
                ConfigurationSection itemSec = sec.getConfigurationSection("item");
                if (itemSec != null) {
                    itemMap.putAll(itemSec.getValues(true));
                }

                List<Map<String, String>> actions = new ArrayList<>();
                List<?> rawActions = sec.getList("actions");
                if (rawActions != null) {
                    for (Object o : rawActions) {
                        if (o instanceof Map<?, ?> m) {
                            Map<String, String> action = new LinkedHashMap<>();
                            m.forEach((k, v) -> action.put(k.toString(), v == null ? "" : v.toString()));
                            actions.add(action);
                        }
                    }
                }

                List<String> commands = sec.getStringList("commands");
                if (commands.isEmpty() && sec.contains("commands")) commands = List.of(sec.getString("commands", ""));

                List<String> consoleCommands = sec.getStringList("consoleCommands");

                Boolean close = sec.contains("close") ? sec.getBoolean("close") : null;

                ClickRequirement clickReq = null;
                ConfigurationSection cr = sec.getConfigurationSection("click-requirement");
                if (cr != null) {
                    clickReq = new ClickRequirement(cr.getValues(true));
                }

                items.add(new MenuItemConfig(key, slot, itemMap, actions, commands, consoleCommands, close, clickReq));
            }
        }

        return new MenuConfig(name, title, size, true, fillItem, items);
    }

    public MenuConfig getMenu(String name) {
        return menus.get(name);
    }

    public Collection<MenuConfig> allMenus() {
        return menus.values();
    }
}
