package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

/**
 * /settings: builds a MenuAPI menu from inventories/settings.yml.
 * Uses the shared MenuHandler from ManagerService so module reloads
 * don't leak listeners / scheduler tasks.
 */
public class SettingsModule implements Module {

    private final Smp_library plugin;
    private MenuHandler menuHandler;
    private SettingsConfigLoader configLoader;

    public SettingsModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "settings"; }

    @Override
    public void onEnable() {
        this.menuHandler = plugin.getManagers().menuHandler();

        this.configLoader = new SettingsConfigLoader(plugin);
        configLoader.load();

        registerCommands();

        C.info("Settings module enabled.");
    }

    private void registerCommands() {
        PluginCommand cmd = plugin.getCommand("settings");
        if (cmd == null) {
            C.warning("Command 'settings' not found in plugin.yml");
            return;
        }
        cmd.setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players.");
                return true;
            }
            openMenu(player);
            return true;
        });
    }

    public void openMenu(Player player) {
        SettingsConfig cfg = configLoader.get();
        if (cfg == null) return;
        new SettingsMenu(cfg, player, menuHandler).open();
    }

    @Override
    public void onDisable() {
        if (menuHandler != null) {
            menuHandler.getOpenMenus().values().forEach(menu -> menu.close(false));
        }
        menuHandler = null;
        configLoader = null;
    }

    @Override
    public Module storage(StorageManager storage) { return this; }
    @Override
    public Module commands(CommandManager commands) { return this; }
    @Override
    public Module messages(MessagesManager messages) { return this; }
}
