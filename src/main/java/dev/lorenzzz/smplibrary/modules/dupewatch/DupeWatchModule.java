package dev.lorenzzz.smplibrary.modules.dupewatch;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.web.WebLog;
import dev.lorenzzz.smplibrary.modules.web.WebModule;
import dev.lorenzzz.smplibrary.modules.web.WebStats;
import dev.lorenzzz.smplibrary.security.dupe.BalanceSource;
import dev.lorenzzz.smplibrary.security.dupe.DupeAlert;
import dev.lorenzzz.smplibrary.security.dupe.DupeAlertBroadcaster;
import dev.lorenzzz.smplibrary.security.dupe.DupeWatchSettings;
import dev.lorenzzz.smplibrary.security.dupe.EconomyDupeWatcher;
import dev.lorenzzz.smplibrary.security.dupe.VaultBalanceSource;
import dev.lorenzzz.smplibrary.security.dupe.ZEssentialsBalanceSource;
import dev.lorenzzz.smplibrary.security.dupe.ZEssentialsStorage;
import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host wiring for the anti-dupe watcher, plus the feed the dashboard reads.
 *
 * <p>{@code dev.lorenzzz.smplibrary.security.dupe} is deliberately library code: it registers
 * nothing, reads no config and decides nothing on its own. This is the part that does — it
 * reads {@code MODULES.dupewatch}, asks zEssentials where its database and its currencies are,
 * picks a balance source, wires the alerts to chat and to the panel, and turns the whole thing
 * off again on reload.
 *
 * <p>Alerts are kept in a small ring so the panel can show what happened while nobody was
 * looking. Only the critical ones are echoed into the dashboard's action log: the log is a
 * record of things that changed the server, and a slow flush is not one of them.
 */
public final class DupeWatchModule extends AbstractModule {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** How many alerts the panel can look back through. */
    private static final int DEFAULT_HISTORY = 100;

    /** One alert as the dashboard shows it: the anomaly plus when it landed. */
    private record Entry(long at, DupeAlert alert) {}

    private final Deque<Entry> history = new ArrayDeque<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final AtomicLong version = new AtomicLong();

    private EconomyDupeWatcher watcher;
    private DupeAlertBroadcaster broadcaster;
    private DupeWatchSettings settings;
    private String balanceSourceName = "--";
    private int historyLimit = DEFAULT_HISTORY;

