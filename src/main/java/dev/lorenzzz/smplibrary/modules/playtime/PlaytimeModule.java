package dev.lorenzzz.smplibrary.modules.playtime;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;

/** Pays every online player a fixed amount on a fixed interval. Configured under MODULES.playtime. */
public final class PlaytimeModule extends AbstractModule {

    private static final long DEFAULT_PERIOD = 5 * Ticks.MINUTE;
    private static final long MINIMUM_PERIOD = Ticks.MINUTE;

    private double amount;
    private String currency;

    public PlaytimeModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "playtime"; }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();
        if (cfg == null) return;

        amount = cfg.getDouble("amount", 100.0);
        currency = cfg.getString("currency", "money");

        String every = cfg.getString("every", cfg.getString("task-every", "5m"));
        long periodTicks = Ticks.parseAtLeast(every, DEFAULT_PERIOD, MINIMUM_PERIOD);

        repeat(this::reward, periodTicks, periodTicks);
        C.info("Playtime module enabled — paying " + (long) amount + " " + currency + " every " + every + ".");
    }

    private void reward() {
        if (amount <= 0) return;

        String formatted = String.valueOf((long) amount);
        for (Player player : Bukkit.getOnlinePlayers()) {
            economy().deposit(player, amount, currency);
            if (messages != null) {
                messages.sendBoth(player, "playtime.reward", Map.of(
                        "%amount%", formatted,
                        "%player%", player.getName()));
            }
        }
    }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "every", "5m",
                "amount", 100.0,
                "currency", "money");
    }
}
