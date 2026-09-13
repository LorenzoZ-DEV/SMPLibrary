package dev.lorenzzz.smplibrary.security.dupe;

import fr.maxlego08.essentials.api.EssentialsPlugin;
import fr.maxlego08.essentials.api.economy.Economy;
import fr.maxlego08.essentials.api.economy.EconomyManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The cache side read straight from zEssentials, which is the only thing that can answer for
 * every economy.
 *
 * <p>Vault carries one balance per player because it was designed when a server had one
 * currency; zEssentials has {@code money}, {@code coins} and whatever else the economy module
 * lists, and only the one marked {@code vault: true} is reachable through Vault. Watching the
 * others means asking zEssentials itself.
 *
 * <p>Same soft-dependency shape as {@link VaultBalanceSource} and {@code TpAutoBridge}: this is
 * the only class in the package that names {@code fr.maxlego08.essentials.*}, so loading it on a
 * server without zEssentials fails and nothing else does. {@link #create()} is the gate — it
 * checks the plugin is enabled first.
 *
 * <p>The {@link Economy} handles are resolved per call rather than cached, because zEssentials
 * rebuilds them when its economy module reloads and a cached one goes stale — the same reason
 * this plugin's own {@code EconomyManager} does it that way.
 */
public final class ZEssentialsBalanceSource {

    private static final String PLUGIN_NAME = "zEssentials";

    private ZEssentialsBalanceSource() {}

    /**
     * A source backed by the running zEssentials.
     *
     * <p>Guard the call where zEssentials might be absent — {@code
     * Bukkit.getPluginManager().isPluginEnabled("zEssentials")} — and note that the guard has to
     * live in the caller: merely reaching this class resolves the zEssentials types, so a check
     * written as a method here would be the very thing that blows up.
     *
     * @throws IllegalStateException when zEssentials is not loaded
     */
    public static BalanceSource create() {
        Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (!(found instanceof EssentialsPlugin plugin)) {
            throw new IllegalStateException(PLUGIN_NAME + " is not loaded");
        }
        return (player, economyName) -> {
            if (player == null || economyName == null) return OptionalDouble.empty();

            EconomyManager economies = plugin.getEconomyManager();
            if (economies == null) return OptionalDouble.empty();

            Optional<Economy> economy = economies.getEconomy(economyName);
            // An economy the server does not have is "unknown", never zero: a missing currency
            // must not read as a player who lost everything.
            if (economy.isEmpty()) return OptionalDouble.empty();

            BigDecimal balance = economies.getBalance(player, economy.get());
            return balance == null ? OptionalDouble.empty() : OptionalDouble.of(balance.doubleValue());
        };
    }
}
