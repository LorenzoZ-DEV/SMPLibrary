package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.C;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.Button;
import net.j4c0b3y.api.menu.button.impl.SimpleButton;
import net.j4c0b3y.api.menu.layer.impl.BackgroundLayer;
import net.j4c0b3y.api.menu.layer.impl.ForegroundLayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The last step of {@code /bugreport}: the player reads back what they are about to file and
 * confirms it. Nothing is written until the green button is clicked.
 */
public final class BugReportConfirmMenu extends Menu {

    private static final int PREVIEW_LINE = 42;   // wrap width for the description in the lore

    private final DebugModule module;
    private final String type;
    private final String title;
    private final String body;
    private final String media;

    public BugReportConfirmMenu(Player player, MenuHandler handler, DebugModule module,
                                String type, String title, String body, String media) {
        super("Conferma segnalazione", MenuSize.of(3), player, handler);
        this.module = module;
        this.type = type;
        this.title = title;
        this.body = body;
        this.media = media;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(Component.text(" "));
            filler.setItemMeta(fillerMeta);
        }
        background.fill(new SimpleButton(filler, click -> {}));

        foreground.set(4, 1, summaryButton());
        foreground.set(2, 2, acceptButton());
        foreground.set(6, 2, declineButton());
    }

    private Button summaryButton() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(C.mm("<gold><bold>" + escape(title) + "</bold>"));

            List<Component> lore = new ArrayList<>();
            lore.add(C.mm("<gray>Tipo: <white>" + escape(type)));
            lore.add(Component.empty());
            for (String line : wrap(body)) lore.add(C.mm("<gray>" + escape(line)));
            lore.add(Component.empty());
            lore.add(C.mm(media.isBlank()
                    ? "<dark_gray>Nessun media allegato"
                    : "<aqua>" + escape(media)));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return new SimpleButton(item, click -> {});
    }

    private Button acceptButton() {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(C.mm("<green><bold>Invia la segnalazione</bold>"));
            meta.lore(List.of(C.mm("<gray>Verra' vista dallo staff sulla dashboard.")));
            item.setItemMeta(meta);
        }
        return new SimpleButton(item, click -> {
            Player player = getPlayer();
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            module.submitReport(player);
        });
    }

    private Button declineButton() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(C.mm("<red><bold>Annulla</bold>"));
            meta.lore(List.of(C.mm("<gray>Niente verra' salvato.")));
            item.setItemMeta(meta);
        }
        return new SimpleButton(item, click -> {
            Player player = getPlayer();
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            module.cancelReport(player);
        });
    }

    /** First few wrapped lines of the description — the lore is a preview, not the whole book. */
    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        String flat = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        int from = 0;
        while (from < flat.length() && lines.size() < 6) {
            int to = Math.min(flat.length(), from + PREVIEW_LINE);
            if (to < flat.length()) {
                int space = flat.lastIndexOf(' ', to);
                if (space > from) to = space;
            }
            lines.add(flat.substring(from, to).trim());
            from = to;
        }
        if (from < flat.length()) lines.add("…");
        return lines;
    }

    /** MiniMessage is applied to these strings, so a player-typed '<' must not open a tag. */
    private static String escape(String text) {
        return text.replace("<", "\\<");
    }
}
