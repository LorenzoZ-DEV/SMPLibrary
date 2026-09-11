package dev.lorenzzz.smplibrary.modules.keyall;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Periodic command that gives every online player a crate key.
 * Configured under MODULES.keyall in config.yml; {@code /keyall} fires it manually.
 *
 * <p>The keys are handed out over several ticks rather than all inside the scheduled one. Each
 * key is a console command, which means the command parser, the crate plugin's own lookups and
 * whatever it does to the player's data — cheap once, but a full server's worth of it landing on
 * a single tick is a visible hitch every hour, on the hour. See
 * {@link AbstractModule#batch(java.util.Collection, java.util.function.Consumer, int)}.
 */
public final class KeyAllModule extends AbstractModule {

    private static final long DEFAULT_PERIOD = Ticks.HOUR;
    private static final long MINIMUM_PERIOD = Ticks.MINUTE; // ponytail: never spam keys.

    /** Players served per tick when the batch does not fit in one. */
    private static final int DEFAULT_BATCH = 10;

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
     * %crate-name% and %amount%.
     *
     * @return how many players were queued. With more players than {@code batch-size} the last
     *         of them is served a few ticks later, so this is a count of recipients rather than
     *         of commands that have already run.
     */
    public int run() {
        ConfigurationSection cfg = config();
        if (cfg == null) return 0;

        String command = cfg.getString("command", "");
        if (command.isBlank()) return 0;

        // Resolved once for the whole pass — the same two values were being substituted into
        // the template again for every single player.
        String template = command
                .replace("%crate-name%", cfg.getString("crate-name", "common_crate"))
                .replace("%amount%", String.valueOf(cfg.getInt("amount", 1)));

        List<Player> recipients = List.copyOf(Bukkit.getOnlinePlayers());
        batch(recipients,
                player -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        template.replace("%player%", player.getName())),
                cfg.getInt("batch-size", DEFAULT_BATCH));
        return recipients.size();
    }

    @Override
    protected void onShutdown() {
        nextRunEpoch = 0;
    }
}
