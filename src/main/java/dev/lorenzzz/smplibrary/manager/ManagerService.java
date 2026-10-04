package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.text.Text;
import dev.lorenzzz.smplibrary.util.Log;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns every manager, starts them in dependency order, stops them LIFO.
 * Also owns the plugin-wide MenuHandler so modules (shop, settings) don't
 * create their own (which would leak listeners + scheduler tasks on reload).
 */
public final class ManagerService implements Manager {

    private final Smp_library plugin;
    // LinkedHashMap: registration order IS the start-up order, reversed for shutdown.
    private final Map<Class<?>, Manager> managers = new LinkedHashMap<>();

    private ConfigManager config;
    private MessagesManager messages;
    private StorageManager storage;
    private EconomyManager economy;
    private CommandManager commands;
    private DialogGuard dialogGuard;
    private ToggleManager toggles;
    private ModuleManager modules;

    private MenuHandler menuHandler;

    public ManagerService(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() { return "manager-service"; }

    @Override
    public void enable() {
        // order matters: config -> messages -> storage -> economy -> commands -> dialogs
        //                 -> toggles -> modules
        config = register(new ConfigManager(plugin));
        messages = register(new MessagesManager(plugin));
        storage = register(new StorageManager(plugin));
        economy = register(new EconomyManager(plugin));
        commands = register(new CommandManager(plugin));
        // Before modules: a module arms the guard from its own onEnable path, and the guard's
        // join listener has to be live before the first player can walk into a guarded dialog.
        dialogGuard = register(new DialogGuard(plugin));
        // Before modules too: settings renders the stored toggles and tpauto reads them, so
        // the store has to be up (and preloading joins) before either can ask it anything.
        toggles = register(new ToggleManager(plugin, storage));
        modules = register(new ModuleManager(plugin));

        for (Manager manager : managers.values()) {
            try {
                manager.enable();
            } catch (Exception e) {
                Log.error("Failed enabling " + manager.name() + ": " + e);
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Manager start-up failure", e);
            }
        }
    }

    public MenuHandler menuHandler() {
        if (menuHandler == null) {
            menuHandler = new MenuHandler(plugin);
            menuHandler.setInventoryCreator((player, slots, title) ->
                    Bukkit.createInventory(player, slots, Text.mini(title)));
        }
        return menuHandler;
    }

    /** Closes every open menu — used on module reload and on shutdown. */
    public void closeOpenMenus() {
        if (menuHandler == null) return;
        List.copyOf(menuHandler.getOpenMenus().values()).forEach(menu -> menu.close(false));
    }

    private <T extends Manager> T register(T manager) {
        managers.put(manager.getClass(), manager);
        return manager;
    }

    @Override
    public void disable() {
        closeOpenMenus();
        menuHandler = null;

        List<Manager> ordered = new ArrayList<>(managers.values());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Manager manager = ordered.get(i);
            try {
                manager.disable();
            } catch (Exception e) {
                Log.warning("Error disabling " + manager.name() + ": " + e);
            }
        }
        managers.clear();
    }

    @SuppressWarnings("unchecked")
    public <T extends Manager> T get(Class<T> type) {
        return (T) managers.get(type);
    }

    public ConfigManager config() { return config; }
    public MessagesManager messages() { return messages; }
    public StorageManager storage() { return storage; }
    public EconomyManager economy() { return economy; }
    public CommandManager commands() { return commands; }
    public DialogGuard dialogGuard() { return dialogGuard; }
    public ToggleManager toggles() { return toggles; }
    public ModuleManager modules() { return modules; }
}
