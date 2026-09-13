package dev.lorenzzz.smplibrary.modules.gui;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.hook.SavedPoint;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.Button;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The homes screen and the warps screen — the same class twice, because they differ only in the
 * {@link ScreenConfig} they are handed.
 *
 * <p>The entries are a plain {@link SavedPoint} list read once before the menu opens: HuskHomes
 * answers off the server thread, and an inventory may not be built there. Paging therefore never
 * touches HuskHomes again, it only re-slices the list it already has.
 */
final class PositionMenu extends Menu {

    private static final char SLOT_ENTRY = 'O';
    private static final char SLOT_FILL = 'X';
    private static final char SLOT_PREVIOUS = '<';
    private static final char SLOT_NEXT = '>';
    private static final char SLOT_INFO = 'I';
    private static final char SLOT_ACTION = 'A';

    private final GuiConfig gui;
    private final ScreenConfig screen;
    private final List<SavedPoint> points;
    private final GuiModule module;

    /** Where entries go, in layout order. Recomputed by {@link #populate()}, never null. */
    private final List<int[]> entrySlots = new ArrayList<>();

    private int page;
    private int totalPages = 1;

    PositionMenu(GuiConfig gui, ScreenConfig screen, List<SavedPoint> points,
                 Player player, MenuHandler handler, GuiModule module) {
        super(title(screen, points, player, 1, pages(screen, points)),
                MenuSize.of(screen.layout().size()), player, handler);
        this.gui = gui;
        this.screen = screen;
        this.points = points;
        this.module = module;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        populate();
    }

    // ------------------------------------------------------------------ build

    /**
     * Re-places every foreground button for the current page. {@link #setup} runs once per open,
     * so a page change has to come back through here.
     */
    private void populate() {
        ForegroundLayer fg = getForeground();
        entrySlots.clear();

        List<int[]> filler = new ArrayList<>();
        List<int[]> previous = new ArrayList<>();
        List<int[]> next = new ArrayList<>();
        List<int[]> info = new ArrayList<>();
        List<int[]> action = new ArrayList<>();

        // Nothing is drawn during the walk: the page counters the icons interpolate are only
        // known once every 'O' has been counted.
        List<String> layout = screen.layout();
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            for (int col = 0; col < line.length() && col < COLUMNS; col++) {
                switch (line.charAt(col)) {
                    case SLOT_FILL -> filler.add(new int[]{col, row});
                    case SLOT_ENTRY -> entrySlots.add(new int[]{col, row});
                    case SLOT_PREVIOUS -> previous.add(new int[]{col, row});
                    case SLOT_NEXT -> next.add(new int[]{col, row});
                    case SLOT_INFO -> info.add(new int[]{col, row});
                    case SLOT_ACTION -> action.add(new int[]{col, row});
                    default -> { /* a space: the slot stays empty */ }
                }
            }
        }

