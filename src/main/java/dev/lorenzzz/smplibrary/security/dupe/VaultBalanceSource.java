package dev.lorenzzz.smplibrary.security.dupe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The only class here that names Vault's types.
 *
 * <p>Vault is {@code compileOnly}, so on a server without it {@code net.milkbowl.vault.*} does
 * not exist and loading this class fails. Nothing else in the package touches it: a host with a
 * different economy API passes its own {@link BalanceSource} lambda and never mentions this
 * class, which is why the watcher itself does not name Vault anywhere.
 *
 * <p><strong>One economy only.</strong> zEssentials marks exactly one of its economies
 * {@code vault: true} — {@code money} out of the box — and Vault has no notion of the others.
 * Asked about {@code coins} it would happily return the {@code money} balance, which would be
 * compared against the {@code coins} row and reported as a critical mismatch on every single
 * player. So the economy this instance speaks for is stated up front, and anything else answers
 * "unknown". To watch the rest, use {@link ZEssentialsBalanceSource}.
 */
public final class VaultBalanceSource {

    private VaultBalanceSource() {}

    /**
     * Wraps a Vault economy.
     *
     * @param economyName the zEssentials economy this Vault provider actually represents, i.e.
     *                    the one configured with {@code vault: true}
     */
    public static BalanceSource of(Economy economy, String economyName) {
        if (economy == null) throw new IllegalArgumentException("economy is required");
        if (economyName == null || economyName.isBlank()) {
            throw new IllegalArgumentException("economyName is required: Vault speaks for exactly one");
        }
        String name = economyName.trim();
        return (player, asked) -> {
            if (player == null || !name.equalsIgnoreCase(asked)) return OptionalDouble.empty();
            if (!economy.hasAccount(player)) return OptionalDouble.empty();
            return OptionalDouble.of(economy.getBalance(player));
        };
    }

    /**
     * Resolves the registered Vault economy, if any. Only call this when Vault is installed —
     * merely reaching this method loads the Vault classes.
     */
    public static Optional<BalanceSource> fromServices(String economyName) {
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) return Optional.empty();
        return Optional.of(of(registration.getProvider(), economyName));
    }
}
