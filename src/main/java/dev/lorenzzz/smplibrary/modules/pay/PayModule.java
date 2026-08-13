package dev.lorenzzz.smplibrary.modules.pay;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

/**
 * Player-to-player "pistacchi" transfers, driven by {@code /pay}. Money moves through the
 * shared EconomyManager; the limits below come from MODULES.pay in config.yml.
 */
public final class PayModule extends AbstractModule {

    private static final String MESSAGE_ROOT = "pay.";

    public PayModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "pay"; }

    @Override
    public void onEnable() {
        C.info("Pay module enabled (currency: " + economy().defaultCurrency() + ").");
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

    /** Moves {@code amount} from one player to the other; false leaves both balances untouched. */
    public boolean transfer(Player from, Player to, double amount) {
        if (!economy().withdraw(from, amount)) return false;
        if (economy().deposit(to, amount)) return true;

        // ponytail: the deposit failed — put the money back where it came from.
        economy().deposit(from, amount);
        C.warning("Refunded " + amount + " to " + from.getName() + " after a failed transfer to " + to.getName());
        return false;
    }

    // --------------------------------------------------------------- messages

    public void send(CommandSender target, String key, Map<String, ?> placeholders) {
        messages.send(target, MESSAGE_ROOT + key, placeholders);
    }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "minimum-amount", 1.0,
                "maximum-amount", 1_000_000.0,
                "allow-self", false,
                "currency-decimals", 0);
    }
}
