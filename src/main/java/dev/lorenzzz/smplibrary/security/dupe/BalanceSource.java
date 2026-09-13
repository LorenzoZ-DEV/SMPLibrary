package dev.lorenzzz.smplibrary.security.dupe;

import org.bukkit.OfflinePlayer;

import java.util.OptionalDouble;

/**
 * The cache side of the comparison: whatever the server thinks a player holds right now.
 *
 * <p>Per economy, because zEssentials keeps several side by side — {@code money}, {@code coins},
 * whatever else is configured — in one table with an {@code economy_name} column. A source that
 * answered with a single number could only ever be checked against one of them, and comparing
 * that number to the wrong row is how you invent a dupe that never happened.
 *
 * <p>Always read on the main thread: {@link EconomyDupeWatcher} marshals every call through the
 * global region scheduler, so implementations may touch plugin state freely and must not do any
 * I/O of their own.
 *
 * <p>Returning an empty {@link OptionalDouble} means "I cannot answer for this player and this
 * economy", which is not the same as zero and must never be compared against a stored value: an
 * unknown treated as 0.00 raises a critical alert on every account the source does not know.
 * That is exactly what {@link VaultBalanceSource} does for every economy but its own.
 *
 * <p>Two ready-made wirings:
 * <pre>{@code
 * // zEssentials directly — the only one that can answer for every economy:
 * BalanceSource source = ZEssentialsBalanceSource.create();
 *
 * // Vault, which speaks for the single economy flagged vault: true:
 * BalanceSource source = VaultBalanceSource.of(economy, "money");
 * }</pre>
 */
@FunctionalInterface
public interface BalanceSource {

    /**
     * The balance held in memory for this economy, or empty when this source has nothing to say
     * about it.
     *
     * @param economy the economy name as zEssentials spells it ({@code money}, {@code coins}, …)
     */
    OptionalDouble balance(OfflinePlayer player, String economy);
}
