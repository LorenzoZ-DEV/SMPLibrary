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

import java.lang.reflect.Method;
import java.math.BigDecimal;

public class ShopModule implements Module {

    private final Smp_library plugin;
    private MenuHandler menuHandler;
    private ShopConfigLoader configLoader;
    private Object economyManager;
    // ponytail: economy reflection resolved once at enable; the zEssentials API
    // classes/methods don't move at runtime, so caching beats Class.forName per call.
    private Class<?> economyClass;
    private Method getDefaultEconomyMethod;
    private Method getBalanceMethod;
    private Method withdrawMethod;
    private Object defaultEconomy;

    public ShopModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "shop"; }

    @Override
    public void onEnable() {
        this.menuHandler = plugin.getManagers().menuHandler();

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
            if (economyManager != null) {
                economyClass = Class.forName("fr.maxlego08.essentials.api.economy.Economy");
                getDefaultEconomyMethod = economyManager.getClass().getMethod("getDefaultEconomy");
                getBalanceMethod = economyManager.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class, economyClass);
                withdrawMethod = economyManager.getClass().getMethod("withdraw", java.util.UUID.class, economyClass, BigDecimal.class);
            }
        } catch (Exception e) {
            economyManager = null;
        }
    }

    // ponytail: default economy cached once; restart the plugin if the server's
    // default economy ever changes at runtime (it won't in normal setups).
    private Object defaultEconomy() {
        if (defaultEconomy == null && getDefaultEconomyMethod != null) {
            try { defaultEconomy = getDefaultEconomyMethod.invoke(economyManager); } catch (Exception ignored) {}
        }
        return defaultEconomy;
    }

    public double getBalance(Player player) {
        if (economyManager != null && getBalanceMethod != null) {
            try {
                Object balance = getBalanceMethod.invoke(economyManager, player, defaultEconomy());
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
        if (economyManager != null && withdrawMethod != null) {
            try {
                Object result = withdrawMethod.invoke(economyManager, player.getUniqueId(), defaultEconomy(), BigDecimal.valueOf(amount));
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
        economyClass = null;
        getDefaultEconomyMethod = null;
        getBalanceMethod = null;
        withdrawMethod = null;
        defaultEconomy = null;
    }

    @Override
    public Module storage(StorageManager storage) { return this; }
    @Override
    public Module commands(CommandManager commands) { return this; }
    @Override
    public Module messages(MessagesManager messages) { return this; }
}
