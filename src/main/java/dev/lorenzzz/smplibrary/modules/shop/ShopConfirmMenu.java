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
import java.util.stream.Collectors;

public class ShopConfirmMenu extends Menu {

    private final ShopConfig config;
    private final ProductConfig product;
    private final CategoryConfig category;
    private final ShopModule module;
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

        return new SimpleButton(item, click -> purchase());
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
            module.playSound(getPlayer(), config.cancelPurchaseSound());
            if (config.keepOpenAfterPurchase()) {
                module.openCategory(getPlayer(), category);
            } else {
                getPlayer().closeInventory();
            }
        });
    }

    private void purchase() {
        Player player = getPlayer();
        double totalCost = product.price() * quantity;

        if (totalCost > 0) {
            double balance = module.getBalance(player);
            if (balance < totalCost) {
                C.sendChatAndAction(player, config.messages().insufficientFunds());
                module.playSound(player, config.purchaseErrorSound());
                return;
            }
            if (!module.takeMoney(player, totalCost)) {
                C.sendChatAndAction(player, config.messages().transactionError());
                module.playSound(player, config.purchaseErrorSound());
                return;
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
    }
}
