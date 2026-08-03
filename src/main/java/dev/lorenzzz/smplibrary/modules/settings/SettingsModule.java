package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * /settings: mostra un Dialog (Paper Dialog API) costruito da inventories/settings.yml.
 * Sostituisce la vecchia implementazione MenuAPI con la nuova Dialog API di Paper 1.21.7+.
 */
public class SettingsModule implements Module {

    private final Smp_library plugin;
    private SettingsConfigLoader configLoader;
    private Listener quitListener;

    public SettingsModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "settings"; }

    @Override
    public void onEnable() {
        this.configLoader = new SettingsConfigLoader(plugin);
        configLoader.load();

        registerCommands();
        registerListeners();

        C.info("Settings module enabled (Dialog API).");
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
            openDialog(player);
            return true;
        });
    }

    private void registerListeners() {
        quitListener = new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent e) {
                // Pulisce la memoria degli stati ON/OFF quando il player esce
                SettingsDialog.clearStates(e.getPlayer().getUniqueId());
            }
        };
        Bukkit.getPluginManager().registerEvents(quitListener, plugin);
    }

    public void openDialog(Player player) {
        SettingsConfig cfg = configLoader.get();
        if (cfg == null) return;
        new SettingsDialog(plugin, cfg).show(player);
    }

    @Override
    public void onDisable() {
        if (quitListener != null) {
            HandlerList.unregisterAll(quitListener);
            quitListener = null;
        }
        configLoader = null;
    }

    @Override
    public Module storage(StorageManager storage) { return this; }
    @Override
    public Module commands(CommandManager commands) { return this; }
    @Override
    public Module messages(MessagesManager messages) { return this; }
}