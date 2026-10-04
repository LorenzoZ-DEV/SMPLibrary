package dev.lorenzzz.smplibrary.modules.shop;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

public class ShopConfigLoader {

    private final Smp_library plugin;
    private ShopConfig config;

    public ShopConfigLoader(Smp_library plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File dir = new File(plugin.getDataFolder(), "inventories");
        dir.mkdirs();

        File file = new File(dir, "shop.yml");
        if (!file.exists()) {
            try (InputStream in = plugin.getResource("inventories/shop.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                    Log.info("Saved default shop.yml");
                } else {
                    Log.warning("shop.yml not found in jar!");
                    return;
                }
            } catch (IOException e) {
                Log.warning("Could not save shop.yml: " + e.getMessage());
                return;
            }
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        config = parse(yaml);
        Log.info("Shop config loaded with " + config.categories().size() + " categories.");
    }

    public ShopConfig reload() {
        load();
        return config;
    }

    public ShopConfig get() {
        return config;
    }

    private ShopConfig parse(YamlConfiguration yaml) {
        MainInvConfig mainInv = parseMainInventory(yaml.getConfigurationSection("main-inventory"));

        StackItemConfig addStack = parseStackItem(yaml.getConfigurationSection("add-stack-item"));
        StackItemConfig removeStack = parseStackItem(yaml.getConfigurationSection("remove-stack-item"));
        StackItemConfig setToMin = parseStackItem(yaml.getConfigurationSection("set-to-min-item"));
        StackItemConfig setToMax = parseStackItem(yaml.getConfigurationSection("set-to-max-item"));

        Map<String, CategoryConfig> categories = parseCategories(yaml.getConfigurationSection("categories"));

        boolean keepOpen = yaml.getBoolean("keep-open-after-purchase", true);
        boolean antiDupe = yaml.getBoolean("anti-dupe", true);
        boolean debug = yaml.getBoolean("debug", false);

        PrintConfig printConfig = new PrintConfig(yaml.getBoolean("print-console.enabled", false));

        Map<String, TemplateItem> items = parseItems(yaml.getConfigurationSection("items"));

        MessagesConfig messages = parseMessages(yaml);

        SoundConfig openShop = parseSound(yaml.getConfigurationSection("open-shop"));
        SoundConfig openCat = parseSound(yaml.getConfigurationSection("open-category"));
        SoundConfig navPage = parseSound(yaml.getConfigurationSection("navigate-page"));
        SoundConfig selectItem = parseSound(yaml.getConfigurationSection("select-item"));
        SoundConfig confirmBuy = parseSound(yaml.getConfigurationSection("confirm-purchase"));
        SoundConfig cancelBuy = parseSound(yaml.getConfigurationSection("cancel-purchase"));
        SoundConfig purchaseErr = parseSound(yaml.getConfigurationSection("purchase-error"));
        SoundConfig backBtn = parseSound(yaml.getConfigurationSection("back-button"));

        return new ShopConfig(
                mainInv, addStack, removeStack, setToMin, setToMax,
                categories, keepOpen, printConfig, antiDupe, debug, items,
                messages, openShop, openCat, navPage, selectItem,
                confirmBuy, cancelBuy, purchaseErr, backBtn
        );
    }

    private MainInvConfig parseMainInventory(ConfigurationSection sec) {
        if (sec == null) return new MainInvConfig("Shop", List.of("XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX"));
        return new MainInvConfig(
                sec.getString("title", "Shop"),
                sec.getStringList("layout")
        );
    }

    private StackItemConfig parseStackItem(ConfigurationSection sec) {
        if (sec == null) return new StackItemConfig("<green><amount>", "LIME_STAINED_GLASS_PANE");
        return new StackItemConfig(
                sec.getString("display-name", "<green><amount>"),
                sec.getString("type", "LIME_STAINED_GLASS_PANE")
        );
    }

    private Map<String, CategoryConfig> parseCategories(ConfigurationSection sec) {
        Map<String, CategoryConfig> map = new LinkedHashMap<>();
        if (sec == null) return map;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection catSec = sec.getConfigurationSection(key);
            if (catSec == null) continue;

            boolean enabled = catSec.getBoolean("enabled", true);
            if (!enabled) continue;

            int slot = catSec.getInt("slot", -1);
            int rows = catSec.getInt("rows", 4);

            IconConfig icon = parseIcon(catSec.getConfigurationSection("icon"));
            String invName = catSec.getString("inventory-name", "Shop");
            List<String> layout = catSec.getStringList("layout");

            Map<String, ProductConfig> products = parseProducts(catSec.getConfigurationSection("products"));

            map.put(key, new CategoryConfig(true, slot, rows, icon, invName, layout, products));
        }
        return map;
    }

    private IconConfig parseIcon(ConfigurationSection sec) {
        if (sec == null) return new IconConfig("", "STONE", List.of());
        return new IconConfig(
                sec.getString("display-name", ""),
                sec.getString("type", "STONE"),
                sec.getStringList("lore")
        );
    }

    private Map<String, ProductConfig> parseProducts(ConfigurationSection sec) {
        Map<String, ProductConfig> map = new LinkedHashMap<>();
        if (sec == null) return map;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection prodSec = sec.getConfigurationSection(key);
            if (prodSec == null) continue;

            String material = prodSec.getString("material", "STONE");
            int price = prodSec.getInt("price", 0);
            List<String> commands = prodSec.getStringList("commands");
            List<String> lore = prodSec.getStringList("lore");
            String displayName = prodSec.getString("display-name", "");

            map.put(key, new ProductConfig(material, price, commands, lore, displayName));
        }
        return map;
    }

    private Map<String, TemplateItem> parseItems(ConfigurationSection sec) {
        Map<String, TemplateItem> map = new LinkedHashMap<>();
        if (sec == null) return map;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection itemSec = sec.getConfigurationSection(key);
            if (itemSec == null) continue;

            String material = itemSec.getString("material", itemSec.getString("type", "STONE"));
            String name = itemSec.getString("name", itemSec.getString("display-name", ""));
            List<String> lore = itemSec.getStringList("lore");

            map.put(key, new TemplateItem(material, name, lore));
        }
        return map;
    }

    private MessagesConfig parseMessages(YamlConfiguration yaml) {
        return new MessagesConfig(
                yaml.getString("insufficient-funds", "&#F3F622&lSHOP &r&8➟ &cNon hai abbastanza soldi."),
                yaml.getString("inventory-full", "&#F3F622&lSHOP &r&8➟ &fNon hai abbastanza spazio."),
                yaml.getString("purchase-success", "&#F3F622&lSHOP &r&8➟ &aHai acquistato x<amount> <item>"),
                yaml.getString("transaction-error", "<red>Errore, apri un ticket."),
                yaml.getString("shop-reloaded", "<green>Shop ricaricato!"),
                yaml.getString("confirm-title", "Conferma Acquisto"),
                yaml.getString("item-price", "<white>Prezzo: <green><price>$"),
                yaml.getString("item-amount", ""),
                yaml.getString("confirm-quantity", "<gray>Quantità: <white>x<amount>"),
                yaml.getString("item-click", "<yellow>➥ Clicca per acquistare")
        );
    }

    private SoundConfig parseSound(ConfigurationSection sec) {
        if (sec == null) return new SoundConfig("ui.button.click", 0.5f, 1.0f, false);
        return new SoundConfig(
                sec.getString("sound", "ui.button.click"),
                (float) sec.getDouble("volume", 0.5),
                (float) sec.getDouble("pitch", 1.0),
                sec.getBoolean("enabled", true)
        );
    }
}
