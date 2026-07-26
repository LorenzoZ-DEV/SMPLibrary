package dev.lorenzzz.smplibrary.modules.menu;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class MenuModule implements Module {

    private final Smp_library plugin;
    private MenuHandler menuHandler;
    private MenuLoader menuLoader;
    private ConfigurationSection cfg;
    private StorageManager storage;
    private CommandManager commands;
    private MessagesManager messages;

    private Object economyManager;
    private final List<Command> registeredCommands = new ArrayList<>();

    public MenuModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "menu"; }

    @Override
    public void onEnable() {
        cfg = plugin.getConfig().getConfigurationSection("MODULES.menu");
        if (cfg == null) return;

        this.menuHandler = new MenuHandler(plugin);
        this.menuLoader = new MenuLoader(plugin);
        menuLoader.loadAll();

        setupEconomy();
        registerCommands();

        C.info("Menu system enabled with " + menuLoader.allMenus().size() + " menus.");
    }

    private void setupEconomy() {
        try {
            Object zEssentials = Bukkit.getPluginManager().getPlugin("zEssentials");
            if (zEssentials != null) {
                economyManager = zEssentials.getClass().getMethod("getEconomyManager").invoke(zEssentials);
                C.info("zEssentials economy hooked for Pistacchi Shop.");
            }
        } catch (Exception e) {
            economyManager = null;
            C.info("zEssentials not found — using commands for economy.");
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

    private void registerCommands() {
        try {
            Field bukkitMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            bukkitMap.setAccessible(true);
            CommandMap map = (CommandMap) bukkitMap.get(Bukkit.getServer());

            String mainCmd = cfg.getString("command", "pistacchi");
            List<String> aliases = cfg.getStringList("aliases");

            registerCommand(map, mainCmd, aliases, "pistacchi-main");
            registerCommand(map, "settings", List.of("impostazioni"), "settings_inventory");
            registerCommand(map, "live", List.of("tiktok"), "live_inventory");

        } catch (Exception e) {
            C.warning("Could not register menu commands: " + e.getMessage());
        }
    }

    private void registerCommand(CommandMap map, String name, List<String> aliases, String defaultMenu) {
        Command cmd = new Command(name, "Open " + defaultMenu + " menu", "/" + name, aliases) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can use this command.");
                    return true;
                }
                openMenu(player, defaultMenu);
                return true;
            }
        };
        map.register(plugin.getName().toLowerCase(), cmd);
        registeredCommands.add(cmd);
    }

    public void openMenu(Player player, String menuName) {
        MenuConfig config = menuLoader.getMenu(menuName);
        if (config == null) {
            player.sendMessage(C.color("&cMenu not found: " + menuName));
            return;
        }
        new DynamicMenu(config, player, menuHandler, this).open();
    }

    @Override
    public void onDisable() {
        if (menuHandler != null) {
            menuHandler.getOpenMenus().values().forEach(menu -> menu.close(false));
        }
        try {
            Field bukkitMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            bukkitMap.setAccessible(true);
            CommandMap map = (CommandMap) bukkitMap.get(Bukkit.getServer());
            for (Command cmd : registeredCommands) {
                cmd.unregister(map);
            }
        } catch (Exception ignored) {}
        registeredCommands.clear();
        menuLoader = null;
        menuHandler = null;
    }

    @Override
    public Module storage(StorageManager storage) { this.storage = storage; return this; }
    @Override
    public Module commands(CommandManager commands) { this.commands = commands; return this; }
    @Override
    public Module messages(MessagesManager messages) { this.messages = messages; return this; }
}