    public DupeWatchModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "dupewatch"; }

    @Override
    public Map<String, Object> defaults() {
        return Map.of("enabled", true);
    }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();
        this.historyLimit = cfg == null ? DEFAULT_HISTORY : Math.max(10, cfg.getInt("history", DEFAULT_HISTORY));

        this.settings = buildSettings(cfg);
        BalanceSource source = resolveSource(cfg);
        if (source == null) {
            Log.warning("Anti-dupe: nessuna sorgente per i saldi (zEssentials assente e Vault non registrato)"
                    + " — modulo non avviato.");
            return;
        }

        this.broadcaster = buildBroadcaster(cfg);
        this.watcher = EconomyDupeWatcher.create(plugin, source, this.settings, this::onAlert);

        listen(this.watcher);
        this.watcher.start();

        Log.info("Anti-dupe attivo (" + this.balanceSourceName + ") su "
                + String.join(", ", this.settings.economies()) + ".");
    }

    @Override
    protected void onShutdown() {
        if (this.watcher != null) this.watcher.close();
        this.watcher = null;
        this.broadcaster = null;
        synchronized (this.history) {
            this.history.clear();
            this.counts.clear();
        }
    }

    // ------------------------------------------------------------------ setup

    /**
     * Settings from zEssentials' own configuration, with the timings overridable here.
     *
     * <p>The database coordinates and the currency list are never taken from our config: they
     * live in zEssentials, they change when it is reconfigured, and a second copy would go stale
     * without anyone noticing until the panel started reporting that nothing persists.
     */
    private DupeWatchSettings buildSettings(ConfigurationSection cfg) {
        Path folder = plugin.getDataFolder().getParentFile().toPath().resolve("zEssentials");
        var builder = ZEssentialsStorage.settings(folder);

        if (cfg == null) return builder.build();

        List<String> economies = cfg.getStringList("economies");
        if (!economies.isEmpty()) builder.economies(new LinkedHashSet<>(economies));

        builder.tolerance(cfg.getDouble("tolerance", 0.01))
                .unexplainedGainThreshold(cfg.getDouble("unexplained-gain-threshold", 1_000_000))
                .expectedFlushSeconds(cfg.getInt("expected-flush-seconds", 5))
                .flushTimeoutSeconds(cfg.getInt("flush-timeout-seconds", 30))
                .payVerifySeconds(cfg.getInt("pay-verify-seconds", 20))
                .auditIntervalSeconds(cfg.getInt("audit-interval-seconds", 30))
                .joinVerifySeconds(cfg.getInt("join-verify-seconds", 3));

        ConfigurationSection payCommands = cfg.getConfigurationSection("pay-commands");
        if (payCommands != null) {
            var commands = new LinkedHashMap<String, String>();
            for (String alias : payCommands.getKeys(false)) {
                commands.put(alias, payCommands.getString(alias, ""));
            }
            builder.payCommands(commands);
        }
        return builder.build();
    }

    /**
     * zEssentials first: it is the only source that can answer for every currency. Vault is the
     * fallback and only ever speaks for the one economy flagged {@code vault: true}, so with it
     * the other currencies simply go unchecked rather than being checked wrongly.
     */
    private BalanceSource resolveSource(ConfigurationSection cfg) {
        String preferred = cfg == null ? "zessentials" : cfg.getString("balance-source", "zessentials");

        if (!"vault".equalsIgnoreCase(preferred) && Bukkit.getPluginManager().isPluginEnabled("zEssentials")) {
            this.balanceSourceName = "zEssentials";
            return ZEssentialsBalanceSource.create();
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            String vaultEconomy = ZEssentialsStorage
                    .discover(plugin.getDataFolder().getParentFile().toPath().resolve("zEssentials"))
                    .vaultEconomy();
            if (vaultEconomy == null) vaultEconomy = this.settings.primaryEconomy();

            var source = VaultBalanceSource.fromServices(vaultEconomy);
            if (source.isPresent()) {
                this.balanceSourceName = "Vault (" + vaultEconomy + ")";
                Log.warning("Anti-dupe: uso Vault, che parla solo per '" + vaultEconomy
                        + "'. Le altre economie non verranno controllate.");
                return source.get();
            }
        }
        return null;
    }

    private DupeAlertBroadcaster buildBroadcaster(ConfigurationSection cfg) {
        ConfigurationSection notify = cfg == null ? null : cfg.getConfigurationSection("notify");
        if (notify == null) return new DupeAlertBroadcaster(plugin);
        return new DupeAlertBroadcaster(plugin,
                notify.getString("permission", DupeAlertBroadcaster.DEFAULT_PERMISSION),
                notify.getLong("cooldown-seconds", 30),
                notify.getLong("decay-minutes", 30),
                notify.getBoolean("console", true));
    }

    // ----------------------------------------------------------------- alerts

    /**
     * Every alert lands here: chat first, then the panel's ring.
     *
     * <p>Called from the watcher's scheduler thread, so the ring is guarded and nothing here
     * touches Bukkit — the broadcaster does its own hop onto the main thread.
     */
    private void onAlert(DupeAlert alert) {
        if (this.broadcaster != null) this.broadcaster.accept(alert);

        synchronized (this.history) {
            this.history.addFirst(new Entry(System.currentTimeMillis(), alert));
            while (this.history.size() > this.historyLimit) this.history.removeLast();
            this.counts.merge(alert.type().code(), 1, Integer::sum);
        }
        this.version.incrementAndGet();

        if (alert.critical()) logToDashboard(alert);
    }

    /**
     * The action log keeps what changed the server, so only the critical anomalies go in — and
     * they go in because that log is what survives a restart in latest.log, while the ring here
     * does not.
     */
    private void logToDashboard(DupeAlert alert) {
        try {
            WebModule web = plugin.modules() == null ? null : plugin.modules().module("web", WebModule.class);
            WebLog log = web == null ? null : web.log();
            if (log == null) return;
            log.record("Anti-dupe " + alert.type().code() + ": " + alert.describeSubject()
                    + " (" + alert.economy() + ") atteso " + DupeAlert.format(alert.expected())
                    + ", effettivo " + DupeAlert.format(alert.actual()));
        } catch (Exception e) {
            // An audit line that fails to render must never take down the check that produced it.
            Log.warning("Anti-dupe: impossibile scrivere sull'action log: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- dashboard

    /** Bumped on every alert, so the dashboard only gets a frame when something happened. */
    public long version() {
        return this.version.get();
    }

    /** The whole panel payload. */
    public String json() {
        List<Entry> entries;
        Map<String, Integer> byCheck;
        synchronized (this.history) {
            entries = List.copyOf(this.history);
            byCheck = new LinkedHashMap<>(this.counts);
        }

        var stats = this.watcher == null ? null : this.watcher.flushStats().snapshot();
        boolean probeUp = this.watcher != null && this.watcher.probeAvailable();

        var json = new StringBuilder("{");
        json.append("\"running\":").append(this.watcher != null);
        json.append(",\"source\":").append(WebStats.jsonStr(sourceLabel()));
        json.append(",\"balanceSource\":").append(WebStats.jsonStr(this.balanceSourceName));
        json.append(",\"probeAvailable\":").append(probeUp);
        json.append(",\"economies\":").append(strings(this.settings == null ? Set.of() : this.settings.economies()));
        json.append(",\"total\":").append(entries.size());
        json.append(",\"critical\":").append(entries.stream().filter(e -> e.alert().critical()).count());

        json.append(",\"counts\":{");
        boolean first = true;
        for (var entry : byCheck.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(WebStats.jsonStr(entry.getKey())).append(':').append(entry.getValue());
        }
        json.append('}');

        json.append(",\"flush\":");
        if (stats == null) {
            json.append("{\"samples\":0,\"minMs\":0,\"avgMs\":0,\"maxMs\":0,\"timeouts\":0}");
        } else {
            json.append("{\"samples\":").append(stats.samples())
                    .append(",\"minMs\":").append(stats.minMillis())
                    .append(",\"avgMs\":").append(Math.round(stats.averageMillis()))
                    .append(",\"maxMs\":").append(stats.maxMillis())
                    .append(",\"timeouts\":").append(stats.timeouts())
                    .append('}');
        }

        json.append(",\"players\":").append(playersJson(entries));
        json.append(",\"alerts\":").append(alertsJson(entries));
        return json.append('}').toString();
    }

    private String sourceLabel() {
        if (this.settings == null) return "--";
        return this.settings.source().describe();
    }

    /** Who shows up most in the ring, worst check first — the panel's "chi guardare" list. */
    private String playersJson(List<Entry> entries) {
        record Tally(String name, UUID id) {}
        var byPlayer = new LinkedHashMap<String, int[]>();
        var worst = new LinkedHashMap<String, DupeAlert>();
        var ids = new LinkedHashMap<String, UUID>();

        for (Entry entry : entries) {
            DupeAlert alert = entry.alert();
            if (alert.type().system() || alert.playerId() == null) continue;
            String name = alert.describeSubject();
            byPlayer.computeIfAbsent(name, key -> new int[1])[0]++;
            ids.putIfAbsent(name, alert.playerId());
            DupeAlert current = worst.get(name);
            if (current == null || alert.type().severity().compareTo(current.type().severity()) > 0) {
                worst.put(name, alert);
            }
        }

        var rows = new ArrayList<String>();
        byPlayer.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(10)
                .forEach(entry -> {
                    String name = entry.getKey();
                    DupeAlert alert = worst.get(name);
                    double vl = this.broadcaster == null || alert == null
                            ? 0 : this.broadcaster.violationLevel(ids.get(name), alert.type());
                    rows.add("{\"player\":" + WebStats.jsonStr(name)
                            + ",\"alerts\":" + entry.getValue()[0]
                            + ",\"check\":" + WebStats.jsonStr(alert == null ? "" : alert.type().code())
                            + ",\"severity\":" + WebStats.jsonStr(alert == null ? "" : alert.type().severity().name())
                            + ",\"vl\":" + String.format(Locale.ROOT, "%.1f", vl) + "}");
                });
        return "[" + String.join(",", rows) + "]";
    }

    private String alertsJson(List<Entry> entries) {
        var rows = new ArrayList<String>(entries.size());
        for (Entry entry : entries) {
            DupeAlert alert = entry.alert();
            rows.add("{\"at\":" + WebStats.jsonStr(CLOCK.format(
                            LocalTime.ofInstant(java.time.Instant.ofEpochMilli(entry.at()),
                                    java.time.ZoneId.systemDefault())))
                    + ",\"code\":" + WebStats.jsonStr(alert.type().code())
                    + ",\"severity\":" + WebStats.jsonStr(alert.type().severity().name())
                    + ",\"system\":" + alert.type().system()
                    + ",\"player\":" + WebStats.jsonStr(alert.type().system() ? "" : alert.describeSubject())
                    + ",\"economy\":" + WebStats.jsonStr(alert.economy())
                    // Quoted, not bare numbers: the panel prints these as money and a JSON
                    // number would drop the trailing zeros of 1200.00 — and a non-finite
                    // balance would spell NaN, which is not JSON at all and would take the
                    // whole payload, and the tab with it, down.
                    + ",\"expected\":" + WebStats.jsonStr(DupeAlert.format(alert.expected()))
                    + ",\"actual\":" + WebStats.jsonStr(DupeAlert.format(alert.actual()))
                    + ",\"delta\":" + WebStats.jsonStr(DupeAlert.format(alert.delta()))
                    + ",\"detail\":" + WebStats.jsonStr(alert.detail()) + "}");
        }
        return "[" + String.join(",", rows) + "]";
    }

    private static String strings(Set<String> values) {
        var quoted = new ArrayList<String>(values.size());
        for (String value : values) quoted.add(WebStats.jsonStr(value));
        return "[" + String.join(",", quoted) + "]";
    }
}
