package dev.lorenzzz.smplibrary.modules.shop;

import dev.lorenzzz.smplibrary.C;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.Button;
import net.j4c0b3y.api.menu.button.impl.SimpleButton;
import net.j4c0b3y.api.menu.layer.impl.BackgroundLayer;
import net.j4c0b3y.api.menu.layer.impl.ForegroundLayer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The "are you sure" screen. One instance is one purchase: it carries a single
 * {@link #purchaseId} and a {@link State} that only ever moves forward, which is what stops an
 * autoclicker from buying the same thing four times.
 *
 * <p>The window between the click and the inventory actually closing is several ticks wide, and
 * every click that lands inside it used to start its own purchase. The fix is not a cooldown: the
 * click handler flips the state to {@link State#PROCESSING} as its very first statement, before
 * it touches the economy, so any further click is dropped on the floor rather than raced.
 */
public class ShopConfirmMenu extends Menu {

    /**
     * {@link #READY} accepts a confirm; {@link #PROCESSING} is the purchase in flight;
     * {@link #DONE} is a purchase that went through and must never run again from this menu.
     * A failed purchase goes back to READY — the player did not get charged and may well want
     * to try again once they have the money.
     */
    private enum State { READY, PROCESSING, DONE }

    private final ShopConfig config;
    private final ProductConfig product;
    private final CategoryConfig category;
    private final ShopModule module;

    /** Identifies this purchase to {@link ShopModule#claimPurchase}; spent at most once. */
    private final UUID purchaseId = UUID.randomUUID();

    private State state = State.READY;
    private int quantity = 1;

    public ShopConfirmMenu(ShopConfig config, ProductConfig product, CategoryConfig category,
                           Player player, MenuHandler handler, ShopModule module) {
        super(config.messages().confirmTitle(), MenuSize.of(3), player, handler);
        this.config = config;
        this.product = product;
        this.category = category;
        this.module = module;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        TemplateItem filler = config.items().get("filler");
        if (filler != null) {
            Material mat = Material.getMaterial(filler.material().toUpperCase());
            if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (!filler.name().isEmpty()) meta.displayName(C.mm(filler.name()));
                if (filler.lore() != null && !filler.lore().isEmpty())
                    meta.lore(filler.lore().stream().map(C::mm).collect(Collectors.toList()));
                item.setItemMeta(meta);
            }
            background.fill(new SimpleButton(item, click -> {}));
        }

        foreground.set(0, 0, buildSetButton(1));
        foreground.set(1, 0, buildSetButton(8));
        foreground.set(2, 0, buildSetButton(16));
        foreground.set(3, 0, buildSetButton(32));
        foreground.set(4, 0, buildSetButton(64));

        foreground.set(4, 1, buildProductDisplay());

        foreground.set(2, 2, buildAcceptButton());
        foreground.set(6, 2, buildDeclineButton());
    }

    private void updateProductDisplay() {
        getForeground().set(4, 1, new SimpleButton(buildProductStack(), click -> {}));
        update();
    }

    private Button buildSetButton(int amount) {
        StackItemConfig stackCfg = config.addStackItem();
        Material mat = Material.getMaterial(stackCfg.type().toUpperCase());
        if (mat == null) mat = Material.LIME_STAINED_GLASS_PANE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(C.mm(stackCfg.displayName().replace("<amount>", String.valueOf(amount))));
        }
        item.setItemMeta(meta);

        return new SimpleButton(item, click -> {
            // Changing the quantity of a purchase that is already being charged would decide
            // the price after the fact.
            if (state != State.READY) return;
            quantity = amount;
            module.playSound(getPlayer(), config.navigatePageSound());
            updateProductDisplay();
        });
    }

    private Button buildProductDisplay() {
        return new SimpleButton(buildProductStack(), click -> {});
    }

    private String resolveItemName() {
        Material mat = Material.getMaterial(product.material().toUpperCase());
        if (mat == null) mat = Material.STONE;
        String name = product.displayName();
        if (name == null || name.isEmpty()) {
            name = mat.name().toLowerCase().replace("_", " ");
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    private ItemStack buildProductStack() {
        Material mat = Material.getMaterial(product.material().toUpperCase());
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat, Math.min(quantity, 64));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(C.mm(resolveItemName()));

        List<String> lore = new ArrayList<>();
        if (product.lore() != null) lore.addAll(product.lore());
        lore.add("");

        String priceLine = config.messages().itemPrice();
        if (priceLine != null && !priceLine.isEmpty()) {
            lore.add(priceLine.replace("<price>", String.valueOf(product.price() * quantity)));
        }

        String qtyLine = config.messages().confirmQuantity();
        if (qtyLine == null || qtyLine.isEmpty()) qtyLine = "<gray>Quantità: <white>x<amount>";
        lore.add(qtyLine.replace("<amount>", String.valueOf(quantity)));

        meta.lore(lore.stream().map(C::mm).collect(Collectors.toList()));
        item.setItemMeta(meta);
        return item;
    }

    private Button buildAcceptButton() {
        TemplateItem accept = config.items().get("accept");
        Material mat = Material.LIME_STAINED_GLASS_PANE;
        String name = "<green>✔ Conferma";
        List<String> lore = List.of();

        if (accept != null) {
            mat = Material.getMaterial(accept.material().toUpperCase());
            if (mat == null) mat = Material.LIME_STAINED_GLASS_PANE;
            name = accept.name();
            lore = accept.lore();
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(C.mm(name));
            if (!lore.isEmpty()) meta.lore(lore.stream().map(C::mm).collect(Collectors.toList()));
        }
        item.setItemMeta(meta);

        return new SimpleButton(item, click -> confirm());
    }

    private Button buildDeclineButton() {
        TemplateItem decline = config.items().get("decline");
        Material mat = Material.RED_STAINED_GLASS_PANE;
        String name = "<red>✖ Annulla";
        List<String> lore = List.of();

        if (decline != null) {
            mat = Material.getMaterial(decline.material().toUpperCase());
            if (mat == null) mat = Material.RED_STAINED_GLASS_PANE;
            name = decline.name();
            lore = decline.lore();
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(C.mm(name));
            if (!lore.isEmpty()) meta.lore(lore.stream().map(C::mm).collect(Collectors.toList()));
        }
        item.setItemMeta(meta);

        return new SimpleButton(item, click -> {
            // Cancelling mid-charge would take the player out of a purchase that is still
            // running; the commands would deliver into a menu nobody is watching.
            if (state != State.READY) return;
            module.playSound(getPlayer(), config.cancelPurchaseSound());
            if (config.keepOpenAfterPurchase()) {
                module.openCategory(getPlayer(), category);
            } else {
                getPlayer().closeInventory();
            }
        });
    }

    /**
     * The confirm button's entire click handler. The state flip is the first thing that happens —
     * ahead of the balance read, the withdrawal and the reward commands — so the second, third
     * and fourth click of an autoclicker find a menu that is no longer READY and return without
     * doing anything. {@link ShopModule#claimPurchase} is the second lock behind it: even a click
     * that somehow arrives on a fresh code path cannot spend the same purchase id twice.
     */
    private void confirm() {
        if (state != State.READY) return;
        state = State.PROCESSING;

        if (!module.claimPurchase(purchaseId)) {
            state = State.DONE;
            return;
        }

        boolean bought = false;
        try {
            bought = purchase();
        } finally {
            state = bought ? State.DONE : State.READY;
            // A purchase that charged nothing must be retryable, and the id is single-use — so
            // the failed attempt hands its claim back.
            if (!bought) module.releasePurchase(purchaseId);
        }
    }

    /** @return true when the player was charged and the rewards were delivered. */
    private boolean purchase() {
        Player player = getPlayer();
        double totalCost = product.price() * quantity;

        if (totalCost > 0) {
            double balance = module.getBalance(player);
            if (balance < totalCost) {
                C.sendChatAndAction(player, config.messages().insufficientFunds());
                module.playSound(player, config.purchaseErrorSound());
                return false;
            }
            if (!module.takeMoney(player, totalCost)) {
                C.sendChatAndAction(player, config.messages().transactionError());
                module.playSound(player, config.purchaseErrorSound());
                return false;
            }
        }

        for (String cmd : product.commands()) {
            String processed = cmd
                    .replace("%player%", player.getName())
                    .replace("%player_name%", player.getName())
                    .replace("%amount%", String.valueOf(quantity));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
        }

        C.sendChatAndAction(player, config.messages().purchaseSuccess()
                .replace("<amount>", String.valueOf(quantity))
                .replace("<item>", resolveItemName()));
        module.playSound(player, config.confirmPurchaseSound());
        // Logged after the commands ran and the money is gone, so the dashboard only ever
        // shows purchases that actually went through.
        module.logPurchase(player, resolveItemName(), quantity, totalCost, category);

        if (config.keepOpenAfterPurchase()) {
            module.openCategory(player, category);
        } else {
            player.closeInventory();
        }
        return true;
    }
}
