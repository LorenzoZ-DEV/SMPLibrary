package dev.lorenzzz.smplibrary.modules.gui;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.hook.HuskHomesHook;
import dev.lorenzzz.smplibrary.hook.SavedPoint;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The homes and warps screens, drawn from {@code inventories/gui.yml} over HuskHomes' data.
 *
 * <p>Nothing here owns a home or a warp: HuskHomes creates, moves and deletes them, and the
 * teleport is its own {@code /home} / {@code /warp} run as the player, so warmups, cooldowns,
 * permissions and messages stay exactly where players already know them from. This module is a
 * front end, which is also why it degrades to nothing at all when HuskHomes is absent.
 *
 * <h2>Why the commands are named this way</h2>
 * {@code /homes} and {@code /warps} belong to HuskHomes; registering them here would be a fight
 * over the same label with the plugin that owns the data. So the module registers
 * {@code /homegui} and {@code /warpgui}, and — when {@code intercept.enabled} is on — quietly
 * takes over the argument-less {@code /homes} and {@code /warps} a player types out of habit.
 */
public final class GuiModule extends AbstractModule implements Listener {

    private static final String HOMES_COMMAND = "homegui";
    private static final String WARPS_COMMAND = "warpgui";

    private MenuHandler menuHandler;
    private GuiConfigLoader configLoader;

    private HuskHomesHook hook;
    private long nextHookAttempt;
    private boolean missingLogged;

    private static final long HOOK_RETRY_MS = 15_000L;

