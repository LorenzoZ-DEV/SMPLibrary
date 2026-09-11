package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The plugin's single door to the server economy.
 *
 * <p>zEssentials is hooked reflectively so the plugin still loads (and degrades to console
 * {@code /eco} commands) when it is absent. The reflection handles are resolved once at boot
 * because the API classes don't move at runtime; the {@code Economy} instance itself is
 * resolved <em>per call</em> by name, since zEssentials recreates those objects when it
 * reloads and a cached one goes stale.
 *
 * <p>The default currency comes from {@code MODULES.shop.economy} (kept for config
 * compatibility); callers that use a different one — playtime, for instance — pass it in.
 */
public final class EconomyManager implements Manager {

    private static final String DEFAULT_CURRENCY = "coins";
    private static final String BALANCE_PLACEHOLDER = "%zessentials_user_balance_coins%";

    private final Smp_library plugin;
    private final Set<String> warnedCurrencies = new HashSet<>();

    /**
     * Serializes the debit/credit pairs of {@link #transfer}. zEssentials exposes no
     * transaction of its own, so this is the closest thing to one we can offer: while a
     * transfer holds the lock no other transfer can read a balance it is about to change,
     * which is what turns "check, withdraw, deposit" into a single step for our callers.
     */
    private final Object transferLock = new Object();

    private Object zEconomyManager;
    private Method getEconomy;
    private Method getDefaultEconomy;
    private Method getBalance;
    private Method withdraw;
    private Method deposit;

    private String defaultCurrency = DEFAULT_CURRENCY;

    public EconomyManager(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "economy"; }

    @Override
    public void enable() {
        reload();
    }

    /** Re-reads the configured currency and re-hooks zEssentials. Safe to call on /smpl reload. */
    public void reload() {
        defaultCurrency = plugin.getConfig().getString("MODULES.shop.economy", DEFAULT_CURRENCY);
        warnedCurrencies.clear();
        hook();
    }

    private void hook() {
        reset();
        try {
            Object zEssentials = Bukkit.getPluginManager().getPlugin("zEssentials");
            if (zEssentials == null) {
                C.info("zEssentials not present — economy falls back to console /eco commands.");
                return;
            }

            Object manager = zEssentials.getClass().getMethod("getEconomyManager").invoke(zEssentials);
            if (manager == null) return;

            Class<?> impl = manager.getClass();
            Class<?> economyType = Class.forName("fr.maxlego08.essentials.api.economy.Economy");

            getBalance = impl.getMethod("getBalance", OfflinePlayer.class, economyType);
            withdraw = impl.getMethod("withdraw", UUID.class, economyType, BigDecimal.class);
            deposit = impl.getMethod("deposit", UUID.class, economyType, BigDecimal.class);
            getEconomy = methodOrNull(impl, "getEconomy", String.class);
            getDefaultEconomy = methodOrNull(impl, "getDefaultEconomy");

            // Without a way to resolve an Economy instance the typed methods are unusable.
            if (getEconomy == null && getDefaultEconomy == null) {
                reset();
                C.warning("zEssentials economy API changed — falling back to console /eco commands.");
                return;
            }

            zEconomyManager = manager;
            C.info("Economy hooked to zEssentials (currency: '" + defaultCurrency + "').");
        } catch (Exception e) {
            reset();
            C.warning("Could not hook zEssentials economy (" + e.getClass().getSimpleName()
                    + ") — falling back to console /eco commands.");
        }
    }

