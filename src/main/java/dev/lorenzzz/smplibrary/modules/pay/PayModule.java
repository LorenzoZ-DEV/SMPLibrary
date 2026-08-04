package dev.lorenzzz.smplibrary.modules.pay;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.shop.ShopModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Player-to-player "pistacchi" transfers. Delegates the actual economy
 * operations (balance / withdraw / deposit) to {@link ShopModule}, which owns
 * the zEssentials economy reflection. Configured under MODULES.pay in config.yml.
 */
public final class PayModule implements Module {

    private final JavaPlugin plugin;
    private ConfigurationSection cfg;
    private StorageManager storage;
    private CommandManager commands;
    private MessagesManager messages;

    public PayModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "pay"; }

    @Override
    public void onEnable() {
        cfg = plugin.getConfig().getConfigurationSection("MODULES.pay");
        if (cfg == null) {
            cfg = plugin.getConfig().createSection("MODULES.pay");
        }
        C.info("Pay module enabled (currency: pistacchi).");
    }

    public ShopModule shop() {
        Module m = Smp_library.getInstance().getManagers().modules().module("shop");
        return m instanceof ShopModule s ? s : null;
    }

    public double getBalance(Player player) {
        ShopModule s = shop();
        return s == null ? 0 : s.getBalance(player);
    }

    public boolean takeMoney(Player player, double amount) {
        ShopModule s = shop();
        return s != null && s.takeMoney(player, amount);
    }

    public boolean giveMoney(Player player, double amount) {
        ShopModule s = shop();
        return s != null && s.giveMoney(player, amount);
    }

    public double minimumAmount() { return cfg == null ? 1.0 : cfg.getDouble("minimum-amount", 1.0); }
    public double maximumAmount() { return cfg == null ? 1_000_000.0 : cfg.getDouble("maximum-amount", 1_000_000.0); }
    public boolean selfPayAllowed() { return cfg != null && cfg.getBoolean("allow-self", false); }

    public int currencyDecimals() { return cfg == null ? 0 : cfg.getInt("currency-decimals", 0); }

    public String formatAmount(double amount) {
        int decimals = currencyDecimals();
        String s = decimals <= 0
                ? String.valueOf((long) amount)
                : String.format(java.util.Locale.ROOT, "%." + decimals + "f", amount);
        return s;
    }

    public String message(String key, Map<String, ?> placeholders) {
        String raw = messages == null ? "" : messages.raw("pay." + key);
        String out = C.replace(raw, placeholders);
        return C.color(out);
    }

    public void send(org.bukkit.command.CommandSender target, String key, Map<String, ?> placeholders) {
        String msg = message(key, placeholders);
        if (msg == null || msg.isEmpty()) return;
        C.send(target, msg);
    }

    public void messageBoth(Player target, String key, Map<String, ?> placeholders) {
        String raw = messages == null ? "" : messages.raw("pay." + key);
        if (raw == null || raw.isEmpty()) return;
        String out = C.replace(raw, placeholders);
        C.sendChatAndAction(target, C.mm(out));
    }

    @Override
    public void onDisable() {
        cfg = null;
    }

    @Override public Module storage(StorageManager storage) { this.storage = storage; return this; }
    @Override public Module commands(CommandManager commands) { this.commands = commands; return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }

    public MessagesManager messages() { return messages; }

    @Override
    public java.util.Map<String, Object> defaults() {
        return java.util.Map.of(
                "enabled", true,
                "minimum-amount", 1.0,
                "maximum-amount", 1000000.0,
                "allow-self", false,
                "currency-decimals", 0
        );
    }
}
