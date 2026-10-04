package dev.lorenzzz.smplibrary.modules.pay;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Player-to-player "pistacchi" transfers, driven by {@code /pistacchipay} (alias
 * {@code /pagapers}) — not by the server's {@code /pay}, which is zEssentials' own command over
 * a different currency. Money moves through the shared EconomyManager; the limits below come
 * from MODULES.pay in config.yml.
 *
 * <p>A transfer is one operation, not three. It used to be "withdraw, deposit, refund if the
 * deposit failed", which is fine until two of them overlap: both read the same balance, both
 * pass the check, and the wallet goes negative — or the refund lands while a second transfer is
 * reading, and the money is counted twice. {@link #transfer} now runs behind an id that can only
 * be spent once ({@link TransferLedger}) and a per-sender lock, and the debit/credit pair itself
 * happens inside {@link dev.lorenzzz.smplibrary.manager.EconomyManager#transfer}, which holds its
 * own lock across both legs. The result is always one of {@link TransferResult} — completed, or
 * nothing charged.
 */
public final class PayModule extends AbstractModule {

    private static final String MESSAGE_ROOT = "pay.";

    private final TransferLedger ledger = new TransferLedger();

    public PayModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "pay"; }

    @Override
    public void onEnable() {
        Log.info("Pay module enabled (currency: " + economy().defaultCurrency() + ").");
    }

    // ----------------------------------------------------------------- limits

    public double minimumAmount() { return setting("minimum-amount", 1.0); }

    public double maximumAmount() { return setting("maximum-amount", 1_000_000.0); }

    public boolean selfPayAllowed() {
        ConfigurationSection cfg = config();
        return cfg != null && cfg.getBoolean("allow-self", false);
    }

    public int currencyDecimals() {
        ConfigurationSection cfg = config();
        return cfg == null ? 0 : cfg.getInt("currency-decimals", 0);
    }

    /** Extra protection on top of the ledger, not a substitute for it. 0 disables it. */
    public long cooldownMillis() {
        ConfigurationSection cfg = config();
        double seconds = cfg == null ? 1.0 : cfg.getDouble("cooldown-seconds", 1.0);
        return seconds <= 0 ? 0L : (long) (seconds * 1000d);
    }

    private double setting(String key, double fallback) {
        ConfigurationSection cfg = config();
        return cfg == null ? fallback : cfg.getDouble(key, fallback);
    }

    /** Rounds the request to the configured precision — whole pistacchi by default. */
    public double normalizeAmount(double amount) {
        return currencyDecimals() <= 0 ? Math.floor(amount) : amount;
    }

    public String formatAmount(double amount) {
        int decimals = currencyDecimals();
        return decimals <= 0
                ? String.valueOf((long) amount)
                : String.format(Locale.ROOT, "%." + decimals + "f", amount);
    }

    // ---------------------------------------------------------------- economy

    public double getBalance(Player player) {
        return economy().balance(player);
    }

    /** A fresh id for one attempted transfer. Spend it exactly once, in {@link #transfer}. */
    public UUID newTransferId() {
        return UUID.randomUUID();
    }

    /** Milliseconds the player still has to wait before paying again; 0 when they may pay now. */
    public long cooldownRemaining(Player player) {
        return ledger.cooldownRemaining(player.getUniqueId(), cooldownMillis());
    }

    /**
     * Moves {@code amount} from one player to the other, once.
     *
     * <p>Every outcome other than {@link TransferResult#COMPLETED} leaves both balances exactly
     * as they were: the guards all run before any money moves, and the debit/credit pair itself
     * rolls back its own debit if the credit fails. The id is only kept when the transfer really
     * happened — a refusal releases it, so the player can retry with the same command.
     *
     * @param transferId from {@link #newTransferId()}; replaying one yields {@code DUPLICATE}.
     */
    public TransferResult transfer(UUID transferId, Player from, Player to, double amount) {
        if (amount <= 0) return TransferResult.FAILED;

        UUID payer = from.getUniqueId();
        if (!ledger.claim(transferId)) return TransferResult.DUPLICATE;

        boolean completed = false;
        try {
            if (ledger.onCooldown(payer, cooldownMillis())) return TransferResult.COOLDOWN;
            if (!ledger.lock(payer)) return TransferResult.IN_PROGRESS;

            try {
                // Re-read inside the lock: the balance the command checked is only a hint by now.
                if (getBalance(from) < amount) return TransferResult.INSUFFICIENT_FUNDS;

                if (!economy().transfer(from, to, amount, economy().defaultCurrency())) {
                    return TransferResult.FAILED;
                }
                completed = true;
                ledger.markCompleted(payer);
                return TransferResult.COMPLETED;
            } finally {
                ledger.unlock(payer);
            }
        } finally {
            // Nothing was charged, so the id was never really spent — let them try again.
            if (!completed) ledger.release(transferId);
        }
    }

    // --------------------------------------------------------------- messages

    public void send(CommandSender target, String key, Map<String, ?> placeholders) {
        messages.send(target, MESSAGE_ROOT + key, placeholders);
    }

    @Override
    protected void onShutdown() {
        // The id history is worthless across a restart (no command survives one) but an
        // in-flight entry would be: a player disconnected mid-transfer would come back unable
        // to pay at all.
        ledger.clearPlayers();
    }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "minimum-amount", 1.0,
                "maximum-amount", 1_000_000.0,
                "allow-self", false,
                "currency-decimals", 0,
                "cooldown-seconds", 1.0);
    }
}
