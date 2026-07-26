package dev.lorenzzz.smplibrary.modules.keyall;

import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reference module — periodic command that gives every online player a crate key.
 * Configured under MODULES.keyall in config.yml.
 */
public final class KeyAllModule implements Module {

    private final JavaPlugin plugin;
    private ConfigurationSection cfg;
    private StorageManager storage;
    private CommandManager commands;
    private MessagesManager messages;
    private int taskId = -1;

    // ponytail: epoch millis of the next scheduled run. Drifts if server lags hard, good enough for a scoreboard countdown.
    private long nextRunEpoch = 0;
    private long periodMillis = 0;

    public KeyAllModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "keyall"; }

    /** Epoch millis of the next keyall tick, or 0 if the module isn't running. */
    public long nextRunEpoch() { return nextRunEpoch; }

    @Override
    public void onEnable() {
        cfg = plugin.getConfig().getConfigurationSection("MODULES.keyall");
        if (cfg == null) return;

        String every = cfg.getString("task-every", "1h");
        long ticks = parseTicks(every);
        long periodTicks = Math.max(ticks, 20L * 60); // ponytail: floor at 1 min — never spam keys.

        periodMillis = periodTicks / 20L * 1000L;
        nextRunEpoch = System.currentTimeMillis() + periodMillis;
        taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, this::runKeyAll, periodTicks, periodTicks).getTaskId();
    }

    private void runKeyAll() {
        nextRunEpoch = System.currentTimeMillis() + periodMillis;   // roll the countdown for the next tick
        String command = cfg.getString("command", "");
        int amount = cfg.getInt("amount", 1);
        String crate = cfg.getString("crate-name", "common_crate");

        // %player%, %crate-name%, %amount% replaced per-player. Flat keys handled by config.
        plugin.getServer().getOnlinePlayers().forEach(p -> {
            String cmd = command
                    .replace("%player%", p.getName())
                    .replace("%crate-name%", crate)
                    .replace("%amount%", String.valueOf(amount));
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
        });
    }

    private static long parseTicks(String period) {
        if (period == null || period.isBlank()) return 20L * 60 * 60; // 1h default
        period = period.trim().toLowerCase();
        char unit = period.charAt(period.length() - 1);
        long n;
        try { n = Long.parseLong(period.substring(0, period.length() - 1)); }
        catch (NumberFormatException e) { return 20L * 60 * 60; }
        long seconds = switch (unit) {
            case 's' -> n;
            case 'm' -> n * 60;
            case 'h' -> n * 60 * 60;
            case 'd' -> n * 60 * 60 * 24;
            default -> n; // plain number → seconds
        };
        return seconds * 20L;
    }

    @Override
    public void onDisable() {
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    @Override public Module storage(StorageManager storage) { this.storage = storage; return this; }
    @Override public Module commands(CommandManager commands) { this.commands = commands; return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }
}