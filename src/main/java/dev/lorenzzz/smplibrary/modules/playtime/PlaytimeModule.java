package dev.lorenzzz.smplibrary.modules.playtime;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Map;

public class PlaytimeModule implements Module {

    private final Smp_library plugin;
    private MessagesManager messages;
    private int taskId = -1;
    private Object economyManager;

    public PlaytimeModule(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() { return "playtime"; }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("MODULES.playtime");
        if (cfg == null) return;

        setupEconomy();

        long intervalTicks = parseTicks(cfg.getString("every", "5m"));
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::reward, intervalTicks, intervalTicks).getTaskId();
        C.info("Playtime module enabled — rewarding every " + cfg.getString("every", "5m") + ".");
    }

    private void setupEconomy() {
        try {
            Object zEssentials = Bukkit.getPluginManager().getPlugin("zEssentials");
            if (zEssentials != null) {
                economyManager = zEssentials.getClass().getMethod("getEconomyManager").invoke(zEssentials);
            }
        } catch (Exception e) {
            economyManager = null;
        }
    }

    private void reward() {
        if (economyManager == null) {
            setupEconomy();
            if (economyManager == null) return;
        }

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("MODULES.playtime");
        if (cfg == null) return;

        double amount = cfg.getDouble("amount", 100.0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            giveMoney(player, amount);

            Map<String, String> ph = Map.of(
                    "%amount%", String.valueOf((int) amount),
                    "%player%", player.getName()
            );
            if (messages != null) {
                messages.sendBoth(player, "playtime.reward", ph);
            }
        }
    }

    private void giveMoney(Player player, double amount) {
        try {
            Class<?> economyCls = Class.forName("fr.maxlego08.essentials.api.economy.Economy");
            Object defaultEconomy = economyManager.getClass().getMethod("getDefaultEconomy").invoke(economyManager);
            economyManager.getClass().getMethod("deposit", java.util.UUID.class, economyCls, BigDecimal.class)
                    .invoke(economyManager, player.getUniqueId(), defaultEconomy, BigDecimal.valueOf(amount));
        } catch (Exception e) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "eco add coins " + player.getName() + " " + (int) amount);
        }
    }

    private static long parseTicks(String period) {
        if (period == null || period.isBlank()) return 20L * 60 * 5;
        period = period.trim().toLowerCase();
        char unit = period.charAt(period.length() - 1);
        long n;
        try { n = Long.parseLong(period.substring(0, period.length() - 1)); }
        catch (NumberFormatException e) { return 20L * 60 * 5; }
        long seconds = switch (unit) {
            case 's' -> n;
            case 'm' -> n * 60;
            case 'h' -> n * 60 * 60;
            default -> n;
        };
        return seconds * 20L;
    }

    @Override
    public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        economyManager = null;
    }

    @Override public Module storage(StorageManager storage) { return this; }
    @Override public Module commands(CommandManager commands) { return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }
}
