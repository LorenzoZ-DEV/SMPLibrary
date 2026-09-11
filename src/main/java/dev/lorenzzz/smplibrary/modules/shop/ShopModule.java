package dev.lorenzzz.smplibrary.modules.shop;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.web.WebLog;
import dev.lorenzzz.smplibrary.modules.web.WebModule;
import net.j4c0b3y.api.menu.MenuHandler;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Menu-driven shop. The inventories come from inventories/shop.yml via {@link ShopConfigLoader};
 * money goes through the shared {@link dev.lorenzzz.smplibrary.manager.EconomyManager}, so the
 * shop no longer owns any economy plumbing of its own.
 */
public final class ShopModule extends AbstractModule {

    /** Commands declared in plugin.yml that simply open the shop. */
    private static final List<String> OPEN_COMMANDS = List.of("pistacchi", "live");

    /** How many spent purchase ids to remember. See {@link #claimPurchase}. */
    private static final int PURCHASE_HISTORY = 4_096;

    /**
     * Purchase ids that have been spent. Bounded and insertion-ordered: an id is only useful for
     * as long as a duplicate of its click could still be in flight, which is a matter of ticks,
     * so the oldest entries are dropped rather than kept forever.
     */
    private final Set<UUID> spentPurchases = Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<UUID, Boolean>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > PURCHASE_HISTORY;
                }
            }));

    private MenuHandler menuHandler;
    private ShopConfigLoader configLoader;

    public ShopModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "shop"; }

    @Override
    public void onEnable() {
        this.menuHandler = managers.menuHandler();
        this.configLoader = new ShopConfigLoader(plugin);
        configLoader.load();

        CommandExecutor openShop = (sender, command, label, args) -> {
            if (sender instanceof Player player) {
                openMainMenu(player);
            } else {
                C.send(sender, "&cOnly players.");
            }
            return true;
        };
        OPEN_COMMANDS.forEach(name -> setExecutor(name, openShop));

        C.info("Shop system enabled with " + configLoader.get().categories().size() + " categories.");
    }

    private void setExecutor(String name, CommandExecutor executor) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            C.warning("Command '" + name + "' is missing from plugin.yml — shop entry point skipped.");
            return;
        }
        command.setExecutor(executor);
    }

    // ------------------------------------------------------------------ menus

    public void openMainMenu(Player player) {
        ShopConfig cfg = configLoader.get();
        new ShopMainMenu(cfg, player, menuHandler, this).open();
        playSound(player, cfg.openShopSound());
    }

    public void openCategory(Player player, CategoryConfig category) {
        ShopConfig cfg = configLoader.get();
        new ShopCategoryMenu(cfg, category, player, menuHandler, this).open();
        playSound(player, cfg.openCategorySound());
    }

    public void openConfirm(Player player, ProductConfig product, CategoryConfig category) {
        ShopConfig cfg = configLoader.get();
        new ShopConfirmMenu(cfg, product, category, player, menuHandler, this).open();
        playSound(player, cfg.selectItemSound());
    }

    public void playSound(Player player, SoundConfig sound) {
        if (sound == null || !sound.enabled() || sound.sound() == null || sound.sound().isEmpty()) return;
        String name = sound.sound();
        if (!name.contains(":")) name = name.toLowerCase(Locale.ROOT).replace('_', '.');
        player.playSound(player.getLocation(), name, sound.volume(), sound.pitch());
    }

    // -------------------------------------------------------------- purchases

    /**
     * Claims a purchase id. False means it was claimed already, and the caller must deliver
     * nothing — the purchase it describes has been (or is being) handled.
     *
     * <p>This is the shop's half of the double-click guard. The confirm menu's own PROCESSING
     * state catches the repeated click; this catches the case that state cannot see — the same
     * purchase arriving through a second path.
     */
    public boolean claimPurchase(UUID purchaseId) {
        return spentPurchases.add(purchaseId);
    }

    /** Hands back a claim whose purchase charged nothing, so the player can try again. */
    public void releasePurchase(UUID purchaseId) {
        spentPurchases.remove(purchaseId);
    }

    // ------------------------------------------------------------------- log

    /**
     * Puts one completed purchase on the dashboard's log panel (and, through the console echo,
     * in latest.log — which is where it survives a restart, since the panel keeps only the last
     * hundred entries in memory).
     *
     * <p>The web module is looked up per call rather than cached: the dashboard can be toggled
     * off and back on from its own config page, and a cached {@link WebLog} would then be
     * writing into a ring nobody serves any more. No dashboard simply means no log line — a
     * purchase must never fail because the site is down.
     */
    public void logPurchase(Player player, String item, int quantity, double cost, CategoryConfig category) {
        try {
            WebLog log = dashboardLog();
            if (log == null) return;

            String currency = economy() == null ? "" : " " + economy().defaultCurrency();
            log.record("Shop: " + player.getName() + " ha comprato x" + quantity + " " + plain(item)
                    + " per " + formatAmount(cost) + currency
                    + " (categoria: " + plain(category == null ? "?" : category.inventoryName()) + ")");
        } catch (Exception e) {
            // The money is already gone and the commands have run: an audit line that fails to
            // render must not blow up in the player's face.
            C.warning("Shop: could not log the purchase of " + player.getName() + ": " + e.getMessage());
        }
    }

    private WebLog dashboardLog() {
        ModuleManager modules = plugin.modules();
        WebModule web = modules == null ? null : modules.module("web", WebModule.class);
        return web == null ? null : web.log();
    }

    /** Menu labels carry MiniMessage tags; a log line wants the words, not the markup. */
    private static String plain(String formatted) {
        if (formatted == null || formatted.isEmpty()) return "?";
        return PlainTextComponentSerializer.plainText().serialize(C.mm(formatted));
    }

    private static String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    // ---------------------------------------------------------------- economy

    public double getBalance(Player player) {
        return economy().balance(player);
    }

    public boolean takeMoney(Player player, double amount) {
        return economy().withdraw(player, amount);
    }

    public boolean giveMoney(Player player, double amount) {
        return economy().deposit(player, amount);
    }

    public ShopConfigLoader getConfigLoader() { return configLoader; }

    @Override
    protected void onShutdown() {
        managers.closeOpenMenus();
        OPEN_COMMANDS.forEach(name -> setExecutor(name, null));
        spentPurchases.clear();
        menuHandler = null;
        configLoader = null;
    }

    @Override
    public Map<String, Object> defaults() {
        return Map.of("enabled", true, "economy", "coins");
    }
}
