package dev.lorenzzz.smplibrary.security.dupe;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Watches the economy cache against what is actually on disk, and says when they disagree.
 *
 * <p>It detects and reports. It never blocks a transaction, never edits a balance and never
 * punishes anyone — partly because that is not its job, and mostly because it cannot tell an
 * exploit from a persistence bug. Both produce the same divergence, and only one of them is
 * the player's doing.
 *
 * <h2>What it measures</h2>
 * <ul>
 *   <li><b>Join</b> — a few seconds in, session balance against the stored one. They should
 *       already agree: the session was loaded from that row.</li>
 *   <li><b>Quit</b> — the main event. The disk is polled once a second until it catches up
 *       with the balance the player left with, and how long that took is recorded. Fast is
 *       silence, slow is {@link DupeAlert.Type#SLOW_FLUSH}, never is
 *       {@link DupeAlert.Type#PERSISTENCE_MISMATCH}.</li>
 *   <li><b>Pay</b> — the two sides of a transfer must cancel out. Credited without debited is
 *       money created; debited without credited is money lost. Each transfer command declares
 *       which currency it moves, since {@code /pay} and {@code /pistacchiopay} do not move the
 *       same one.</li>
 *   <li><b>Audit</b> — periodic sweep for balances that grew by more than the threshold with
 *       no transfer to explain it.</li>
 * </ul>
 *
 * <h2>Currencies</h2>
 * Every check runs once per configured economy. zEssentials keeps {@code money}, {@code coins}
 * and anything else the economy module lists in one table keyed by name, and a dupe in one of
 * them is invisible in the others — watching only the Vault-backed one would leave every other
 * currency on the server unchecked.
 *
 * <h2>Threading</h2>
 * Everything runs on one daemon scheduler thread. Balances are read only on the main thread,
 * hopped through the global region scheduler and returned as futures; the database — a SQLite
 * file or a MySQL server, depending on how zEssentials is configured — is touched only from
 * the scheduler thread. Neither ever happens the other way round.
 *
 * <h2>Lifecycle</h2>
 * The host registers this as a listener, calls {@link #start()}, and calls {@link #close()}
 * on shutdown. It is a library object: it registers nothing by itself and reads no config.
 */
public final class EconomyDupeWatcher implements Listener, AutoCloseable {

    /** How often the disk is re-read while waiting for a quit to persist. */
    private static final long FLUSH_POLL_SECONDS = 1;

    /** A broken probe says so once every five minutes, not once per query. */
    private static final long PROBE_ERROR_COOLDOWN_MILLIS = 300_000L;

    private final Plugin plugin;
    private final BalanceSource balances;
    private final DupeWatchSettings settings;
    private final Consumer<DupeAlert> sink;
    private final BalanceProbe probe;
    private final FlushLatencyStats flushStats = new FlushLatencyStats();
    private final ScheduledExecutorService scheduler;

    /** One player's stake in one currency — the unit everything here is keyed by. */
    private record Holding(UUID playerId, String economy) {}

    /** Last balance the audit saw: the baseline an unexplained gain is measured against. */
    private final Map<Holding, Double> lastSeen = new ConcurrentHashMap<>();

    /** Credits already explained by an observed transfer, waiting to be subtracted by the audit. */
    private final Map<Holding, Double> accountedGains = new ConcurrentHashMap<>();

    private volatile ScheduledFuture<?> auditTask;
    private volatile boolean running;
    private volatile boolean probeUsable;
    private volatile long nextProbeErrorReport;

    private EconomyDupeWatcher(Plugin plugin, BalanceSource balances,
                               DupeWatchSettings settings, Consumer<DupeAlert> sink) {
        this.plugin = plugin;
        this.balances = balances;
        this.settings = settings;
        this.sink = sink;
        this.probe = BalanceProbe.create(settings);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "smp-library-dupewatch");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Builds a watcher. Nothing starts until {@link #start()}.
     *
     * @param balances the cache side — {@code VaultBalanceSource.of(economy)} for a Vault
     *                 server, or any lambda over the host's own economy API
     * @param sink     where alerts go; {@link DupeAlertBroadcaster} is one option
     */
    public static EconomyDupeWatcher create(Plugin plugin, BalanceSource balances,
                                            DupeWatchSettings settings, Consumer<DupeAlert> sink) {
        if (plugin == null) throw new IllegalArgumentException("plugin is required");
        if (balances == null) throw new IllegalArgumentException("balances are required");
        if (settings == null) throw new IllegalArgumentException("settings are required");
        if (sink == null) throw new IllegalArgumentException("sink is required");
        return new EconomyDupeWatcher(plugin, balances, settings, sink);
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Starts the periodic audit and takes the first baseline. Idempotent.
     *
     * <p>An unreadable database is reported and then tolerated: the pay and audit checks run
     * off the cache alone and still catch money appearing from nowhere. Failing to start
     * because a diagnostic cannot read a file would be the worse outcome.
     */
    public synchronized void start() {
        if (this.running) return;
        this.running = true;

        // Only the cheap checks here — start() runs on the main thread, and a database on the
        // other end of a network is not something to go and ask about from there.
        String reason = this.probe.unusableReason();
        this.probeUsable = reason == null;
        if (!this.probeUsable) {
            this.plugin.getLogger().warning("[dupewatch] storage non leggibile (" + reason
                    + "): restano attivi solo i controlli sulla cache.");
            emit(DupeAlert.system(DupeAlert.Type.PROBE_UNAVAILABLE, "", 0, 0, reason));
        } else {
            this.plugin.getLogger().info("[dupewatch] avviato su " + this.probe.describe()
                    + " — economie: " + String.join(", ", this.settings.economies()));
            schedule(this::verifyProbe, 0);
        }

        seedBaseline();

        long interval = this.settings.auditIntervalSeconds();
        this.auditTask = this.scheduler.scheduleAtFixedRate(
                guard(this::audit), interval, interval, TimeUnit.SECONDS);
    }

    /** Stops every scheduled check and drops the listener registration. Idempotent. */
    @Override
    public synchronized void close() {
        if (!this.running && this.scheduler.isShutdown()) return;
        this.running = false;

        ScheduledFuture<?> task = this.auditTask;
        if (task != null) task.cancel(false);
        this.auditTask = null;

        HandlerList.unregisterAll(this);
        this.scheduler.shutdownNow();
        this.probe.close();
        this.lastSeen.clear();
        this.accountedGains.clear();
    }

    /** True between {@link #start()} and {@link #close()}. */
    public boolean running() {
        return this.running;
    }

    /** False when the database could not be read at start-up; cache-only checks still run. */
    public boolean probeAvailable() {
        return this.probeUsable;
    }

    /**
     * Opens a real connection and runs a trivial query, on the scheduler thread.
     *
     * <p>The cheap check at start-up only says the driver is there and the file exists. With a
     * MySQL backend that is most of the way to nothing: wrong credentials, a firewall or a
     * server that is simply down all look identical until something actually connects. Finding
     * out here — once, in the background — beats finding out on the first quit, when the answer
     * would be dressed up as "this player's balance never persisted".
     */
    private void verifyProbe() {
        try {
            this.probe.verify();
            this.plugin.getLogger().info("[dupewatch] connessione allo storage verificata.");
        } catch (SQLException | RuntimeException e) {
            this.probeUsable = false;
            this.plugin.getLogger().log(Level.WARNING, "[dupewatch] storage non raggiungibile", e);
            emit(DupeAlert.system(DupeAlert.Type.PROBE_UNAVAILABLE, "", 0, 0,
                    this.probe.describe() + " — " + describeError(e)));
        }
    }

    private static String describeError(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Read-only view of how long persistence is actually taking. */
    public FlushLatencyStats flushStats() {
        return this.flushStats;
    }

    // -------------------------------------------------------------------- API

    /** Tables and columns as the database describes itself — for a schema command. */
    public CompletableFuture<List<BalanceProbe.TableInfo>> describeSchema() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.probe.describeSchema();
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, this.scheduler);
    }

    /** One player's cache and stored values, compared right now, one line per economy. */
    public CompletableFuture<List<BalanceCheck>> checkNow(UUID playerId) {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        return cachedBalances(playerId).thenApplyAsync(cached -> {
            Map<String, Double> persisted;
            String failure = null;
            try {
                persisted = this.probe.readAll(playerId);
            } catch (SQLException | RuntimeException e) {
                persisted = Map.of();
                failure = "lettura fallita: " + describeError(e);
            }

            String name = nameOf(playerId);
            var checks = new ArrayList<BalanceCheck>();
            for (String economy : this.settings.economies()) {
                OptionalDouble cache = cached.getOrDefault(economy, OptionalDouble.empty());
                Double stored = persisted.get(economy);
                OptionalDouble disk = stored == null ? OptionalDouble.empty() : OptionalDouble.of(stored);

                String detail = failure;
                if (detail == null) {
                    detail = disk.isEmpty()
                            ? "nessuna riga per questo UUID"
                            : updatedAt(playerId, economy).map(at -> "ultima scrittura: " + at).orElse("");
                }
                checks.add(new BalanceCheck(playerId, name, economy, cache, disk,
                        aligned(cache, disk, this.settings.tolerance()), detail));
            }
            return List.copyOf(checks);
        }, this.scheduler);
    }

    /** The result of {@link #checkNow(UUID)}, one per economy. */
    public record BalanceCheck(
            UUID playerId,
            String name,
            String economy,
            OptionalDouble cached,
            OptionalDouble persisted,
            boolean aligned,
            String detail
    ) {
        /** One line, ready to print to whoever asked. */
        public String describe() {
            String cache = this.cached.isPresent() ? DupeAlert.format(this.cached.getAsDouble()) : "?";
            String disk = this.persisted.isPresent() ? DupeAlert.format(this.persisted.getAsDouble()) : "assente";
            return (this.name == null ? this.playerId.toString() : this.name)
                    + " [" + this.economy + "] — cache " + cache + ", disco " + disk
                    + (this.aligned ? " (allineati)" : " (DIVERGENTI)")
                    + (this.detail.isEmpty() ? "" : " — " + this.detail);
        }
    }

    // ----------------------------------------------------------------- events

    /**
     * The session was loaded from the stored row, so the two should already agree. Checked a
     * few seconds in, not on the spot: the economy plugin loads accounts asynchronously and a
     * balance read at MONITOR time can still be the placeholder zero.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!this.running) return;
        UUID playerId = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        schedule(() -> verifyJoin(playerId, name), this.settings.joinVerifySeconds());
    }

    /**
     * The main diagnostic. The balance is captured here, on the main thread while the player
     * object is still live, and the disk is then polled until it agrees or the timeout runs out.
     *
     * <p>{@code LOWEST}, not {@code MONITOR}, and that is not a style choice: zEssentials
     * unloads the user from its cache in a {@code HIGHEST} quit handler, which runs before
     * {@code MONITOR}. Reading the balance there would read it after the cache entry we are
     * trying to compare against is already gone — the measurement would be of nothing.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (!this.running) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        forget(playerId);

        if (!this.probeUsable) return;

        // Every currency the player was holding, captured in one pass while the account is
        // still in memory.
        var session = new LinkedHashMap<String, Double>();
        for (String economy : this.settings.economies()) {
            readCache(player, economy).ifPresent(value -> session.put(economy, value));
        }
        if (session.isEmpty()) return;

        var state = new FlushProbe(playerId, player.getName(), session, System.nanoTime());
        schedule(() -> pollFlush(state), 0);
    }

    /**
     * A transfer has two sides and they must cancel out.
     *
     * <p>Captured before the command runs, verified {@code payVerifySeconds} later. Anything
     * else that moves either balance inside that window lands in the same measurement, which
     * is why the window wants to be short — and why it has to be longer than any confirmation
     * menu standing between the command and the actual transfer.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!this.running) return;

        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2) return;

        String command = parts[0].toLowerCase(Locale.ROOT);
        int colon = command.indexOf(':');
        if (colon >= 0) command = command.substring(colon + 1);

        // Which currency this command moves. An unknown command is not a transfer, and a
        // transfer measured against the wrong currency would see both sides sit still.
        String economy = this.settings.payEconomy(command);
        if (economy == null) return;

        Player sender = event.getPlayer();
        Player target = Bukkit.getPlayerExact(parts[1]);
        // An offline recipient cannot be measured: their balance is not in the cache, so the
        // credit side of the transfer is simply not observable.
        if (target == null || target.getUniqueId().equals(sender.getUniqueId())) return;

        OptionalDouble senderBefore = readCache(sender, economy);
        OptionalDouble targetBefore = readCache(target, economy);
        if (senderBefore.isEmpty() || targetBefore.isEmpty()) return;

        var pending = new PendingPay(
                sender.getUniqueId(), sender.getName(), senderBefore.getAsDouble(),
                target.getUniqueId(), target.getName(), targetBefore.getAsDouble(),
                economy, parseAmount(parts.length >= 3 ? parts[2] : null));

        schedule(() -> verifyPay(pending), this.settings.payVerifySeconds());
    }

    // ------------------------------------------------------------------ join

    private void verifyJoin(UUID playerId, String name) {
        if (!this.running || !this.probeUsable) return;

        cachedBalances(playerId).thenAcceptAsync(cached -> {
            Map<String, Double> persisted = readAll(playerId);
            if (persisted == null) return;                 // probe error, already reported

            for (var entry : cached.entrySet()) {
                String economy = entry.getKey();
                OptionalDouble balance = entry.getValue();
                if (balance.isEmpty()) continue;

                double session = balance.getAsDouble();
                this.lastSeen.put(new Holding(playerId, economy), session);

                Double stored = persisted.get(economy);
                if (stored == null) {
                    // A brand-new player has no row yet and no money either: not a fault.
                    if (session <= this.settings.tolerance()) continue;
                    emit(DupeAlert.of(DupeAlert.Type.PERSISTENCE_MISMATCH, playerId, name, economy,
                            session, 0, "nessuna riga su disco al login" + duplicateNameNote(name)));
                    continue;
                }

                if (Math.abs(session - stored) <= this.settings.tolerance()) continue;

                emit(DupeAlert.of(DupeAlert.Type.PERSISTENCE_MISMATCH, playerId, name, economy,
                        session, stored, "divergenza al login" + updatedAtNote(playerId, economy)
                                + duplicateNameNote(name)));
            }
        }, this.scheduler);
    }

    // ------------------------------------------------------------------ quit

    /**
     * One quit being watched: who left, holding what in each currency, and since when we have
     * been waiting. {@code pending} shrinks as currencies land; the probe stops when it is
     * empty or when the timeout runs out on whatever is left.
     */
    private record FlushProbe(UUID playerId, String name, Map<String, Double> pending, long startedNanos) {

        long elapsedMillis() {
            return (System.nanoTime() - this.startedNanos) / 1_000_000L;
        }
    }

    private void pollFlush(FlushProbe state) {
        if (!this.running || !this.probeUsable) return;

        Map<String, Double> persisted = readAll(state.playerId());
        if (persisted == null) return;                     // probe error, already reported

        long elapsed = state.elapsedMillis();
        boolean expired = elapsed >= this.settings.flushTimeoutSeconds() * 1000L;

        var iterator = state.pending().entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String economy = entry.getKey();
            double session = entry.getValue();
            Double stored = persisted.get(economy);

            if (stored != null && Math.abs(stored - session) <= this.settings.tolerance()) {
                iterator.remove();
                this.flushStats.record(elapsed);
                if (elapsed > this.settings.expectedFlushSeconds() * 1000L) {
                    emit(DupeAlert.of(DupeAlert.Type.SLOW_FLUSH, state.playerId(), state.name(), economy,
                            session, stored,
                            "flush in " + DupeAlert.format(elapsed / 1000d) + "s, oltre i "
                                    + this.settings.expectedFlushSeconds() + "s attesi"));
                }
                continue;
            }

            if (!expired) continue;

            iterator.remove();
            this.flushStats.recordTimeout();
            emit(DupeAlert.of(DupeAlert.Type.PERSISTENCE_MISMATCH, state.playerId(), state.name(), economy,
                    session, stored == null ? 0 : stored,
                    (stored != null ? "il valore su disco non ha mai raggiunto quello di sessione"
                            : "nessuna riga su disco")
                            + " entro " + this.settings.flushTimeoutSeconds() + "s dal quit"
                            + updatedAtNote(state.playerId(), economy)));
        }

        if (state.pending().isEmpty()) return;
        schedule(() -> pollFlush(state), FLUSH_POLL_SECONDS);
    }

    // ------------------------------------------------------------------- pay

    /** A transfer in flight: both balances as they were when the command was typed. */
    private record PendingPay(
            UUID senderId, String senderName, double senderBefore,
            UUID targetId, String targetName, double targetBefore,
            String economy, OptionalDouble amount
    ) {}

    private void verifyPay(PendingPay pay) {
        if (!this.running) return;

        cachedBalance(pay.senderId(), pay.economy())
                .thenCombine(cachedBalance(pay.targetId(), pay.economy()), (senderAfter, targetAfter) ->
                        senderAfter.isEmpty() || targetAfter.isEmpty()
                                ? null
                                : new double[]{senderAfter.getAsDouble(), targetAfter.getAsDouble()})
                .thenAcceptAsync(after -> {
                    if (after == null) return;

                    double tolerance = this.settings.tolerance();
                    double debited = pay.senderBefore() - after[0];
                    double credited = after[1] - pay.targetBefore();

                    // Neither side moved: the command was refused, cancelled, or is still
                    // sitting on a confirmation screen. Nothing to say either way.
                    if (debited <= tolerance && credited <= tolerance) return;

                    double gap = credited - debited;
                    if (Math.abs(gap) <= tolerance) {
                        // Healthy transfer: remember the credit so the audit does not read it
                        // as money appearing out of nowhere.
                        this.accountedGains.merge(new Holding(pay.targetId(), pay.economy()),
                                credited, Double::sum);
                        return;
                    }

                    DupeAlert.Type type = gap > 0
                            ? DupeAlert.Type.MONEY_CREATED
                            : DupeAlert.Type.PAY_NOT_CREDITED;

                    // The player named is the one holding the money that should not be there:
                    // the recipient when money was created, the sender when it vanished.
                    boolean blameTarget = type == DupeAlert.Type.MONEY_CREATED;
                    emit(DupeAlert.of(type,
                            blameTarget ? pay.targetId() : pay.senderId(),
                            blameTarget ? pay.targetName() : pay.senderName(),
                            pay.economy(), debited, credited,
                            "trasferimento " + pay.senderName() + " -> " + pay.targetName()
                                    + (pay.amount().isPresent()
                                            ? ", importo digitato " + DupeAlert.format(pay.amount().getAsDouble())
                                            : "")
                                    + ", addebitato " + DupeAlert.format(debited)
                                    + ", accreditato " + DupeAlert.format(credited)));
                }, this.scheduler);
    }

    /**
     * Reads the amount typed by the player, k/m/b/t/q suffixes included, comma or dot for the
     * decimals. Only ever used to describe the alert: the checks themselves compare the two
     * balance deltas, so a {@code /pay Marco all} is verified just as well as a number.
     */
    static OptionalDouble parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return OptionalDouble.empty();

        String text = raw.trim().toLowerCase(Locale.ROOT).replace(',', '.').replace("_", "");
        double multiplier = switch (text.charAt(text.length() - 1)) {
            case 'k' -> 1_000d;
            case 'm' -> 1_000_000d;
            case 'b' -> 1_000_000_000d;
            case 't' -> 1_000_000_000_000d;
            case 'q' -> 1_000_000_000_000_000d;
            default -> 1d;
        };
        if (multiplier != 1d) text = text.substring(0, text.length() - 1);

        try {
            double value = Double.parseDouble(text) * multiplier;
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    // ----------------------------------------------------------------- audit

    private void audit() {
        if (!this.running) return;

        onlineBalances().thenAcceptAsync(current -> {
            double threshold = this.settings.unexplainedGainThreshold();
            for (var entry : current.entrySet()) {
                Holding holding = entry.getKey();
                double now = entry.getValue();
                Double previous = this.lastSeen.put(holding, now);
                if (previous == null) continue;            // first sighting: only a baseline

                Double credited = this.accountedGains.remove(holding);
                double gain = now - previous - (credited == null ? 0 : credited);
                if (gain <= threshold) continue;

                emit(DupeAlert.of(DupeAlert.Type.UNEXPLAINED_GAIN, holding.playerId(),
                        nameOf(holding.playerId()), holding.economy(), previous, now,
                        "aumento di " + DupeAlert.format(gain) + " in "
                                + this.settings.auditIntervalSeconds() + "s senza trasferimenti osservati"));
            }
            // Credits belonging to players who logged out mid-window would otherwise sit here
            // forever, suppressing the first real gain after they come back.
            this.accountedGains.keySet().retainAll(current.keySet());
        }, this.scheduler);
    }

    private void seedBaseline() {
        onlineBalances().thenAcceptAsync(this.lastSeen::putAll, this.scheduler);
    }

    // ------------------------------------------------------------- plumbing

    /** Reads a balance on the main thread. Only legal from inside a main-thread context. */
    private OptionalDouble readCache(OfflinePlayer player, String economy) {
        try {
            OptionalDouble balance = this.balances.balance(player, economy);
            return balance == null ? OptionalDouble.empty() : balance;
        } catch (RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING,
                    "[dupewatch] lettura del saldo fallita (" + economy + ")", e);
            return OptionalDouble.empty();
        }
    }

    private CompletableFuture<OptionalDouble> cachedBalance(UUID playerId, String economy) {
        return onMainThread(() -> readCache(resolve(playerId), economy))
                .exceptionally(error -> OptionalDouble.empty());
    }

    /** Every watched economy of one player, read in a single hop onto the main thread. */
    private CompletableFuture<Map<String, OptionalDouble>> cachedBalances(UUID playerId) {
        return this.<Map<String, OptionalDouble>>onMainThread(() -> {
            OfflinePlayer player = resolve(playerId);
            var balances = new LinkedHashMap<String, OptionalDouble>();
            for (String economy : this.settings.economies()) {
                balances.put(economy, readCache(player, economy));
            }
            return balances;
        }).exceptionally(error -> Map.of());
    }

    private CompletableFuture<Map<Holding, Double>> onlineBalances() {
        return this.<Map<Holding, Double>>onMainThread(() -> {
            var balances = new HashMap<Holding, Double>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (String economy : this.settings.economies()) {
                    readCache(player, economy).ifPresent(value ->
                            balances.put(new Holding(player.getUniqueId(), economy), value));
                }
            }
            return balances;
        }).exceptionally(error -> Map.of());
    }

    private static OfflinePlayer resolve(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        return online != null ? online : Bukkit.getOfflinePlayer(playerId);
    }

    /** Drops everything remembered about a player, across every currency. */
    private void forget(UUID playerId) {
        this.lastSeen.keySet().removeIf(holding -> holding.playerId().equals(playerId));
        this.accountedGains.keySet().removeIf(holding -> holding.playerId().equals(playerId));
    }

    /**
     * Runs {@code supplier} on the main thread and hands the result back to the scheduler.
     * A disabled plugin completes the future exceptionally rather than throwing into whatever
     * task happened to ask.
     */
    private <T> CompletableFuture<T> onMainThread(Supplier<T> supplier) {
        var future = new CompletableFuture<T>();
        if (!this.plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("plugin disabilitato"));
            return future;
        }
        try {
            Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Stored read with the error handling every caller wants; {@code null} means "probe failed". */
    private Map<String, Double> readAll(UUID playerId) {
        try {
            return this.probe.readAll(playerId);
        } catch (SQLException | RuntimeException e) {
            reportProbeError(e);
            return null;
        }
    }

    private Optional<String> updatedAt(UUID playerId, String economy) {
        try {
            return this.probe.readUpdatedAt(playerId, economy);
        } catch (SQLException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private String updatedAtNote(UUID playerId, String economy) {
        return updatedAt(playerId, economy).map(value -> " (ultima scrittura: " + value + ")").orElse("");
    }

    /**
     * Two rows for one nickname explain a divergence on their own, so it is worth saying so in
     * the alert rather than leaving staff to guess at a dupe.
     */
    private String duplicateNameNote(String name) {
        try {
            int rows = this.probe.countRowsForName(this.settings.nameColumn(), name);
            return rows > 1 ? " — attenzione: " + rows + " righe con il nome '" + name + "'" : "";
        } catch (SQLException | RuntimeException e) {
            return "";
        }
    }

    private void reportProbeError(Exception error) {
        long now = System.currentTimeMillis();
        if (now < this.nextProbeErrorReport) return;
        this.nextProbeErrorReport = now + PROBE_ERROR_COOLDOWN_MILLIS;

        this.plugin.getLogger().log(Level.WARNING, "[dupewatch] lettura dello storage fallita", error);
        emit(DupeAlert.system(DupeAlert.Type.PROBE_UNAVAILABLE, "", 0, 0, describeError(error)));
    }

    private static boolean aligned(OptionalDouble cached, OptionalDouble persisted, double tolerance) {
        return cached.isPresent() && persisted.isPresent()
                && Math.abs(cached.getAsDouble() - persisted.getAsDouble()) <= tolerance;
    }

    private String nameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        return online != null ? online.getName() : Bukkit.getOfflinePlayer(playerId).getName();
    }

    /** Hands an alert to the sink. A sink that throws must not take the watcher down with it. */
    private void emit(DupeAlert alert) {
        try {
            this.sink.accept(alert);
        } catch (RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "[dupewatch] il consumer degli alert ha lanciato", e);
        }
    }

    private void schedule(Runnable task, long delaySeconds) {
        if (!this.running || this.scheduler.isShutdown()) return;
        try {
            this.scheduler.schedule(guard(task), delaySeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutting down between the check above and here: nothing left to schedule onto.
        }
    }

    /**
     * Wraps a task so a stray exception cannot kill it.
     *
     * <p>Not defensive habit: a task submitted to {@code scheduleAtFixedRate} that throws is
     * cancelled and never runs again, silently. The audit would stop on the first hiccup and
     * nothing would say so.
     */
    private Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable error) {
                this.plugin.getLogger().log(Level.WARNING, "[dupewatch] controllo fallito", error);
            }
        };
    }
}
