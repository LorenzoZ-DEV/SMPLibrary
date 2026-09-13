package dev.lorenzzz.smplibrary.security.dupe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Turns an alert into the line staff actually read, in the shape they already know from
 * anticheat plugins:
 *
 * <pre>ANTIDUPE » Lorenzo potrebbe stare DUPPANDO MoneyDupeA (VL 3)</pre>
 *
 * <p>"potrebbe" is the whole point. Nothing here punishes anyone, and nothing here should:
 * this module cannot tell a dupe from a broken flush, and a ban handed out automatically on
 * a false positive costs far more than a missed exploit. The message is an invitation to go
 * and look, with {@code /dupewatch check <player>} one click away.
 *
 * <h2>Violation levels</h2>
 * Counted per player and per check, and halved every {@code decayMinutes} so an isolated
 * incident does not follow someone around forever. The decay is applied lazily when the
 * counter is next touched — same result as a repeating task, minus the thread.
 *
 * <p>The cooldown suppresses the <em>message</em>, never the count: a loop firing the same
 * anomaly a hundred times shows one line and a violation level that tells the real story.
 *
 * <p>Alerts carry the currency they are about — the server runs several — and the templates
 * expose it as {@code <economy>}; the default puts it in the hover rather than in the line, so
 * the line stays the one shape staff already read at a glance.
 *
 * <p>System-level checks ({@code StorageLagA}, {@code StorageProbeA}) name nobody and score
 * nobody. A slow database is not a player's doing, and putting a name next to it would send
 * staff after the wrong thing.
 */
public final class DupeAlertBroadcaster implements Consumer<DupeAlert> {

    /** Permission that receives the alerts in chat. */
    public static final String DEFAULT_PERMISSION = "smplibrary.antidupe.notify";

    public static final String DEFAULT_PLAYER_TEMPLATE =
            "<dark_gray>[</dark_gray><red><bold>ANTIDUPE</bold></red><dark_gray>]</dark_gray> "
                    + "<gray>»</gray> <white><player></white> <gray>potrebbe stare</gray> "
                    + "<red><bold>DUPPANDO</bold></red> <yellow><check></yellow> "
                    + "<dark_gray>(</dark_gray><gray>VL <vl></gray><dark_gray>)</dark_gray>";

    public static final String DEFAULT_SYSTEM_TEMPLATE =
            "<dark_gray>[</dark_gray><red><bold>ANTIDUPE</bold></red><dark_gray>]</dark_gray> "
                    + "<gray>»</gray> <yellow><check></yellow> <gray>—</gray> <white><detail></white>";

    public static final String DEFAULT_HOVER_TEMPLATE =
            "<gray>Check: <yellow><check></yellow>"
                    + "<newline><gray>Economia: <white><economy></white>"
                    + "<newline><gray>Atteso: <white><expected></white>"
                    + "<newline><gray>Effettivo: <white><actual></white>"
                    + "<newline><gray>Delta: <white><delta></white>"
                    + "<newline><gray><detail></gray>"
                    + "<newline><newline><dark_gray>Clicca per verificare cache e disco.</dark_gray>";

    private static final long MINUTE_MILLIS = 60_000L;

    /** Drop a player's counters once they are this stale — they have decayed to nothing anyway. */
    private static final long FORGET_AFTER_MILLIS = 6 * 60 * MINUTE_MILLIS;

    private final Plugin plugin;
    private final String permission;
    private final long cooldownMillis;
    private final long decayMillis;
    private final boolean echoToConsole;

    private String playerTemplate = DEFAULT_PLAYER_TEMPLATE;
    private String systemTemplate = DEFAULT_SYSTEM_TEMPLATE;
    private String hoverTemplate = DEFAULT_HOVER_TEMPLATE;

    /** One counter per player and check. */
    private final Map<String, Violation> violations = new ConcurrentHashMap<>();

    /** Last time this player+check was actually announced. */
    private final Map<String, Long> lastAnnounced = new ConcurrentHashMap<>();

    /** A running score that fades on its own. */
    private static final class Violation {
        private double level;
        private long updatedAt = System.currentTimeMillis();
    }

    /** Defaults: 30 s between identical messages, violation levels halved every 30 minutes. */
    public DupeAlertBroadcaster(Plugin plugin) {
        this(plugin, DEFAULT_PERMISSION, 30, 30, true);
    }

    /**
     * @param permission     who sees the alerts in chat
     * @param cooldownSeconds minimum gap between two identical messages
     * @param decayMinutes   how often violation levels are halved
     * @param echoToConsole  also log every alert, cooldown or not — this is the audit trail
     */
    public DupeAlertBroadcaster(Plugin plugin, String permission, long cooldownSeconds,
                                long decayMinutes, boolean echoToConsole) {
        if (plugin == null) throw new IllegalArgumentException("plugin is required");
        this.plugin = plugin;
        this.permission = permission == null || permission.isBlank() ? DEFAULT_PERMISSION : permission;
        this.cooldownMillis = Math.max(0, cooldownSeconds) * 1000L;
        this.decayMillis = Math.max(1, decayMinutes) * MINUTE_MILLIS;
        this.echoToConsole = echoToConsole;
    }

    /** Overrides the chat line for player-scoped alerts. MiniMessage. */
    public DupeAlertBroadcaster playerTemplate(String template) {
        if (template != null && !template.isBlank()) this.playerTemplate = template;
        return this;
    }

    /** Overrides the chat line for server-scoped alerts. MiniMessage. */
    public DupeAlertBroadcaster systemTemplate(String template) {
        if (template != null && !template.isBlank()) this.systemTemplate = template;
        return this;
    }

