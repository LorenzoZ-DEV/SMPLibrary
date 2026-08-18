package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * /settings: mostra un Dialog (Paper Dialog API) costruito da inventories/settings.yml.
 * Sostituisce la vecchia implementazione MenuAPI con la nuova Dialog API di Paper 1.21.7+.
 */
public final class SettingsModule extends AbstractModule implements Listener {

    private static final String COMMAND = "settings";

    private SettingsConfigLoader configLoader;
    private SettingsStateStore store;

    public SettingsModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return COMMAND; }

    @Override
    public void onEnable() {
        this.configLoader = new SettingsConfigLoader(plugin);
        configLoader.load();

        this.store = new SettingsStateStore(plugin, storage);
        store.initialize();

        registerCommand();
        listen(this);

        // Su /reload i giocatori sono gia' online: nessun join in arrivo per loro.
        for (Player online : Bukkit.getOnlinePlayers()) {
            store.whenLoaded(online.getUniqueId(), () -> {});
        }

        C.info("Settings module enabled (Dialog API) — toggle salvati su " + store.backend() + ".");
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
        if (cfg == null || store == null) return;
        new SettingsDialog(plugin, cfg, store).show(player);
    }

    /** Reads the stored toggles ahead of the first /settings so the dialog opens instantly. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (store != null) store.whenLoaded(event.getPlayer().getUniqueId(), () -> {});
    }

    /** Drops the cached ON/OFF states when a player leaves; the database keeps them. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (store != null) store.unload(event.getPlayer().getUniqueId());
    }

    @Override
    protected void onShutdown() {
        if (store != null) store.clear();
        store = null;
        configLoader = null;
    }
}
