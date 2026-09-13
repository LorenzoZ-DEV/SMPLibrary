package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import io.papermc.paper.dialog.Dialog;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a player inside a dialog they are supposed to answer, across a disconnect.
 *
 * <p>Vanilla dialog screens carry a warning button — the danger symbol in the corner — that
 * opens a "leave server" prompt. It is the client's own escape hatch and there is no way to
 * take it away: Paper's {@code DialogBase.Builder} exposes only {@code externalTitle},
 * {@code canCloseWithEscape}, {@code pause}, {@code afterAction}, {@code body} and
 * {@code inputs}, and the disconnect itself is decided client-side. So this class does not
 * try to block the button. It blocks what the button is <em>for</em>: a player who quits from
 * a guarded dialog gets the same dialog back the moment they log in again, so relogging buys
 * them nothing.
 *
 * <p>A guarded dialog must be built with {@code canCloseWithEscape(false)} and must offer its
 * own way out — a cancel button that calls {@link #release(Player)}. Without one the only exit
 * left really would be the danger symbol, and the player would be stuck in a loop.
 *
 * <p>State lives in memory only. It survives a relog, which is the whole point; it does not
 * survive a server restart, and it should not — the dialog is rebuilt by a factory holding
 * live objects, and nothing about a half-answered form is worth carrying across a restart.
 *
 * <h2>Safety valves</h2>
 * Two, because a guard that never lets go is a way to lock a player out of the server:
 * {@code max-reopens} caps how many times one pending dialog can come back, and
 * {@code expire-seconds} drops it once it is simply old. Players with
 * {@code smplibrary.dialogguard.bypass} are never re-prompted.
 */
public final class DialogGuard implements Manager, Listener {

    /** Permission that opts a player out of the re-open on join. */
    public static final String BYPASS_PERMISSION = "smplibrary.dialogguard.bypass";

    private static final String CONFIG_PATH = "DIALOG-GUARD";

    /** Rebuilds the dialog for a player. Called again on every re-open, never cached. */
    @FunctionalInterface
    public interface DialogFactory {
        Dialog build(Player player);
    }

    /** One armed dialog, waiting for an answer. */
    private static final class Pending {
        private final String id;
        private final DialogFactory factory;
        private final long armedAt = System.currentTimeMillis();
        private int reopens;

        private Pending(String id, DialogFactory factory) {
            this.id = id;
            this.factory = factory;
        }
    }

    private final Smp_library plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private long reopenDelayTicks = 20L;
    private int maxReopens = 5;
    private long expireMillis = 600_000L;

    public DialogGuard(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "dialog-guard"; }

    @Override
    public void enable() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection(CONFIG_PATH);
        if (cfg != null) {
            enabled = cfg.getBoolean("enabled", true);
            reopenDelayTicks = Math.max(1, cfg.getInt("reopen-delay-ticks", 20));
            maxReopens = Math.max(1, cfg.getInt("max-reopens", 5));
            expireMillis = Math.max(30, cfg.getInt("expire-seconds", 600)) * 1000L;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        pending.clear();
    }

    // ------------------------------------------------------------------ api

    /**
     * Shows {@code factory}'s dialog and arms the guard: until {@link #release(Player)} is
     * called, quitting and logging back in brings the dialog straight back.
     *
     * @param id short name of the flow, for logs only
     */
    public void show(Player player, String id, DialogFactory factory) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> show(player, id, factory));
            return;
        }
        if (!player.isOnline()) return;

        Dialog dialog = factory.build(player);
        if (dialog == null) return;

        if (enabled && !player.hasPermission(BYPASS_PERMISSION)) {
            pending.put(player.getUniqueId(), new Pending(id, factory));
        }
        player.showDialog(dialog);
    }

    /** Disarms the guard: the dialog was answered, or the player took its way out. */
    public void release(Player player) {
        release(player.getUniqueId());
    }

    public void release(UUID playerId) {
        pending.remove(playerId);
    }

    /**
     * Disarms every dialog belonging to one flow. Modules call this when they shut down: the
     * factory they registered closes over module state, and re-opening it after a reload would
     * drop the player into a wizard whose listeners are gone.
     */
    public void releaseAll(String id) {
        pending.entrySet().removeIf(entry -> entry.getValue().id.equals(id));
    }

    /** Whether this player still owes an answer to a guarded dialog. */
    public boolean isPending(UUID playerId) {
        Pending armed = pending.get(playerId);
        if (armed == null) return false;
        if (isExpired(armed)) {
            pending.remove(playerId);
            return false;
        }
        return true;
    }

    /** Id of the flow the player is held in, or {@code null} when none. */
    public String pendingId(UUID playerId) {
        Pending armed = pending.get(playerId);
        return armed == null ? null : armed.id;
    }

    // -------------------------------------------------------------- re-open

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        Pending armed = pending.get(id);
        if (armed == null) return;

        if (isExpired(armed) || player.hasPermission(BYPASS_PERMISSION)) {
            pending.remove(id);
            return;
        }

        // The player has been sent back here often enough. Letting go beats locking someone
        // out of the server over a dialog their client may simply refuse to render.
        if (armed.reopens >= maxReopens) {
            pending.remove(id);
            C.warning("DialogGuard: giving up on '" + armed.id + "' for " + player.getName()
                    + " after " + armed.reopens + " re-opens");
            tell(player, "dialog-guard.gave-up", Map.of());
            return;
        }
        armed.reopens++;

        // The client is still finishing the join sequence; a dialog sent this tick is dropped.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || pending.get(id) != armed) return;
            Dialog dialog = armed.factory.build(player);
            if (dialog == null) {
                pending.remove(id);
                return;
            }
            tell(player, "dialog-guard.reopened", Map.of("%attempt%", armed.reopens));
            player.showDialog(dialog);
        }, reopenDelayTicks);
    }

    private boolean isExpired(Pending armed) {
        return System.currentTimeMillis() - armed.armedAt >= expireMillis;
    }

    /** messages.yml is optional here: a missing manager (shutdown race) just means no text. */
    private void tell(Player player, String key, Map<String, ?> placeholders) {
        ManagerService managers = plugin.getManagers();
        if (managers == null || managers.messages() == null) return;
        managers.messages().send(player, key, placeholders);
    }
}
