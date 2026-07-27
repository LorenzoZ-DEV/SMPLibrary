package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;

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
    private final Map<Class<?>, Manager> managers = new LinkedHashMap<>();
    private final List<Manager> ordered = new ArrayList<>();

    private ConfigManager config;
    private MessagesManager messages;
    private StorageManager storage;
    private CommandManager commands;
    private ModuleManager modules;

    private MenuHandler menuHandler;

    public ManagerService(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() { return "manager-service"; }

    @Override
    public void enable() {
        // order matters: config -> messages -> storage -> commands -> modules
        config = register(new ConfigManager(plugin));
        messages = register(new MessagesManager(plugin));
        storage = register(new StorageManager(plugin));
        commands = register(new CommandManager(plugin));
        modules = register(new ModuleManager(plugin));

        for (Manager m : ordered) {
            try {
                m.enable();
            } catch (Exception e) {
                C.error("Failed enabling " + m.name() + ": " + e);
                e.printStackTrace();
            }
        }
    }

    public MenuHandler menuHandler() {
        if (menuHandler == null) {
            menuHandler = new MenuHandler(plugin);
            menuHandler.setInventoryCreator((player, slots, title) ->
                    Bukkit.createInventory(player, slots, C.mm(title)));
        }
        return menuHandler;
    }

    private <T extends Manager> T register(T manager) {
        managers.put(manager.getClass(), manager);
        ordered.add(manager);
        return manager;
    }

    @Override
    public void disable() {
        if (menuHandler != null) {
            menuHandler.getOpenMenus().values().forEach(menu -> menu.close(false));
            menuHandler = null;
        }
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Manager m = ordered.get(i);
            try {
                m.disable();
            } catch (Exception e) {
                C.warning("Error disabling " + m.name() + ": " + e);
                e.printStackTrace();
            }
        }
        managers.clear();
        ordered.clear();
    }

    @SuppressWarnings("unchecked")
    public <T extends Manager> T get(Class<T> type) {
        return (T) managers.get(type);
    }

    public ConfigManager config() { return config; }
    public MessagesManager messages() { return messages; }
    public StorageManager storage() { return storage; }
    public CommandManager commands() { return commands; }
    public ModuleManager modules() { return modules; }
}