package dev.lorenzzz.smplibrary.modules.tpauto;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.hook.HuskHomesHook;
import dev.lorenzzz.smplibrary.manager.ToggleManager;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TPAuto: with it on, every {@code /tpa} aimed at you is accepted for you.
 *
 * <p>{@code /tpahere} is never accepted, and that asymmetry is the point: a TPA brings the other
 * player to you, a TPA_HERE takes you to them. The switch means "people may come to me", so an
 * automatic yes must not be able to move the person who flipped it. The rule lives in
 * {@link HuskHomesHook}, which only ever answers requests of type {@code TPA}.
 *
 * <p>The teleport itself is HuskHomes' — this module only says yes on the player's behalf,
 * through {@link HuskHomesHook}, so the warmup, the safety checks and the messages are the ones
 * players already know from {@code /tpaccept}. HuskHomes is a soft dependency: without it the
 * switch still stores, it just has nothing to accept, and the hook is retried in case the plugin
 * comes up after us.
 *
 * <h2>How a request gets picked up</h2>
 * HuskHomes fires {@code ReceiveTeleportRequestEvent} the moment one lands, and
 * {@link TpAutoRequestListener} turns that into {@link #onRequestReceived}. The answer is sent a
 * tick later because HuskHomes only queues the request in that event's own callback — answering
 * during the event finds an empty queue.
 *
 * <p>The periodic sweep is the safety net behind it: it catches what was already waiting when
 * the switch was turned on, and anything an event we never saw put in the queue. It walks only
 * the players who have the switch on.
 *
 * <p>The switch is the shared {@link ToggleManager} key {@code tpauto}: {@code /tpauto} and the
 * button in {@code /settings} are two doors into the same stored boolean.
 */
public final class TpAutoModule extends AbstractModule implements Listener {

    /** Toggle key, shared with the {@code tpauto} entry of inventories/settings.yml. */
    public static final String TOGGLE_KEY = "tpauto";

    private static final String COMMAND = "tpauto";

    /** TPAuto is off until a player asks for it — it teleports people to them, after all. */
    private static final boolean DEFAULT_STATE = false;

    private static final long POLL_TICKS = 20L;
    private static final long HOOK_RETRY_MS = 15_000L;

    /** Words that open the diagnostic instead of flipping the switch. */
    private static final Set<String> STATUS_WORDS = Set.of("status", "stato", "debug", "info");

    private HuskHomesHook hook;
    private long nextHookAttempt;
    private boolean missingLogged;

    /** Whether HuskHomes' receive-request event is being listened to; see {@link #registerRequestListener}. */
    private boolean requestListenerRegistered;

    /** Last thing that happened, for {@code /tpauto status}. */
    private volatile String lastEvent = "niente ancora";
    private volatile String lastHookError;

    private long pollTicks = POLL_TICKS;

    public TpAutoModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "tpauto"; }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "check-every-ticks", (int) POLL_TICKS
        );
    }

    @Override
    public void onEnable() {
        if (config() != null) {
            pollTicks = Math.max(2, config().getInt("check-every-ticks", (int) POLL_TICKS));
        }

        registerCommand();
        listen(this);
        registerRequestListener();
        repeat(this::sweep, pollTicks, pollTicks);

        C.info("TPAuto enabled — controllo ogni " + pollTicks + " tick, "
                + (huskHomesPresent() ? "HuskHomes trovato." : "in attesa di HuskHomes."));
    }

    /**
     * Registers the HuskHomes event listener, but only once the plugin is actually there:
     * {@link TpAutoRequestListener} names HuskHomes' event classes, and registering it without
     * them throws.
     *
     * <p>Retried from {@link #hook()} for the case where HuskHomes boots after us.
     */
    private void registerRequestListener() {
        if (requestListenerRegistered || !huskHomesPresent()) return;
        try {
            listen(new TpAutoRequestListener(this::onRequestReceived));
            requestListenerRegistered = true;
        } catch (Throwable failed) {
            this.lastHookError = describe(failed);
            C.warning("TPAuto: non riesco ad ascoltare gli eventi di " + HuskHomesHook.PLUGIN_NAME
                    + " (" + describe(failed) + ") — resta il controllo periodico.");
        }
    }

    private static boolean huskHomesPresent() {
        return Bukkit.getPluginManager().isPluginEnabled(HuskHomesHook.PLUGIN_NAME);
    }

    private void registerCommand() {
        PluginCommand command = plugin.getCommand(COMMAND);
        if (command == null) {
            C.warning("Command '" + COMMAND + "' not found in plugin.yml — solo il pulsante in /settings.");
            return;
        }
        command.setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof Player player) {
                handleCommand(player, args);
            } else {
                C.send(sender, "&cOnly players.");
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ state

    /** True when this player has TPAuto on. */
    public boolean isOn(Player player) {
        ToggleManager toggles = toggles();
        return toggles != null && toggles.get(player.getUniqueId(), TOGGLE_KEY, DEFAULT_STATE);
    }

    /** Stores the new state and, when switching on, clears whatever is already waiting. */
    public void set(Player player, boolean enabled) {
        ToggleManager toggles = toggles();
        if (toggles == null) return;

        toggles.set(player.getUniqueId(), TOGGLE_KEY, enabled);
        messages.send(player, enabled ? "tpauto.enabled" : "tpauto.disabled", Map.of());

        if (!enabled) return;
        if (hook() == null) {
            messages.send(player, "tpauto.unavailable", Map.of());
            return;
        }
        acceptPending(player);
    }

    private void handleCommand(Player player, String[] args) {
        if (args.length > 0 && STATUS_WORDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            sendStatus(player);
            return;
        }

        ToggleManager toggles = toggles();
        if (toggles == null) {
            messages.send(player, "tpauto.unavailable", Map.of());
            return;
        }
        // Reading before the stored value has landed would answer with the default and then
        // write that guess back, so the flip waits for the load.
        toggles.whenLoaded(player.getUniqueId(), () -> {
            if (!player.isOnline()) return;
            set(player, target(args, isOn(player)));
        });
    }

    /**
     * {@code /tpauto status} — the whole chain in one screen: switch, hook, and what HuskHomes
     * has waiting for this player right now.
     *
     * <p>Every step that can silently do nothing prints its own line, because "non funziona"
     * covers all of them and they need different fixes.
     */
    private void sendStatus(Player player) {
        ToggleManager toggles = toggles();
        boolean loaded = toggles != null && toggles.isLoaded(player.getUniqueId());

        C.send(player, "&d&lTPAUTO &8» &7diagnostica");
        C.send(player, " &8• &7modulo: &aattivo&7, controllo ogni &f" + this.pollTicks + " &7tick");
        C.send(player, " &8• &7il tuo interruttore: " + (isOn(player) ? "&aON" : "&cOFF")
                + " &8(" + (toggles == null ? "store non disponibile"
                        : toggles.backend() + (loaded ? "" : ", non ancora caricato")) + "&8)");

        boolean present = huskHomesPresent();
        HuskHomesHook live = present ? hook() : null;
        if (!present) {
            C.send(player, " &8• &7" + HuskHomesHook.PLUGIN_NAME + ": &cnon attivo &8(niente da accettare)");
        } else if (live == null) {
            C.send(player, " &8• &7" + HuskHomesHook.PLUGIN_NAME + ": &eattivo ma non agganciato &8("
                    + (this.lastHookError == null ? "riprovo tra poco" : this.lastHookError) + "&8)");
        } else {
            C.send(player, " &8• &7" + HuskHomesHook.PLUGIN_NAME + ": &aagganciato &8(v" + live.version()
                    + ", eventi " + (requestListenerRegistered ? "&aok" : "&cno") + "&8)");
            try {
                HuskHomesHook.Diagnosis diagnosis = live.inspect(player);
                if (diagnosis.ignoring()) {
                    C.send(player, " &8• &7/tpignore: &cattivo &8— HuskHomes scarta le richieste"
                            + " prima ancora che TPAuto le veda. Spegnilo con &f/tpignore&8.");
                }
                if (diagnosis.requester() == null) {
                    C.send(player, " &8• &7ultima richiesta: &fnessuna &8(fatti mandare un /tpa e rilancia)");
                } else {
                    C.send(player, " &8• &7ultima richiesta: &f" + diagnosis.requester()
                            + " &8(" + diagnosis.type() + ", " + diagnosis.status()
                            + (diagnosis.expired() ? ", scaduta" : "") + ")");
                }
            } catch (Throwable failed) {
                C.send(player, " &8• &7lettura richieste: &cfallita &8(" + describe(failed) + ")");
            }
        }

        C.send(player, " &8• &7/tpahere: &cmai accettate &8(sposterebbero te, non loro)");
        C.send(player, " &8• &7ultimo evento: &f" + this.lastEvent);
        C.send(player, " &8• &7come funziona: chi ti manda &f/tpa &7viene teletrasportato &7da te,"
                + " &7senza che tu accetti.");
    }

    /** {@code /tpauto} flips, {@code /tpauto on|off} (and the usual synonyms) states it. */
    private static boolean target(String[] args, boolean current) {
        if (args.length == 0) return !current;
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "true", "si", "sì", "yes", "attiva" -> true;
            case "off", "false", "no", "disattiva" -> false;
            default -> !current;
        };
    }

    // -------------------------------------------------------------- accepting

    /**
     * A request just landed. Answering has to wait a tick: HuskHomes queues the request in the
     * callback that runs after the event, so right now there is nothing to accept yet.
     */
    void onRequestReceived(UUID recipientId, String requester) {
        later(() -> {
            Player player = Bukkit.getPlayer(recipientId);
            if (player == null || !player.isOnline() || !isOn(player)) return;

            HuskHomesHook live = hook();
            if (live == null) return;
            try {
                if (live.accept(player, requester)) announce(player, requester);
            } catch (Throwable failed) {
                dropHook(failed);
            }
        }, 1L);
    }

    /** Accepts what is pending for every player with the switch on. */
    private void sweep() {
        if (hook() == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isOn(player)) acceptPending(player);
        }
    }

    private void acceptPending(Player player) {
        HuskHomesHook live = hook;
        if (live == null) return;
        try {
            String requester = live.acceptPending(player);
            if (requester != null) announce(player, requester);
        } catch (Throwable failed) {
            dropHook(failed);
        }
    }

    private void announce(Player player, String requester) {
        messages.send(player, "tpauto.accepted", Map.of("%player%", requester));
        this.lastEvent = "accettata la richiesta di " + requester + " per " + player.getName();
    }

    /** HuskHomes was reloaded or its API moved — drop the handle and re-hook later. */
    private void dropHook(Throwable failed) {
        hook = null;
        nextHookAttempt = System.currentTimeMillis() + HOOK_RETRY_MS;
        this.lastHookError = describe(failed);
        C.warning("TPAuto: chiamata a " + HuskHomesHook.PLUGIN_NAME + " fallita ("
                + describe(failed) + ") — riprovo tra poco.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        HuskHomesHook live = hook;
        if (live != null) live.forget(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------- hook

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
                C.warning("TPAuto: " + HuskHomesHook.PLUGIN_NAME + " non è attivo — il toggle si salva,"
                        + " ma non c'è nessuna richiesta di teleport da accettare.");
            }
            return null;
        }

        try {
            hook = new HuskHomesHook();
            missingLogged = false;
            this.lastHookError = null;
            // HuskHomes may well have booted after us; the listener could not be registered then.
            registerRequestListener();
            C.info("TPAuto agganciato a " + HuskHomesHook.PLUGIN_NAME + " v" + hook.version()
                    + (requestListenerRegistered ? " — richieste accettate all'istante." : "."));
        } catch (Throwable failed) {
            this.lastHookError = describe(failed);
            C.warning("TPAuto: aggancio a " + HuskHomesHook.PLUGIN_NAME + " fallito ("
                    + describe(failed) + ")."
                    + (failed instanceof NoClassDefFoundError
                            ? " Le classi di " + HuskHomesHook.PLUGIN_NAME + " non sono visibili da qui:"
                                    + " controlla che sia caricato come plugin normale e che smp-library"
                                    + " lo abbia in softdepend."
                            : ""));
        }
        return hook;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    protected void onShutdown() {
        hook = null;
        nextHookAttempt = 0L;
        missingLogged = false;
        requestListenerRegistered = false;
    }
}
