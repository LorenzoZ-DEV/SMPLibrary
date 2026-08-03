package dev.lorenzzz.smplibrary.modules.firstjoin;

import dev.lorenzzz.smplibrary.C;
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
import org.bukkit.event.HandlerList;
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
public final class FirstJoinModule implements Module {

    private final JavaPlugin plugin;
    private ConfigurationSection cfg;
    private StorageManager storage;
    private CommandManager commands;
    private MessagesManager messages;
    private JoinListener listener;

    public FirstJoinModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "firstjoin"; }

    @Override
    public void onEnable() {
        cfg = plugin.getConfig().getConfigurationSection("MODULES.firstjoin");
        if (cfg == null) return;
        Bukkit.getPluginManager().registerEvents(listener = new JoinListener(), plugin);
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
    }

    private final class JoinListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            Map<String, String> ph = Map.of(
                    "%player%", player.getName(),
                    "%player_name%", player.getName());

            if (player.hasPlayedBefore()) {
                player.sendMessage(messages.component("first-join.returning", player, ph));
                C.sendChatAndAction(player, messages.raw("first-join.returning").split("\n")[0]);
                return;
            }

            player.sendMessage(messages.component("first-join.welcome", player, ph));
            C.sendChatAndAction(player, "&#F3F622&lBENVENUTO &r&8➟ &fBenvenuto sul server, &d" + player.getName() + "&f!");

            if (cfg.getBoolean("broadcast-first-join", false)) {
                Component bcast = messages.component("first-join.broadcast", null, ph);
                if (bcast != Component.empty()) {
                    String raw = C.replace(messages.raw("first-join.broadcast"), ph);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendMessage(bcast);
                        p.sendActionBar(C.mm(raw));
                    }
                }
            }

            List<String> commandList = cfg.getStringList("commands");
            if (commandList.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String cmd : commandList) {
                    if (cmd == null || cmd.isBlank()) continue;
                    player.performCommand(cmd.replace("%player%", player.getName()));
                }
            });
        }
    }

    @Override public Module storage(StorageManager storage) { this.storage = storage; return this; }
    @Override public Module commands(CommandManager commands) { this.commands = commands; return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }
}