    /** Overrides the hover box. MiniMessage. */
    public DupeAlertBroadcaster hoverTemplate(String template) {
        if (template != null && !template.isBlank()) this.hoverTemplate = template;
        return this;
    }

    // ------------------------------------------------------------------ sink

    @Override
    public void accept(DupeAlert alert) {
        if (alert == null) return;

        boolean system = alert.type().system();
        // The count is what survives; the message is only how it gets noticed. So score first,
        // then decide whether anyone is told about it.
        double level = system ? 0 : bump(alert);

        if (this.echoToConsole) {
            this.plugin.getLogger().warning("[antidupe] " + alert
                    + (system ? "" : " (VL " + formatLevel(level) + ")"));
        }

        if (!announce(alert, system)) return;

        Component message = render(alert, system, level);
        deliver(message);
    }

    // -------------------------------------------------------------- counting

    private double bump(DupeAlert alert) {
        String key = key(alert);
        var violation = this.violations.computeIfAbsent(key, ignored -> new Violation());
        synchronized (violation) {
            decay(violation);
            violation.level += weight(alert.type());
            violation.updatedAt = System.currentTimeMillis();
            return violation.level;
        }
    }

    /** Current level for a player and check, decay included. Zero when nothing is on record. */
    public double violationLevel(UUID playerId, DupeAlert.Type type) {
        var violation = this.violations.get(playerId + ":" + type.code());
        if (violation == null) return 0;
        synchronized (violation) {
            decay(violation);
            return violation.level;
        }
    }

    /** Wipes a player's history across every check — for a staff "cleared" command. */
    public void clear(UUID playerId) {
        String prefix = playerId + ":";
        this.violations.keySet().removeIf(key -> key.startsWith(prefix));
        this.lastAnnounced.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Halves the level once per elapsed decay period. Applied on access rather than on a
     * timer: the arithmetic is the same and there is no second thread to shut down.
     */
    private void decay(Violation violation) {
        long elapsed = System.currentTimeMillis() - violation.updatedAt;
        long periods = elapsed / this.decayMillis;
        if (periods <= 0) return;

        violation.level = periods >= 16 ? 0 : violation.level / Math.pow(2, periods);
        violation.updatedAt = System.currentTimeMillis();
        if (violation.level < 0.01) violation.level = 0;
    }

    /** A critical anomaly is worth more than a suspicion. */
    private static double weight(DupeAlert.Type type) {
        return switch (type.severity()) {
            case CRITICAL -> 3;
            case HIGH -> 2;
            case MEDIUM -> 1;
            case LOW -> 0.5;
        };
    }

    private boolean announce(DupeAlert alert, boolean system) {
        String key = system ? "system:" + alert.type().code() : key(alert);
        long now = System.currentTimeMillis();
        Long last = this.lastAnnounced.get(key);
        if (last != null && now - last < this.cooldownMillis) return false;
        this.lastAnnounced.put(key, now);
        prune(now);
        return true;
    }

    private void prune(long now) {
        this.lastAnnounced.entrySet().removeIf(entry -> now - entry.getValue() > FORGET_AFTER_MILLIS);
        this.violations.entrySet().removeIf(entry -> {
            var violation = entry.getValue();
            synchronized (violation) {
                return now - violation.updatedAt > FORGET_AFTER_MILLIS;
            }
        });
    }

    private static String key(DupeAlert alert) {
        return alert.playerId() + ":" + alert.type().code();
    }

    // -------------------------------------------------------------- rendering

    private Component render(DupeAlert alert, boolean system, double level) {
        String template = system ? this.systemTemplate : this.playerTemplate;
        Component line = MiniMessage.miniMessage().deserialize(fill(template, alert, level));

        if (system) return line;

        Component hover = MiniMessage.miniMessage().deserialize(fill(this.hoverTemplate, alert, level));
        return line.hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand("/dupewatch check " + alert.describeSubject()));
    }

    private static String fill(String template, DupeAlert alert, double level) {
        return template
                .replace("<player>", sanitize(alert.describeSubject()))
                .replace("<check>", alert.type().code())
                .replace("<economy>", sanitize(alert.economy().isEmpty() ? "-" : alert.economy()))
                .replace("<vl>", formatLevel(level))
                .replace("<expected>", DupeAlert.format(alert.expected()))
                .replace("<actual>", DupeAlert.format(alert.actual()))
                .replace("<delta>", DupeAlert.format(alert.delta()))
                .replace("<detail>", sanitize(alert.detail().isEmpty()
                        ? alert.type().description()
                        : alert.detail()));
    }

    /**
     * Values are substituted into a MiniMessage string, so anything that looks like a tag in
     * them would be parsed as one. Names cannot contain {@code <}, but details quote player
     * input, and a detail that silently swallowed half the message would be a bad way to find
     * that out.
     */
    private static String sanitize(String value) {
        return value == null ? "" : value.replace("<", "\\<");
    }

    private static String formatLevel(double level) {
        return String.format(Locale.ROOT, level == Math.rint(level) ? "%.0f" : "%.1f", level);
    }

    /**
     * Chat delivery hops onto the main thread: alerts arrive on the watcher's scheduler, and
     * walking the online player list from another thread is not something to do on a live
     * server.
     */
    private void deliver(Component message) {
        if (!this.plugin.isEnabled()) return;
        try {
            Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission(this.permission)) player.sendMessage(message);
                }
            });
        } catch (RuntimeException ignored) {
            // Disabled between the check and the call: the console line already went out.
        }
    }
}
