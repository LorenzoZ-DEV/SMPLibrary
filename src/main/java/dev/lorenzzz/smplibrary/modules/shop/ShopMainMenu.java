package dev.lorenzzz.smplibrary.modules.shop;

import dev.lorenzzz.smplibrary.text.Text;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.Button;
import net.j4c0b3y.api.menu.button.impl.SimpleButton;
import net.j4c0b3y.api.menu.layer.impl.BackgroundLayer;
import net.j4c0b3y.api.menu.layer.impl.ForegroundLayer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ShopMainMenu extends Menu {

    private final ShopConfig config;
    private final ShopModule module;

    public ShopMainMenu(ShopConfig config, Player player, MenuHandler handler, ShopModule module) {
        super(config.mainInventory().title(), MenuSize.of(config.mainInventory().layout().size()), player, handler);
        this.config = config;
        this.module = module;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        List<String> layout = config.mainInventory().layout();
        int rows = layout.size();

        for (int row = 0; row < rows; row++) {
            String line = layout.get(row);
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                Button btn = switch (c) {
                    case 'X' -> fillerButton();
                    case 'O' -> null;
                    case '<' -> fillerButton();
                    case '>' -> fillerButton();
                    default -> null;
                };
                if (btn != null) foreground.set(col, row, btn);
            }
        }

        for (Map.Entry<String, CategoryConfig> entry : config.categories().entrySet()) {
            CategoryConfig cat = entry.getValue();
            if (!cat.enabled()) continue;
            if (cat.slot() < 0) continue;

            ItemStack icon = buildIcon(cat.icon());
            if (icon == null) continue;

            foreground.set(cat.slot() % 9, cat.slot() / 9,
                    new SimpleButton(icon, click -> module.openCategory(getPlayer(), cat)));
        }
    }

    private ItemStack buildIcon(IconConfig icon) {
        Material mat = Material.getMaterial(icon.type().toUpperCase());
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (!icon.displayName().isEmpty()) {
            meta.displayName(Text.mini(icon.displayName()));
        }
        if (icon.lore() != null && !icon.lore().isEmpty()) {
            meta.lore(icon.lore().stream().map(Text::mini).collect(Collectors.toList()));
        }
        item.setItemMeta(meta);
        return item;
    }

    private Button fillerButton() {
        TemplateItem filler = config.items().get("filler");
        if (filler == null) {
            return new SimpleButton(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), click -> {});
        }
        Material mat = Material.getMaterial(filler.material().toUpperCase());
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && !filler.name().isEmpty()) {
            meta.displayName(Text.mini(filler.name()));
        }
        if (meta != null && filler.lore() != null && !filler.lore().isEmpty()) {
            meta.lore(filler.lore().stream().map(Text::mini).collect(Collectors.toList()));
        }
        if (meta != null) item.setItemMeta(meta);
        return new SimpleButton(item, click -> {});
    }

}