        int perPage = Math.max(1, entrySlots.size());
        totalPages = Math.max(1, (int) Math.ceil((double) points.size() / perPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        for (int[] slot : filler) fg.set(slot[0], slot[1], decorative("filler"));
        for (int[] slot : previous) fg.set(slot[0], slot[1], pageButton(-1));
        for (int[] slot : next) fg.set(slot[0], slot[1], pageButton(1));
        for (int[] slot : info) fg.set(slot[0], slot[1], decorative("info"));
        for (int[] slot : action) fg.set(slot[0], slot[1], actionButton());

        if (points.isEmpty()) {
            // Nothing to list: one explanatory item in the middle of the entry area, so the
            // screen never opens as an empty box the player has to interpret.
            if (!entrySlots.isEmpty()) {
                int[] middle = entrySlots.get(entrySlots.size() / 2);
                fg.set(middle[0], middle[1], decorative("empty"));
            }
            return;
        }

        int start = page * perPage;
        int end = Math.min(start + perPage, points.size());
        for (int i = start; i < end; i++) {
            int[] slot = entrySlots.get(i - start);
            fg.set(slot[0], slot[1], entryButton(points.get(i), i));
        }
    }

    private void turnTo(int newPage) {
        if (newPage < 0 || newPage >= totalPages || newPage == page) return;
        page = newPage;
        module.playPage(getPlayer());

        getForeground().clear();
        populate();

        // The title carries the page number, so it has to be reissued — setTitle rebuilds the
        // inventory, hence before update(), which is what fills the new one.
        String retitled = title(screen, points, getPlayer(), page + 1, totalPages);
        if (!retitled.equals(getTitle())) setTitle(retitled);
        update();
    }

    // ---------------------------------------------------------------- buttons

    private Button entryButton(SavedPoint point, int index) {
        IconConfig icon = screen.item();
        ItemStack stack = render(icon, placeholders(point, index));
        return new SimpleButton(stack, click -> module.teleport(getPlayer(), screen, point, this));
    }

    /**
     * A page arrow, or the {@code no-page} filler when there is nothing in that direction —
     * an arrow that does nothing is worse than no arrow.
     */
    private Button pageButton(int direction) {
        int target = page + direction;
        if (target < 0 || target >= totalPages) return decorative("no-page");

        IconConfig icon = gui.items().get(direction < 0 ? "previous-page" : "next-page");
        if (icon == null) return decorative("no-page");

        ItemStack stack = render(icon, pageContext(target + 1));
        return new SimpleButton(stack, click -> turnTo(target));
    }

    /** The {@code A} slot: runs its configured command as the player, and nothing else. */
    private Button actionButton() {
        IconConfig icon = gui.items().get("action");
        if (icon == null) return decorative("filler");

        ItemStack stack = render(icon, pageContext(page + 1));
        return new SimpleButton(stack, click -> {
            if (icon.command() == null || icon.command().isBlank()) return;
            module.runCommand(getPlayer(), icon.command());
            if (icon.close()) close();
        });
    }

    /** An icon that is only there to be looked at. */
    private Button decorative(String key) {
        IconConfig icon = gui.items().get(key);
        if (icon == null) {
            return new SimpleButton(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), click -> {});
        }
        return new SimpleButton(render(icon, pageContext(page + 1)), click -> {});
    }

    // ----------------------------------------------------------------- render

    private ItemStack render(IconConfig icon, Map<String, String> values) {
        ItemStack stack = new ItemStack(material(icon.material(), Material.STONE));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = icon.name() == null ? "" : icon.name();
        if (!name.isEmpty()) meta.displayName(text(fill(name, values)));

        List<String> lore = icon.lore();
        if (lore != null && !lore.isEmpty()) {
            List<Component> rendered = new ArrayList<>(lore.size());
            for (String line : lore) rendered.add(text(fill(line == null ? "" : line, values)));
            meta.lore(rendered);
        }
        if (icon.glow()) meta.setEnchantmentGlintOverride(true);

        stack.setItemMeta(meta);
        return stack;
    }

    private Map<String, String> placeholders(SavedPoint point, int index) {
        Map<String, String> values = pageContext(page + 1);
        values.put("%name%", point.name());
        values.put("%description%", point.description() == null || point.description().isBlank()
                ? screen.noDescription() : point.description());
        values.put("%world%", point.world());
        values.put("%server%", point.server());
        values.put("%x%", point.blockX());
        values.put("%y%", point.blockY());
        values.put("%z%", point.blockZ());
        values.put("%owner%", point.owner());
        values.put("%index%", String.valueOf(index + 1));
        values.put("%visibility%", point.isPublic() ? screen.publicLabel() : screen.privateLabel());
        return values;
    }

    private Map<String, String> pageContext(int shownPage) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("%page%", String.valueOf(shownPage));
        values.put("%pages%", String.valueOf(totalPages));
        values.put("%count%", String.valueOf(points.size()));
        values.put("%player%", getPlayer().getName());
        return values;
    }

    private static String fill(String raw, Map<String, String> values) {
        String out = raw;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        return out;
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

    // ------------------------------------------------------------------ title

    /** Static: the title has to be ready for {@code super(...)}, before any field exists. */
    private static String title(ScreenConfig screen, List<SavedPoint> points, Player player,
                               int shownPage, int pages) {
        String raw = screen.title() == null ? "" : screen.title();
        return raw.replace("%page%", String.valueOf(shownPage))
                .replace("%pages%", String.valueOf(pages))
                .replace("%count%", String.valueOf(points.size()))
                .replace("%player%", player.getName());
    }

    /** Page count for the first title, computed the same way {@link #populate()} does. */
    private static int pages(ScreenConfig screen, List<SavedPoint> points) {
        int perPage = 0;
        for (String row : screen.layout()) {
            for (int i = 0; i < row.length() && i < COLUMNS; i++) {
                if (row.charAt(i) == SLOT_ENTRY) perPage++;
            }
        }
        return Math.max(1, (int) Math.ceil((double) points.size() / Math.max(1, perPage)));
    }
}