    private static Method methodOrNull(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private void reset() {
        zEconomyManager = null;
        getEconomy = null;
        getDefaultEconomy = null;
        getBalance = null;
        withdraw = null;
        deposit = null;
    }

    /** True when the typed zEssentials API is in use; false when falling back to console commands. */
    public boolean hooked() {
        return zEconomyManager != null;
    }

    public String defaultCurrency() {
        return defaultCurrency;
    }

    // ------------------------------------------------------------ operations

    public double balance(Player player) {
        return balance(player, defaultCurrency);
    }

    public double balance(Player player, String currency) {
        if (zEconomyManager != null && getBalance != null) {
            try {
                Object result = getBalance.invoke(zEconomyManager, player, economyOrWarn(currency));
                if (result instanceof BigDecimal amount) return amount.doubleValue();
            } catch (Exception ignored) {
                // fall through to the placeholder path
            }
        }
        return placeholderBalance(player);
    }

    public boolean withdraw(Player player, double amount) {
        return withdraw(player, amount, defaultCurrency);
    }

    public boolean withdraw(Player player, double amount, String currency) {
        return transfer(withdraw, player, amount, currency, "take");
    }

    public boolean deposit(Player player, double amount) {
        return deposit(player, amount, defaultCurrency);
    }

    public boolean deposit(Player player, double amount, String currency) {
        return transfer(deposit, player, amount, currency, "give");
    }

    private boolean transfer(Method method, Player player, double amount, String currency, String ecoVerb) {
        if (zEconomyManager != null && method != null) {
            try {
                Object result = method.invoke(zEconomyManager, player.getUniqueId(),
                        economyOrWarn(currency), BigDecimal.valueOf(amount));
                if (result instanceof Boolean ok && ok) return true;
                C.warning("Economy refused to " + ecoVerb + " " + amount + " " + currency + " for " + player.getName());
                return false;
            } catch (Exception e) {
                C.warning("Economy failed to " + ecoVerb + " " + amount + " " + currency
                        + " for " + player.getName() + ": " + e.getMessage());
                return false;
            }
        }
        // ponytail: no API — console /eco is fire-and-forget, so we can only assume success.
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "eco " + ecoVerb + " " + currency + " " + player.getName() + " " + plainAmount(amount));
    }

    /**
     * The amount as {@code /eco} wants to read it. {@code (long) amount} used to be good enough
     * while every currency was whole-numbered, but it silently ate the decimals of anything that
     * is not — a 12.5 transfer took 12.5 off the sender and handed 12 to the receiver, and the
     * missing half pistacchio was created or destroyed depending on which leg was truncated.
     * {@link BigDecimal#toPlainString()} rather than {@code Double.toString}, which would hand
     * the parser {@code 1.0E7} for large round amounts.
     */
    private static String plainAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------- transfers

    /**
     * Moves {@code amount} between two players as one step: the balance check, the debit and the
     * credit all happen under {@link #transferLock}, so two transfers racing over the same wallet
     * cannot both pass the check and overdraw it.
     *
     * <p>If the credit fails the debit is rolled back. A rollback that <em>also</em> fails is the
     * one case where money really is gone, and it is logged at error level with everything needed
     * to refund by hand — silently swallowing it is how a dupe report starts.
     *
     * @return true only when the receiver was credited.
     */
    public boolean transfer(Player from, Player to, double amount, String currency) {
        if (amount <= 0) return false;

        synchronized (transferLock) {
            // Only meaningful on the typed API; the placeholder fallback reads a currency it was
            // not asked about, so a check on it would reject perfectly valid transfers.
            if (hooked() && balance(from, currency) < amount) return false;

            if (!withdraw(from, amount, currency)) return false;
            if (deposit(to, amount, currency)) return true;

            if (!deposit(from, amount, currency)) {
                C.error("TRANSFER LOST " + plainAmount(amount) + " " + currency + ": taken from "
                        + from.getName() + " (" + from.getUniqueId() + "), never credited to "
                        + to.getName() + " (" + to.getUniqueId() + "), and the refund failed too. "
                        + "Refund this by hand.");
                return false;
            }
            C.warning("Refunded " + plainAmount(amount) + " " + currency + " to " + from.getName()
                    + " after a failed transfer to " + to.getName() + ".");
            return false;
        }
    }

    /** Resolves the named Economy, falling back to the server default (warned once per currency). */
    private Object economyOrWarn(String currency) {
        if (getEconomy != null) {
            try {
                Object result = getEconomy.invoke(zEconomyManager, currency);
                if (result instanceof Optional<?> optional && optional.isPresent()) return optional.get();
            } catch (Exception ignored) {
                // fall through to the default economy
            }
        }
        if (warnedCurrencies.add(currency)) {
            C.warning("zEssentials economy '" + currency + "' not found — using the default economy. "
                    + "Check MODULES.shop.economy in config.yml.");
        }
        if (getDefaultEconomy != null) {
            try {
                return getDefaultEconomy.invoke(zEconomyManager);
            } catch (Exception ignored) {
                // nothing left to try
            }
        }
        return null;
    }

    private double placeholderBalance(Player player) {
        if (!C.papiEnabled()) return 0;
        String parsed = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, BALANCE_PLACEHOLDER);
        try {
            return Double.parseDouble(parsed);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void disable() {
        reset();
        warnedCurrencies.clear();
    }
}
