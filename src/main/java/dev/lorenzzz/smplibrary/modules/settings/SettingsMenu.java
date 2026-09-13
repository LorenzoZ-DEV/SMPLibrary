package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.impl.SimpleButton;
import net.j4c0b3y.api.menu.layer.impl.BackgroundLayer;
import net.j4c0b3y.api.menu.layer.impl.ForegroundLayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The /settings GUI: a plain inventory built from {@code inventories/settings.yml}.
 *
 * <p>It replaces the Paper Dialog screen that used to serve this command. A dialog cannot be
 * closed the way players expect a menu to close, it comes with the client's own "leave server"
 * warning button, and every click had to burn a one-use click callback to be able to redraw —
 * so a settings screen, which is nothing but a grid of switches, is better off as an inventory.
 *
 * <p>Each button's icon is a supplier, not a fixed stack, so flipping a switch is
 * {@link Menu#update()}: the icon is rebuilt from the live toggle state and the player never
 * sees the menu close and reopen.
 */
public final class SettingsMenu extends Menu {

    private final SettingsConfig config;
    private final SettingsModule module;

    public SettingsMenu(SettingsConfig config, Player player, MenuHandler handler, SettingsModule module) {
        super(config.name(), MenuSize.of(config.rows()), player, handler);
        this.config = config;
        this.module = module;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        background.fill(new SimpleButton(fillItem(), click -> {}));

        int slots = getTotalSlots();
        Map<Integer, String> taken = new HashMap<>();
        for (Map.Entry<String, SettingsItemConfig> entry : config.items().entrySet()) {
            String key = entry.getKey();
            SettingsItemConfig item = entry.getValue();

            if (item.slot() >= slots) {
                C.warning("settings: '" + key + "' sits on slot " + item.slot()
                        + ", outside a " + config.rows() + "-row menu — entry skipped.");
                continue;
            }
            // An entry that belongs to a module which isn't running would be a button that
            // does nothing, so it isn't drawn at all.
            if (!module.isAvailable(item)) continue;

            // Two entries on one slot means one of them is simply invisible, and the file is
            // hand-edited: say which pair collided instead of quietly dropping a button.
            String other = taken.putIfAbsent(item.slot(), key);
            if (other != null) {
                C.warning("settings: '" + key + "' e '" + other + "' sono entrambi sullo slot "
                        + item.slot() + " — '" + key + "' non viene mostrato.");
                continue;
            }

            foreground.set(item.slot() % COLUMNS, item.slot() / COLUMNS,
                    new SimpleButton(() -> icon(key, item), click -> handleClick(key, item)));
        }
    }

    // ------------------------------------------------------------------ icons

    private ItemStack fillItem() {
        FillItemConfig fill = config.fillItem();
        ItemStack stack = new ItemStack(material(fill.material(), Material.GRAY_STAINED_GLASS_PANE));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(text(fill.name() == null ? " " : fill.name()));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack icon(String key, SettingsItemConfig item) {
        ItemConfig icon = item.item();
        boolean enabled = item.toggle() && module.isOn(getPlayer(), key, item);
        String state = enabled ? config.enabledLabel() : config.disabledLabel();

        ItemStack stack = new ItemStack(material(icon.material(), Material.STONE));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        if (!icon.name().isEmpty()) meta.displayName(text(icon.name().replace("%state%", state)));

        List<Component> lore = new ArrayList<>();
        boolean statePlaced = icon.name().contains("%state%");
        for (String line : icon.lore()) {
            if (line.contains("%state%")) statePlaced = true;
            lore.add(text(line.replace("%state%", state)));
        }
        // Nothing in the configured text asked for the state, so it goes at the bottom —
        // a switch has to say which way it is pointing.
        if (item.toggle() && !statePlaced) {
            lore.add(text(""));
            lore.add(text(" &8» &7Stato: " + state));
        }
        if (!lore.isEmpty()) meta.lore(lore);

        // Enabled switches glow, so the menu reads at a glance without opening any tooltip.
        if (icon.glow() || enabled) meta.setEnchantmentGlintOverride(true);

        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Item text, with the italics vanilla adds to every custom name and lore line turned off —
     * only where the configured text did not ask for italics itself.
     */
    private static Component text(String raw) {
        return C.mm(raw).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }

    // ----------------------------------------------------------------- clicks

    private void handleClick(String key, SettingsItemConfig item) {
        if (!item.toggle()) {
            module.runCommands(getPlayer(), key, item.commands());
            if (item.close()) close();
            return;
        }

        module.toggle(getPlayer(), key, item);
        // Redraw in place: the icon suppliers read the state that was just written.
        update();
    }
}
