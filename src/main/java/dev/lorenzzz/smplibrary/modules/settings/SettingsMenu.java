package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.impl.SimpleButton;
import net.j4c0b3y.api.menu.layer.impl.BackgroundLayer;
import net.j4c0b3y.api.menu.layer.impl.ForegroundLayer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.stream.Collectors;

public class SettingsMenu extends Menu {

    private final SettingsConfig config;

    public SettingsMenu(SettingsConfig config, Player player, MenuHandler handler) {
        super(config.name(), MenuSize.of(config.size() / 9), player, handler);
        this.config = config;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        ItemStack fill = buildFill();
        int rows = config.size() / 9;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                SettingsItemConfig ic = findItem(slot);
                if (ic != null) {
                    ItemStack stack = buildStack(ic.item());
                    if (stack != null) {
                        foreground.set(col, row, new SimpleButton(stack, click -> {
                            if (ic.close()) close(true);
                            String name = getPlayer().getName();
                            for (String cmd : ic.commands()) {
                                Bukkit.dispatchCommand(getPlayer(), cmd.replace("%player%", name));
                            }
                        }));
                    }
                } else if (fill != null) {
                    foreground.set(col, row, new SimpleButton(fill, click -> {}));
                }
            }
        }
    }

    private SettingsItemConfig findItem(int slot) {
        for (SettingsItemConfig ic : config.items().values()) {
            if (ic.slot() == slot) return ic;
        }
        return null;
    }

    private ItemStack buildStack(ItemConfig cfg) {
        Material mat = Material.getMaterial(cfg.material().toUpperCase());
        if (mat == null) mat = Material.STONE;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (!cfg.name().isEmpty()) meta.displayName(C.mm(cfg.name()));
        if (cfg.lore() != null && !cfg.lore().isEmpty()) {
            meta.lore(cfg.lore().stream().map(C::mm).collect(Collectors.toList()));
        }
        if (cfg.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildFill() {
        FillItemConfig fc = config.fillItem();
        if (fc == null) return null;
        Material mat = Material.getMaterial(fc.material().toUpperCase());
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && !fc.name().isEmpty()) meta.displayName(C.mm(fc.name()));
        if (meta != null) stack.setItemMeta(meta);
        return stack;
    }
}
