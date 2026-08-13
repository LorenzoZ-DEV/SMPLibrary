package dev.lorenzzz.smplibrary.modules.firstjoin;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.Map;

/**
 * First-join welcome + starter kit. Brand-new players get a private welcome message
 * and a list of commands run on their behalf (default {@code /kit claim start});
 * returning players get a plain welcome-back message and nothing else.
 * Configured under MODULES.firstjoin in config.yml; messages read from {@code first-join.*}
 * in messages.yml (multi-line {@code |-} blocks supported).
 *
 * ponytail: first-join detection via Player.hasPlayedBefore() — the server already tracks
 * the player dat file, so no DB table is needed. StorageManager stays unused here.
 */
public final class FirstJoinModule extends AbstractModule implements Listener {

    public FirstJoinModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "firstjoin"; }

    @Override
    public void onEnable() {
        if (config() == null) return;
        listen(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Map<String, String> placeholders = Map.of(
                "%player%", player.getName(),
                "%player_name%", player.getName());

        if (player.hasPlayedBefore()) {
            messages.sendWithActionBar(player, "first-join.returning", placeholders);
            return;
        }

        messages.sendWithActionBar(player, "first-join.welcome", placeholders);

        ConfigurationSection cfg = config();
        if (cfg == null) return;

        if (cfg.getBoolean("broadcast-first-join", false)) {
            messages.broadcast("first-join.broadcast", placeholders);
        }
        runStarterCommands(player, cfg.getStringList("commands"));
    }

    /** Runs the configured kit commands as the player, back on the main thread. */
    private void runStarterCommands(Player player, List<String> commands) {
        if (commands.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String command : commands) {
                if (command == null || command.isBlank()) continue;
                player.performCommand(command.replace("%player%", player.getName()));
            }
        });
    }
}
