package dev.lorenzzz.smplibrary.modules.shop;

import dev.lorenzzz.smplibrary.C;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.Button;
import net.j4c0b3y.api.menu.button.impl.SimpleButton;
import net.j4c0b3y.api.menu.layer.impl.BackgroundLayer;
import net.j4c0b3y.api.menu.layer.impl.ForegroundLayer;
import net.j4c0b3y.api.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ShopCategoryMenu extends Menu {

    private final ShopConfig config;
    private final CategoryConfig category;
    private final ShopModule module;
    private int page = 0;

    // ponytail: recomputed each populate(); kept on the instance so nav buttons
    // don't rescan the layout. Cleared+refilled in populate(), never null.
    private final List<int[]> productSlots = new ArrayList<>();
    private int totalPages = 1;

    public ShopCategoryMenu(ShopConfig config, CategoryConfig category, Player player, MenuHandler handler, ShopModule module) {
        super(category.inventoryName(), MenuSize.of(category.layout().size()), player, handler);
        this.config = config;
        this.category = category;
        this.module = module;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        populate();
    }

    /**
     * Re-places every foreground button for the current page. Menu.setup()
     * only runs once per open, so page changes must call this via rebuild().
     */
    private void populate() {
        ForegroundLayer fg = getForeground();
        productSlots.clear();
        List<int[]> navSlots = new ArrayList<>();

        List<String> layout = category.layout();
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                switch (c) {
                    case 'X' -> fg.set(col, row, fillerButton());
                    case '<' -> navSlots.add(new int[]{row, col, -1});
                    case '>' -> navSlots.add(new int[]{row, col, 1});
                    case 'O' -> productSlots.add(new int[]{row, col});
                    default -> {}
                }
            }
        }

        int perPage = productSlots.size();
        int total = category.products().size();
        totalPages = Math.max(1, (int) Math.ceil((double) total / Math.max(1, perPage)));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        for (int[] nav : navSlots) {
            fg.set(nav[1], nav[0], nav[2] < 0 ? backButton() : nextButton());
        }

        List<ProductConfig> products = List.copyOf(category.products().values());
        int start = page * perPage;
        int end = Math.min(start + perPage, total);
        for (int i = start; i < end; i++) {
            int[] pos = productSlots.get(i - start);
            fg.set(pos[1], pos[0], buildProductButton(products.get(i)));
        }
    }

    private void rebuild() {
        getForeground().clear();
        populate();
        update();
    }

    private Button buildProductButton(ProductConfig product) {
        Material mat = Material.getMaterial(product.material().toUpperCase());
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return new SimpleButton(item, click -> {});

        MessagesConfig msgs = config.messages();

        String name = product.displayName();
        if (name == null || name.isEmpty()) {
            name = mat.name().toLowerCase().replace("_", " ");
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        List<String> lore = new ArrayList<>();
        if (product.lore() != null) lore.addAll(product.lore());
        if (msgs.itemPrice() != null && !msgs.itemPrice().isEmpty()) {
            lore.add(msgs.itemPrice().replace("<price>", String.valueOf(product.price())));
        }
        if (msgs.itemAmount() != null && !msgs.itemAmount().isEmpty()) {
            lore.add(msgs.itemAmount());
        }
        if (msgs.itemClick() != null && !msgs.itemClick().isEmpty()) {
            lore.add(msgs.itemClick());
        }

        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(C::mm).collect(Collectors.toList()));
        }

        meta.displayName(C.mm(name));
        item.setItemMeta(meta);

        return new SimpleButton(item, click -> {
            module.playSound(getPlayer(), config.selectItemSound());
            module.openConfirm(getPlayer(), product, category);
        });
    }

    private Button fillerButton() {
        TemplateItem filler = config.items().get("filler");
        if (filler == null) return new SimpleButton(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), click -> {});
        Material mat = Material.getMaterial(filler.material().toUpperCase());
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack icon = new ItemStack(mat);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null && !filler.name().isEmpty()) meta.displayName(C.mm(filler.name()));
        if (meta != null && filler.lore() != null && !filler.lore().isEmpty())
            meta.lore(filler.lore().stream().map(C::mm).collect(Collectors.toList()));
        if (meta != null) icon.setItemMeta(meta);
        return new SimpleButton(icon, click -> {});
    }

    private Button backButton() {
        if (page > 0) {
            TemplateItem item = config.items().get("roll-back-page");
            if (item == null) return fillerButton();
            return new SimpleButton(buildTemplateItem(item), click -> {
                module.playSound(getPlayer(), config.navigatePageSound());
                page--;
                rebuild();
            });
        }

        TemplateItem item = config.items().get("back-page");
        if (item == null) return fillerButton();
        return new SimpleButton(buildTemplateItem(item), click -> {
            module.playSound(getPlayer(), config.backButtonSound());
            module.openMainMenu(getPlayer());
        });
    }

    private Button nextButton() {
        if (page < totalPages - 1) {
            TemplateItem item = config.items().get("next-page");
            if (item == null) return fillerButton();
            return new SimpleButton(buildTemplateItem(item), click -> {
                module.playSound(getPlayer(), config.navigatePageSound());
                page++;
                rebuild();
            });
        }
        return fillerButton();
    }

    private ItemStack buildTemplateItem(TemplateItem item) {
        Material mat = Material.getMaterial(item.material().toUpperCase());
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack icon = new ItemStack(mat);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null && !item.name().isEmpty()) meta.displayName(C.mm(item.name()));
        if (meta != null && item.lore() != null && !item.lore().isEmpty())
            meta.lore(item.lore().stream().map(C::mm).collect(Collectors.toList()));
        if (meta != null) icon.setItemMeta(meta);
        return icon;
    }
}