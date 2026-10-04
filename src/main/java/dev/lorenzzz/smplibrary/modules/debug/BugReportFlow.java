package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.DialogGuard;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.text.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code /bugreport} wizard: dialog → book → chat → confirmation menu.
 *
 * <p>Four steps because each one fits a different kind of answer. The title and the category are
 * short and constrained, so they are a dialog with a text field and a picker. The description is
 * long-form, so it is a writable book — a chat prompt would lose the newlines and the player's
 * train of thought. The media link is a paste, which chat handles and a book does not. The last
 * step is a menu because it is a yes/no on something the player should read back first.
 *
 * <p>One session per player at a time, dropped on quit and expired by
 * {@link #expireStale(long)}, so a player who wanders off mid-report does not keep a book, a
 * cancelled chat line, and a stashed inventory slot forever.
 */
public final class BugReportFlow implements Listener {

    private static final int MAX_TITLE = 90;
    private static final int MAX_BODY = 4000;

    /** Where a player is in the wizard. */
    private enum Stage { BOOK, MEDIA, CONFIRM }

    private static final class Session {
        private final String type;
        private final String title;
        private final long startedAt = System.currentTimeMillis();
        private final ItemStack stashed;      // what the book displaced, put back afterwards
        private final int slot;
        private Stage stage = Stage.BOOK;
        private String body = "";
        private String media = "";

        private Session(String type, String title, ItemStack stashed, int slot) {
            this.type = type;
            this.title = title;
            this.stashed = stashed;
            this.slot = slot;
        }
    }

    /** Flow id the {@link DialogGuard} logs this dialog under. */
    private static final String GUARD_ID = "bugreport";

    private final Smp_library plugin;
    private final DebugModule module;
    private final MessagesManager messages;
    private final DialogGuard guard;
    private final NamespacedKey bookKey;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public BugReportFlow(Smp_library plugin, DebugModule module, MessagesManager messages,
                         DialogGuard guard) {
        this.plugin = plugin;
        this.module = module;
        this.messages = messages;
        this.guard = guard;
        this.bookKey = new NamespacedKey(plugin, "bugreport_book");
    }

    public boolean isBusy(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    // ------------------------------------------------------------- step 1: dialog

    /** Opens the title + category dialog under the {@link DialogGuard}. */
    public void start(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            messages.send(player, "bugreport.already-running", Map.of());
            return;
        }
        if (guard == null) {                       // guard-less fallback: still usable
            player.showDialog(buildStartDialog(player));
            return;
        }
        guard.show(player, GUARD_ID, this::buildStartDialog);
    }

    /**
     * The step-1 dialog. Built fresh on every call because the guard re-builds it when it
     * re-opens after a relog, and the report types can have been reloaded in between.
     *
     * <p>{@code canCloseWithEscape(false)} on purpose: this is the dialog the guard holds the
     * player in, so it has to be answered rather than dismissed. That makes the "Annulla"
     * button the way out — without it the only exit would be the client's danger symbol, which
     * the guard would immediately undo on the next login.
     */
    private Dialog buildStartDialog(Player player) {
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        List<String> types = module.reportTypes();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    type, Component.text(type), i == 0));
        }

        ActionButton confirm = ActionButton.builder(Text.mini("<green><bold>Continua</bold></green>"))
                .tooltip(Component.text("Apre il libro per la descrizione"))
                .width(150)
                .action(DialogAction.customClick(
                        (view, audience) -> onDialogSubmit(player,
                                view.getText("titolo"), view.getText("tipo")),
                        ClickCallback.Options.builder().uses(1).build()))
                .build();

        ActionButton abort = ActionButton.builder(Text.mini("<red>Annulla</red>"))
                .tooltip(Component.text("Chiude la segnalazione senza inviarla"))
                .width(150)
                .action(DialogAction.customClick(
                        (view, audience) -> onDialogAbort(player),
                        ClickCallback.Options.builder().uses(1).build()))
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.mini("<gradient:#ff6b6b:#ffa94d><bold>Segnala un bug</bold></gradient>"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                        "Dai un titolo al problema e scegli di che tipo e'. "
                                                + "Dopo potrai descriverlo in un libro.")
                                .color(NamedTextColor.GRAY))))
                        .inputs(List.of(
                                DialogInput.text("titolo", Component.text("Titolo"))
                                        .width(300)
                                        .maxLength(MAX_TITLE)
                                        .build(),
                                DialogInput.singleOption("tipo", Component.text("Tipo di bug"), options)
                                        .width(300)
                                        .build()))
                        .build())
                .type(DialogType.multiAction(List.of(confirm, abort))
                        .columns(2)
                        .build()));
    }

    /** The player's own way out of the guarded dialog. */
    private void onDialogAbort(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> onDialogAbort(player));
            return;
        }
        releaseGuard(player);
        if (player.isOnline()) messages.send(player, "bugreport.dialog-cancelled", Map.of());
    }

    private void releaseGuard(Player player) {
        if (guard != null) guard.release(player);
    }

    private void onDialogSubmit(Player player, String rawTitle, String rawType) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> onDialogSubmit(player, rawTitle, rawType));
            return;
        }
        if (!player.isOnline()) return;
        if (sessions.containsKey(player.getUniqueId())) {
            releaseGuard(player);
            return;
        }

        String title = rawTitle == null ? "" : rawTitle.trim();
        if (title.isEmpty()) {
            // The client closed the dialog on the click; put it straight back rather than
            // making the player retype the command. The guard stays armed either way.
            messages.send(player, "bugreport.title-empty", Map.of());
            if (guard != null) guard.show(player, GUARD_ID, this::buildStartDialog);
            else player.showDialog(buildStartDialog(player));
            return;
        }
        String type = rawType == null || rawType.isBlank() ? module.reportTypes().get(0) : rawType.trim();

        // Past this point the wizard leaves the dialog and lives in the book, so the guard
        // has nothing left to hold: the session (and its own quit/timeout handling) takes over.
        releaseGuard(player);

        int slot = player.getInventory().getHeldItemSlot();
        ItemStack stashed = player.getInventory().getItem(slot);
        sessions.put(player.getUniqueId(), new Session(type, title, stashed, slot));

        player.getInventory().setItem(slot, buildBook(title));
        messages.send(player, "bugreport.write-book", Map.of("%title%", title));
    }

    private ItemStack buildBook(String title) {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(Text.mini("<gold>Segnalazione: <white>" + title));
        meta.lore(List.of(
                Text.mini("<gray>Scrivi qui cosa succede, passo per passo."),
                Text.mini("<gray>Poi chiudi il libro con <white>Fatto<gray>.")));
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        return book;
    }

    // --------------------------------------------------------------- step 2: book

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.stage != Stage.BOOK) return;

        ItemStack held = player.getInventory().getItem(session.slot);
        if (!isReportBook(held)) return;

        BookMeta meta = event.getNewBookMeta();
        StringBuilder body = new StringBuilder();
        for (Component page : meta.pages()) {
            String text = PlainTextComponentSerializer.plainText().serialize(page).trim();
            if (text.isEmpty()) continue;
            if (!body.isEmpty()) body.append('\n');
            body.append(text);
        }

        if (body.isEmpty()) {
            messages.send(player, "bugreport.body-empty", Map.of());
            return;
        }

        session.body = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body.toString();
        session.stage = Stage.MEDIA;

        // The book UI is still closing this tick; give the slot back on the next one.
        Bukkit.getScheduler().runTask(plugin, () -> {
            returnSlot(player, session);
            if (player.isOnline()) messages.send(player, "bugreport.ask-media", Map.of());
        });
    }

    private boolean isReportBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITABLE_BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(bookKey, PersistentDataType.BYTE);
    }

    // --------------------------------------------------------------- step 3: chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.stage != Stage.MEDIA) return;

        // Cancelled before every other listener sees it: this line is an answer to us, not chat.
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        Bukkit.getScheduler().runTask(plugin, () -> handleMediaAnswer(player, session, raw));
    }

    private void handleMediaAnswer(Player player, Session session, String raw) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) return;

        String answer = raw.toLowerCase(Locale.ROOT);
        if (answer.equals("annulla") || answer.equals("cancel")) {
            cancel(player, "bugreport.cancelled");
            return;
        }

        if (answer.equals("salta") || answer.equals("skip") || answer.equals("no")) {
            session.media = "";
        } else if (isAllowedLink(raw)) {
            session.media = raw;
        } else {
            messages.send(player, "bugreport.bad-media", Map.of(
                    "%hosts%", String.join(", ", module.allowedMediaHosts())));
            return;   // stay in this step; the player can paste another link
        }

        session.stage = Stage.CONFIRM;
        module.openConfirmMenu(player, session.type, session.title, session.body, session.media);
    }

    /**
     * A link is accepted only when it is http(s) and its host is one of the configured sharing
     * sites. Anything else is either a typo or a place we do not want admins clicking through to.
     */
    private boolean isAllowedLink(String raw) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return false;
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return false;

            String lower = host.toLowerCase(Locale.ROOT);
            for (String allowed : module.allowedMediaHosts()) {
                String candidate = allowed.toLowerCase(Locale.ROOT).trim();
                if (candidate.isEmpty()) continue;
                if (lower.equals(candidate) || lower.endsWith('.' + candidate)) return true;
            }
            return false;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    // ------------------------------------------------------------ step 4: outcome

    /** Called by the confirmation menu once the player accepts. */
    public BugReport commit(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return null;
        returnSlot(player, session);

        String context = player.getWorld().getName() + " @ "
                + player.getLocation().getBlockX() + ", "
                + player.getLocation().getBlockY() + ", "
                + player.getLocation().getBlockZ()
                + " | client " + player.getProtocolVersion()
                + " | ping " + player.getPing() + "ms";

        return new BugReport("", player.getName(), player.getUniqueId().toString(),
                session.type, session.title, session.body, session.media, context,
                System.currentTimeMillis(), BugReport.OPEN);
    }

    /** Called by the confirmation menu when the player backs out, and by the timeout sweep. */
    public void cancel(Player player, String messageKey) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        returnSlot(player, session);
        if (player.isOnline() && messageKey != null) messages.send(player, messageKey, Map.of());
    }

    /** Puts the displaced item back, but only over our own book. */
    private void returnSlot(Player player, Session session) {
        if (!player.isOnline()) return;
        ItemStack current = player.getInventory().getItem(session.slot);
        if (!isReportBook(current)) return;   // the player moved it; leave their inventory alone
        player.getInventory().setItem(session.slot, session.stashed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    /** Drops sessions older than {@code maxAgeMillis}; run on a repeating task. */
    public void expireStale(long maxAgeMillis) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (now - entry.getValue().startedAt < maxAgeMillis) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) cancel(player, "bugreport.timeout");
            else sessions.remove(entry.getKey());
        }
    }

    public void clear() {
        // Drop the armed dialogs too: after a reload their factory would rebuild a wizard
        // whose listeners no longer exist.
        if (guard != null) guard.releaseAll(GUARD_ID);
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) cancel(player, null);
            else sessions.remove(id);
        }
    }
}
