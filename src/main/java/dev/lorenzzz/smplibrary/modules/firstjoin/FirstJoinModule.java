package dev.lorenzzz.smplibrary.modules.firstjoin;

import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

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
public final class FirstJoinModule implements Module, Listener {

    private final JavaPlugin plugin;
    private ConfigurationSection cfg;
    private StorageManager storage;
    private CommandManager commands;
    private MessagesManager messages;

    public FirstJoinModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "firstjoin"; }

    @Override
    public void onEnable() {
        cfg = plugin.getConfig().getConfigurationSection("MODULES.firstjoin");
        if (cfg == null) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // %player_displayname% left to PlaceholderAPI; only the two raw names are injected here.
        Map<String, String> ph = Map.of(
                "%player%", player.getName(),
                "%player_name%", player.getName());

        if (player.hasPlayedBefore()) {
            // Returning join: welcome-back only, no kit/commands.
            player.sendMessage(messages.component("first-join.returning", player, ph));
            return;
        }

        // First join: private welcome, optional server-wide broadcast, then the starter-kit commands.
        player.sendMessage(messages.component("first-join.welcome", player, ph));

        if (cfg.getBoolean("broadcast-first-join", false)) {
            Component bcast = messages.component("first-join.broadcast", null, ph);
            if (bcast != Component.empty()) {
                for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(bcast);
            }
        }

        List<String> commandList = cfg.getStringList("commands");
        if (commandList.isEmpty()) return;
        // ponytail: defer 1 tick so the player's world/inventory are settled before kit plugins act.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String cmd : commandList) {
                if (cmd == null || cmd.isBlank()) continue;
                // Run as the joining player — e.g. "/kit claim start" claims the player's own kit.
                // No leading slash; %player% etc. are left to PAPI if the kit command needs them.
                player.performCommand(cmd.replace("%player%", player.getName()));
            }
        });
    }

    @Override public void onDisable() { }

    @Override public Module storage(StorageManager storage) { this.storage = storage; return this; }
    @Override public Module commands(CommandManager commands) { this.commands = commands; return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }
}