    public GuiModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "gui"; }

    @Override
    public Map<String, Object> defaults() {
        return Map.of("enabled", true);
    }

    @Override
    public void onEnable() {
        this.menuHandler = managers.menuHandler();
        this.configLoader = new GuiConfigLoader(plugin);
        configLoader.load();

        registerCommand(HOMES_COMMAND, this::openHomes);
        registerCommand(WARPS_COMMAND, this::openWarps);
        listen(this);

        C.info("GUI module enabled — /" + HOMES_COMMAND + " e /" + WARPS_COMMAND + ", "
                + (huskHomesPresent() ? "HuskHomes trovato." : "in attesa di HuskHomes."));
    }

    /**
     * Re-reads gui.yml. The dashboard calls this after writing the file, so an edit made from
     * the site is live on the next {@code /homegui} instead of on the next restart.
     */
    public void reloadConfig() {
        if (configLoader != null) configLoader.reload();
    }

    private void registerCommand(String name, java.util.function.Consumer<Player> action) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            C.warning("Command '" + name + "' not found in plugin.yml");
            return;
        }
        command.setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof Player player) {
                action.accept(player);
            } else {
                C.send(sender, "&cOnly players.");
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ open

    public void openHomes(Player player) {
        GuiConfig config = gui();
        if (config == null) return;
        open(player, config, config.homes(), hook -> hook.homes(player), "home");
    }

    public void openWarps(Player player) {
        GuiConfig config = gui();
        if (config == null) return;
        open(player, config, config.warps(),
                hook -> hook.warps(player, config.warps().filterByPermission()), "warp");
    }

    /**
     * Reads the entries, then opens the screen on the server thread.
     *
     * <p>HuskHomes completes its futures on its own database threads, so the hop back matters:
     * building an inventory off the main thread is how you get a plugin that works on a test
     * server and corrupts inventories on a busy one.
     */
    private void open(Player player, GuiConfig config, ScreenConfig screen,
                      Function<HuskHomesHook, CompletableFuture<List<SavedPoint>>> source, String what) {
        if (!screen.enabled()) {
            C.send(player, "&cQuesta schermata è disattivata.");
            return;
        }

        HuskHomesHook live = hook();
        if (live == null) {
            C.send(player, "&cLe " + what + " non sono disponibili: "
                    + HuskHomesHook.PLUGIN_NAME + " non è attivo.");
            return;
        }

        CompletableFuture<List<SavedPoint>> entries;
        try {
            entries = source.apply(live);
        } catch (Throwable failed) {
            dropHook(failed);
            C.send(player, "&cNon riesco a leggere le " + what + " in questo momento.");
            return;
        }

        entries.whenComplete((points, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (error != null) {
                C.warning("GUI: lettura " + what + " fallita: " + describe(error));
                C.send(player, "&cNon riesco a leggere le " + what + " in questo momento.");
                return;
            }
            show(player, config, screen, points == null ? List.of() : points);
        }));
    }

    private void show(Player player, GuiConfig config, ScreenConfig screen, List<SavedPoint> points) {
        if (points.isEmpty()) {
            config.emptySound().play(player);
        } else {
            config.openSound().play(player);
        }
        new PositionMenu(config, screen, points, player, menuHandler, this).open();
    }

    // ---------------------------------------------------------- menu callbacks

    /** A click on an entry: close, then let HuskHomes do the teleport its own way. */
    void teleport(Player player, ScreenConfig screen, SavedPoint point, Menu menu) {
        GuiConfig config = gui();
        if (config != null) config.teleportSound().play(player);
        menu.close();

        String command = screen.command();
        if (command == null || command.isBlank()) return;
        runCommand(player, command.replace("%name%", point.name()));
    }

    void playPage(Player player) {
        GuiConfig config = gui();
        if (config != null) config.pageSound().play(player);
    }

    /** Runs a configured command as the player. One that throws must not break the menu. */
    void runCommand(Player player, String command) {
        if (command == null || command.isBlank()) return;
        try {
            Bukkit.dispatchCommand(player, command.replace("%player%", player.getName()));
        } catch (Exception e) {
            C.warning("gui: comando '" + command + "' fallito: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------- interception

    /**
     * Turns the HuskHomes commands listed in gui.yml into these screens.
     *
     * <p>Only when the player typed nothing after the command: {@code /homes} is "show me mine"
     * and belongs here, while {@code /homes Someone} is the staff lookup and stays HuskHomes'.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        GuiConfig config = gui();
        if (config == null || !config.intercept().enabled()) return;

        String typed = event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT);
        // "huskhomes:homes" and friends aim at the same command.
        int colon = typed.indexOf(':');
        int firstSpace = typed.indexOf(' ');
        if (colon >= 0 && (firstSpace < 0 || colon < firstSpace)) typed = typed.substring(colon + 1);

        Player player = event.getPlayer();
        if (matches(typed, config.intercept().homes())) {
            if (!player.hasPermission("smplibrary.gui.homes")) return;
            event.setCancelled(true);
            openHomes(player);
        } else if (matches(typed, config.intercept().warps())) {
            if (!player.hasPermission("smplibrary.gui.warps")) return;
            event.setCancelled(true);
            openWarps(player);
        }
    }

    /**
     * True when what the player typed is exactly one of the configured commands.
     *
     * <p>Exact, not "starts with": an entry of {@code home list} has to match {@code /home list}
     * and leave {@code /home listone} — somebody's home actually called that — alone.
     */
    private static boolean matches(String typed, List<String> configured) {
        for (String candidate : configured) {
            if (typed.equals(candidate)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------- hook

    private GuiConfig gui() {
        GuiConfig config = configLoader == null ? null : configLoader.get();
        if (config == null) C.warning("gui.yml non è stato caricato — la GUI non si apre.");
        return config;
    }

    private static boolean huskHomesPresent() {
        return Bukkit.getPluginManager().isPluginEnabled(HuskHomesHook.PLUGIN_NAME);
    }

    /**
     * The HuskHomes handle, attached on first use and retried on a timer: load order is not ours
     * to pick, and the class must not be touched at all while the plugin is absent.
     */
    private HuskHomesHook hook() {
        if (hook != null) return hook;

        long now = System.currentTimeMillis();
        if (now < nextHookAttempt) return null;
        nextHookAttempt = now + HOOK_RETRY_MS;

        if (!huskHomesPresent()) {
            if (!missingLogged) {
                missingLogged = true;
                C.warning("GUI: " + HuskHomesHook.PLUGIN_NAME + " non è attivo — le schermate"
                        + " home/warp non hanno dati da mostrare.");
            }
            return null;
        }

        try {
            hook = new HuskHomesHook();
            missingLogged = false;
            C.info("GUI agganciata a " + HuskHomesHook.PLUGIN_NAME + " v" + hook.version() + ".");
        } catch (Throwable failed) {
            C.warning("GUI: aggancio a " + HuskHomesHook.PLUGIN_NAME + " fallito ("
                    + describe(failed) + ").");
        }
        return hook;
    }

    private void dropHook(Throwable failed) {
        hook = null;
        nextHookAttempt = System.currentTimeMillis() + HOOK_RETRY_MS;
        C.warning("GUI: chiamata a " + HuskHomesHook.PLUGIN_NAME + " fallita ("
                + describe(failed) + ") — riprovo tra poco.");
    }

    private static String describe(Throwable e) {
        Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                ? e.getCause() : e;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    protected void onShutdown() {
        configLoader = null;
        menuHandler = null;
        hook = null;
        nextHookAttempt = 0L;
        missingLogged = false;
    }
}
