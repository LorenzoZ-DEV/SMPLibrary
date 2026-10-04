package dev.lorenzzz.smplibrary.modules.playtime;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Log;
import dev.lorenzzz.smplibrary.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Pays every online player a fixed amount on a fixed interval. Configured under MODULES.playtime.
 *
 * <p>A payout is an economy write plus a message per player, and the economy write is the
 * expensive half — on a hooked zEssentials it is a reflective call that may reach the database.
 * Doing every one of them inside the scheduled tick makes that tick as long as the player count;
 * the payouts are batched across ticks instead. Nobody can tell the difference: the interval is
 * minutes and the spread is a handful of ticks.
 */
public final class PlaytimeModule extends AbstractModule {

    private static final long DEFAULT_PERIOD = 5 * Ticks.MINUTE;
    private static final long MINIMUM_PERIOD = Ticks.MINUTE;

    /** Players paid per tick when the batch does not fit in one. */
    private static final int DEFAULT_BATCH = 20;

    private double amount;
    private String currency;
    private int batchSize = DEFAULT_BATCH;

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
        batchSize = cfg.getInt("batch-size", DEFAULT_BATCH);

        String every = cfg.getString("every", cfg.getString("task-every", "5m"));
        long periodTicks = Ticks.parseAtLeast(every, DEFAULT_PERIOD, MINIMUM_PERIOD);

        repeat(this::reward, periodTicks, periodTicks);
        Log.info("Playtime module enabled — paying " + (long) amount + " " + currency + " every " + every + ".");
    }

    private void reward() {
        if (amount <= 0) return;

        String formatted = String.valueOf((long) amount);
        batch(List.copyOf(Bukkit.getOnlinePlayers()), player -> pay(player, formatted), batchSize);
    }

    /**
     * One player's payout. Runs a few ticks after the schedule fired, so the player may well have
     * logged off in between — paying an offline player through the Player-typed API is not
     * something the economy is promised to handle, and they were not online for the interval
     * anyway.
     */
    private void pay(Player player, String formatted) {
        if (!player.isOnline()) return;

        economy().deposit(player, amount, currency);
        if (messages != null) {
            messages.sendBoth(player, "playtime.reward", Map.of(
                    "%amount%", formatted,
                    "%player%", player.getName()));
        }
    }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "every", "5m",
                "amount", 100.0,
                "currency", "money",
                "batch-size", DEFAULT_BATCH);
    }
}
