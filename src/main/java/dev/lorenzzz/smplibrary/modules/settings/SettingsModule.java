package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * /settings: mostra un Dialog (Paper Dialog API) costruito da inventories/settings.yml.
 * Sostituisce la vecchia implementazione MenuAPI con la nuova Dialog API di Paper 1.21.7+.
 */
public final class SettingsModule extends AbstractModule implements Listener {

    private static final String COMMAND = "settings";

    private SettingsConfigLoader configLoader;

    public SettingsModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return COMMAND; }

    @Override
    public void onEnable() {
        this.configLoader = new SettingsConfigLoader(plugin);
        configLoader.load();

        registerCommand();
        listen(this);

        C.info("Settings module enabled (Dialog API).");
    }

    private void registerCommand() {
        PluginCommand command = plugin.getCommand(COMMAND);
        if (command == null) {
            C.warning("Command '" + COMMAND + "' not found in plugin.yml");
            return;
        }
        command.setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof Player player) {
                openDialog(player);
            } else {
                C.send(sender, "&cOnly players.");
            }
            return true;
        });
    }

    public void openDialog(Player player) {
        SettingsConfig cfg = configLoader == null ? null : configLoader.get();
        if (cfg == null) return;
        new SettingsDialog(plugin, cfg).show(player);
    }

    /** Drops the cached ON/OFF states when a player leaves. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        SettingsDialog.clearStates(event.getPlayer().getUniqueId());
    }

    @Override
    protected void onShutdown() {
        configLoader = null;
    }
}
