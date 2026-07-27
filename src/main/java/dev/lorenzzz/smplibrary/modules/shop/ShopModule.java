package dev.lorenzzz.smplibrary.modules.shop;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.math.BigDecimal;

public class ShopModule implements Module {

    private final Smp_library plugin;
    private MenuHandler menuHandler;
    private ShopConfigLoader configLoader;
    private Object economyManager;

    public ShopModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "shop"; }

    @Override
    public void onEnable() {
        this.menuHandler = new MenuHandler(plugin);
        menuHandler.setInventoryCreator((player, slots, title) ->
                Bukkit.createInventory(player, slots, C.mm(title)));

        this.configLoader = new ShopConfigLoader(plugin);
        configLoader.load();

        setupEconomy();
        registerCommands();

        C.info("Shop system enabled with " + configLoader.get().categories().size() + " categories.");
    }

    private void setupEconomy() {
        try {
            Object zEssentials = Bukkit.getPluginManager().getPlugin("zEssentials");
            if (zEssentials != null) {
                economyManager = zEssentials.getClass().getMethod("getEconomyManager").invoke(zEssentials);
            }
        } catch (Exception e) {
            economyManager = null;
        }
    }

    public double getBalance(Player player) {
        if (economyManager != null) {
            try {
                Class<?> economyCls = Class.forName("fr.maxlego08.essentials.api.economy.Economy");
                Object defaultEconomy = economyManager.getClass().getMethod("getDefaultEconomy").invoke(economyManager);
                Object balance = economyManager.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class, economyCls)
                        .invoke(economyManager, player, defaultEconomy);
                if (balance instanceof BigDecimal bd) return bd.doubleValue();
            } catch (Exception ignored) {}
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            String parsed = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%zessentials_user_balance_coins%");
            try { return Double.parseDouble(parsed); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    public boolean takeMoney(Player player, double amount) {
        if (economyManager != null) {
            try {
                Class<?> economyCls = Class.forName("fr.maxlego08.essentials.api.economy.Economy");
                Object defaultEconomy = economyManager.getClass().getMethod("getDefaultEconomy").invoke(economyManager);
                Object result = economyManager.getClass().getMethod("withdraw", java.util.UUID.class, economyCls, BigDecimal.class)
                        .invoke(economyManager, player.getUniqueId(), defaultEconomy, BigDecimal.valueOf(amount));
                if (result instanceof Boolean ok && ok) return true;
                C.warning("Failed to take money from " + player.getName());
                return false;
            } catch (Exception e) {
                C.warning("Failed to take money from " + player.getName());
                return false;
            }
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "eco take coins " + player.getName() + " " + (int) amount);
        return true;
    }

    private void registerCommands() {
        setExecutor("pistacchi");
        PluginCommand settings = plugin.getCommand("settings");
        if (settings != null) {
            settings.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players.");
                    return true;
                }
                openMainMenu(player);
                return true;
            });
        }
        PluginCommand live = plugin.getCommand("live");
        if (live != null) {
            live.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players.");
                    return true;
                }
                openMainMenu(player);
                return true;
            });
        }
    }

    private void setExecutor(String cmdName) {
        PluginCommand cmd = plugin.getCommand(cmdName);
        if (cmd == null) return;
        cmd.setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players.");
                return true;
            }
            openMainMenu(player);
            return true;
        });
    }

    public void openMainMenu(Player player) {
        ShopConfig cfg = configLoader.get();
        new ShopMainMenu(cfg, player, menuHandler, this).open();
        playSound(player, cfg.openShopSound());
    }

    public void openCategory(Player player, CategoryConfig category) {
        new ShopCategoryMenu(configLoader.get(), category, player, menuHandler, this).open();
        playSound(player, configLoader.get().openCategorySound());
    }

    public void openConfirm(Player player, ProductConfig product, CategoryConfig category) {
        new ShopConfirmMenu(configLoader.get(), product, category, player, menuHandler, this).open();
        playSound(player, configLoader.get().selectItemSound());
    }

    public void playSound(Player player, SoundConfig sound) {
        if (sound != null && sound.enabled() && sound.sound() != null && !sound.sound().isEmpty()) {
            try {
                org.bukkit.Sound s = org.bukkit.Sound.valueOf(sound.sound().toUpperCase().replace(".", "_"));
                player.playSound(player.getLocation(), s, sound.volume(), sound.pitch());
            } catch (IllegalArgumentException e) {
                player.playSound(player.getLocation(), sound.sound(), sound.volume(), sound.pitch());
            }
        }
    }

    public Smp_library getPlugin() { return plugin; }
    public ShopConfigLoader getConfigLoader() { return configLoader; }

    @Override
    public void onDisable() {
        if (menuHandler != null) {
            menuHandler.getOpenMenus().values().forEach(menu -> menu.close(false));
        }
        menuHandler = null;
        configLoader = null;
        economyManager = null;
    }

    @Override
    public Module storage(StorageManager storage) { return this; }
    @Override
    public Module commands(CommandManager commands) { return this; }
    @Override
    public Module messages(MessagesManager messages) { return this; }
}
