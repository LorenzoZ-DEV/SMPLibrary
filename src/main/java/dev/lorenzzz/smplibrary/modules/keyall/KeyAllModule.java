package dev.lorenzzz.smplibrary.modules.keyall;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Periodic command that gives every online player a crate key.
 * Configured under MODULES.keyall in config.yml; {@code /keyall} fires it manually.
 */
public final class KeyAllModule extends AbstractModule {

    private static final long DEFAULT_PERIOD = Ticks.HOUR;
    private static final long MINIMUM_PERIOD = Ticks.MINUTE; // ponytail: never spam keys.

    // ponytail: epoch millis of the next scheduled run. Drifts if the server lags hard,
    // good enough for a scoreboard countdown.
    private long nextRunEpoch;
    private long periodMillis;

    public KeyAllModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "keyall"; }

    /** Epoch millis of the next keyall tick, or 0 if the module isn't running. */
    public long nextRunEpoch() { return nextRunEpoch; }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();
        if (cfg == null) return;

        long periodTicks = Ticks.parseAtLeast(cfg.getString("task-every"), DEFAULT_PERIOD, MINIMUM_PERIOD);
        periodMillis = periodTicks / Ticks.SECOND * 1000L;
        nextRunEpoch = System.currentTimeMillis() + periodMillis;

        repeat(this::onTick, periodTicks, periodTicks);
    }

    private void onTick() {
        nextRunEpoch = System.currentTimeMillis() + periodMillis; // roll the countdown
        run();
    }

    /**
     * Runs the configured command once per online player, substituting %player%,
     * %crate-name% and %amount%. Returns how many players were served.
     */
    public int run() {
        ConfigurationSection cfg = config();
        if (cfg == null) return 0;

        String command = cfg.getString("command", "");
        if (command.isBlank()) return 0;

        String crate = cfg.getString("crate-name", "common_crate");
        String amount = String.valueOf(cfg.getInt("amount", 1));

        int served = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command
                    .replace("%player%", player.getName())
                    .replace("%crate-name%", crate)
                    .replace("%amount%", amount));
            served++;
        }
        return served;
    }

    @Override
    protected void onShutdown() {
        nextRunEpoch = 0;
    }
}
