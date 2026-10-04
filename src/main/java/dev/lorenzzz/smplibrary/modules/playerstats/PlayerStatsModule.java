package dev.lorenzzz.smplibrary.modules.playerstats;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Log;
import dev.lorenzzz.smplibrary.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Records how many players are online over time, so the dashboard can plot the day and
 * name its peak.
 *
 * <p>Samples live in two places on purpose: an in-memory ring of the last 24h answers every
 * read (the web layer polls, and hitting SQLite for that would be silly), while the database
 * is what makes the history survive a restart. On boot the ring is refilled from the database.
 */
public final class PlayerStatsModule extends AbstractModule implements Listener {

    private static final long DEFAULT_SAMPLE_PERIOD = Ticks.MINUTE;
    private static final long MINIMUM_SAMPLE_PERIOD = 5 * Ticks.SECOND;
    private static final long WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final int DEFAULT_RETENTION_DAYS = 7;

    /** One reading: when it was taken and how many players were on. */
    public record Sample(long timestamp, int online) {}

    /** The high-water mark of a period, and the moment it happened. */
    public record Peak(int online, long timestamp) {
        public static final Peak NONE = new Peak(0, 0L);
    }

    private final Deque<Sample> window = new ArrayDeque<>();
    private final Object lock = new Object();

    private String table;
    private String recordTable;
    private long sampleIntervalMs = 60_000L;
    private boolean persistent;
    private boolean recordPersistent;

    /**
     * The all-time high, which deliberately does not live in {@link #window} or the samples
     * table: that window is 24h and those rows are pruned after a week, so a record set months
     * ago would quietly disappear. It gets its own single-row table instead.
     */
    private volatile Peak record = Peak.NONE;

    /** False until the stored record has been read back; see {@link #noteOnline(int)}. */
    private volatile boolean recordReady;

    public PlayerStatsModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "playerstats"; }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();
        long period = Ticks.parseAtLeast(
                cfg == null ? null : cfg.getString("sample-every"),
                DEFAULT_SAMPLE_PERIOD, MINIMUM_SAMPLE_PERIOD);
        sampleIntervalMs = period / Ticks.SECOND * 1000L;

        String prefix = storage == null ? "smp_" : storage.prefix();
        table = prefix + "player_samples";
        recordTable = prefix + "player_record";
        // Tracked separately: the two tables fail independently, and losing the record slot is
        // no reason to stop persisting samples.
        persistent = createTable();
        recordPersistent = createRecordTable();

        if (persistent) {
            loadWindowAsync();
            pruneOldRowsAsync(cfg == null ? DEFAULT_RETENTION_DAYS : cfg.getInt("retention-days", DEFAULT_RETENTION_DAYS));
        }
        if (recordPersistent) {
            loadRecordAsync(cfg);
        } else {
            // No database: at least honour the number declared in config for this session.
            raiseRecord(new Peak(configuredRecord(cfg), 0L), false);
            recordReady = true;
        }

        // A new high is set the instant someone connects, which is between two samples more
        // often than not — so watch joins as well as the sampler.
        listen(this);

        // Sample immediately so a fresh restart shows a point straight away.
        repeat(this::sample, 0L, period);
        Log.info("Player stats enabled — sampling every " + (sampleIntervalMs / 1000L) + "s"
                + (persistent ? " (persisted)" : " (memory only)") + ".");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        // getOnlinePlayers() already counts the joining player at this point.
        noteOnline(Bukkit.getOnlinePlayers().size());
    }

    private static int configuredRecord(ConfigurationSection cfg) {
        return cfg == null ? 0 : Math.max(0, cfg.getInt("record", 0));
    }

    /** Milliseconds between samples — the chart uses it to spot gaps. */
    public long sampleIntervalMs() {
        return sampleIntervalMs;
    }

    // ------------------------------------------------------------- recording

    private void sample() {
        Sample sample = new Sample(System.currentTimeMillis(), Bukkit.getOnlinePlayers().size());

        synchronized (lock) {
            window.addLast(sample);
            trimLocked(sample.timestamp());
        }
        if (persistent) persistAsync(sample);
        noteOnline(sample.online());
    }

    /**
     * Offers a live count to the all-time record; a no-op unless it beats the current one.
     *
     * <p>Nothing is written to the database until {@link #recordReady} says the stored value
     * has been read back. The first sample fires a tick after enable, which is easily before
     * the async load lands — so without this gate a quiet restart would see an empty in-memory
     * record, treat two players online as a new high, and persist that over the real one.
     * Raising the in-memory value early is harmless; the load only ever raises it further.
     */
    private void noteOnline(int online) {
        boolean raised = raiseRecord(new Peak(online, System.currentTimeMillis()),
                recordPersistent && recordReady);
        if (raised && recordReady) Log.info("New player record: " + online + " players online!");
    }

    /**
     * Raises the record, never lowers it — that is what makes it a record. Returns true when
     * the value actually moved.
     *
     * <p>Synchronised because the async database load can land while the join listener is
     * already reporting live counts; without it a slow query could overwrite a genuine new
     * high with the older stored one.
     */
    private boolean raiseRecord(Peak candidate, boolean persist) {
        if (candidate.online() <= 0) return false;
        synchronized (lock) {
            if (candidate.online() <= record.online()) return false;
            record = candidate;
        }
        if (persist) persistRecordAsync(candidate);
        return true;
    }

    /** Drops anything older than the 24h window. Caller holds {@link #lock}. */
    private void trimLocked(long now) {
        long cutoff = now - WINDOW_MS;
        while (!window.isEmpty() && window.peekFirst().timestamp() < cutoff) {
            window.removeFirst();
        }
    }

    // ---------------------------------------------------------------- reads

    /** The last 24h of samples, oldest first. */
    public List<Sample> window() {
        synchronized (lock) {
            return List.copyOf(window);
        }
    }

    /**
     * Highest count since local midnight. Falls back to the whole window when the server
     * started today and has nothing older.
     */
    public Peak peakToday() {
        long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return peakSince(midnight);
    }

    public Peak peakSince(long from) {
        Peak best = Peak.NONE;
        synchronized (lock) {
            for (Sample sample : window) {
                if (sample.timestamp() >= from && sample.online() > best.online()) {
                    best = new Peak(sample.online(), sample.timestamp());
                }
            }
        }
        return best;
    }

    /**
     * The highest concurrent player count ever recorded, and when it happened.
     * A timestamp of 0 means the figure came from the {@code record:} config key rather than
     * from a reading this module took, so the moment is not known.
     */
    public Peak record() {
        return record;
    }

    /** Mean online count across the window, or 0 when nothing has been sampled yet. */
    public double average() {
        synchronized (lock) {
            if (window.isEmpty()) return 0;
            long total = 0;
            for (Sample sample : window) total += sample.online();
            return (double) total / window.size();
        }
    }

    // -------------------------------------------------------------- storage

    private boolean createTable() {
        if (storage == null) return false;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS " + table + " ("
                             + "ts BIGINT NOT NULL, online INTEGER NOT NULL, PRIMARY KEY (ts))")) {
            statement.execute();
            return true;
        } catch (SQLException | RuntimeException e) {
            // No database is not fatal — the module still works for the current session.
            Log.warning("playerstats: no database available (" + e.getMessage()
                    + "); history will not survive a restart.");
            return false;
        }
    }

    /** One row, id 1. Small and separate so pruning the samples can never touch it. */
    private boolean createRecordTable() {
        if (storage == null) return false;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS " + recordTable + " ("
                             + "id INTEGER NOT NULL, online INTEGER NOT NULL, ts BIGINT NOT NULL,"
                             + " PRIMARY KEY (id))")) {
            statement.execute();
            return true;
        } catch (SQLException | RuntimeException e) {
            Log.warning("playerstats: could not prepare the record table (" + e.getMessage() + ").");
            return false;
        }
    }

    private void loadRecordAsync(ConfigurationSection cfg) {
        int declared = configuredRecord(cfg);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Peak stored = Peak.NONE;
            try (Connection connection = storage.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT online, ts FROM " + recordTable + " WHERE id = 1")) {
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) stored = new Peak(rows.getInt(1), rows.getLong(2));
                }
            } catch (SQLException e) {
                Log.warning("playerstats: could not read the player record: " + e.getMessage());
            }

            raiseRecord(stored, false);
            // A record reached before this module existed can be declared in config. It is a
            // floor, not an override: a real reading that beats it always wins.
            if (raiseRecord(new Peak(declared, 0L), false)) {
                Log.info("Player stats: record raised to " + declared + " from config.");
            }
            recordReady = true;

            // One write covers both catch-up cases: the config floor beating the stored value,
            // and a live count that set a new high while this query was in flight.
            Peak current = record;
            if (current.online() > stored.online()) persistRecordAsync(current);
            if (current.online() > 0) {
                Log.info("Player stats: all-time record is " + current.online() + " players.");
            }
        });
    }

    /**
     * Writes the single record row.
     *
     * <p>Update-then-insert rather than an upsert on purpose: SQLite spells that
     * {@code ON CONFLICT DO UPDATE} and MySQL/MariaDB spell it {@code ON DUPLICATE KEY UPDATE},
     * and storage.yml can select any of the three. Two statements for a row that changes only
     * when a record falls is a fair price for not carrying a dialect check.
     */
    private void persistRecordAsync(Peak peak) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = storage.connection()) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + recordTable + " SET online = ?, ts = ? WHERE id = 1")) {
                    update.setInt(1, peak.online());
                    update.setLong(2, peak.timestamp());
                    if (update.executeUpdate() > 0) return;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + recordTable + " (id, online, ts) VALUES (1, ?, ?)")) {
                    insert.setInt(1, peak.online());
                    insert.setLong(2, peak.timestamp());
                    insert.executeUpdate();
                }
            } catch (SQLException e) {
                Log.warning("playerstats: could not store the player record: " + e.getMessage());
            }
        });
    }

    private void persistAsync(Sample sample) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = storage.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO " + table + " (ts, online) VALUES (?, ?)")) {
                statement.setLong(1, sample.timestamp());
                statement.setInt(2, sample.online());
                statement.executeUpdate();
            } catch (SQLException e) {
                Log.warning("playerstats: could not store a sample: " + e.getMessage());
            }
        });
    }

    private void loadWindowAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long from = System.currentTimeMillis() - WINDOW_MS;
            List<Sample> loaded = new ArrayList<>();

            try (Connection connection = storage.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT ts, online FROM " + table + " WHERE ts >= ? ORDER BY ts ASC")) {
                statement.setLong(1, from);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) loaded.add(new Sample(rows.getLong(1), rows.getInt(2)));
                }
            } catch (SQLException e) {
                Log.warning("playerstats: could not read history: " + e.getMessage());
                return;
            }

            // Merge in front of whatever the live sampler already recorded.
            synchronized (lock) {
                long earliestLive = window.isEmpty() ? Long.MAX_VALUE : window.peekFirst().timestamp();
                for (int i = loaded.size() - 1; i >= 0; i--) {
                    Sample sample = loaded.get(i);
                    if (sample.timestamp() < earliestLive) window.addFirst(sample);
                }
                trimLocked(System.currentTimeMillis());
            }
            Log.info("Player stats: restored " + loaded.size() + " samples from the database.");
        });
    }

    private void pruneOldRowsAsync(int retentionDays) {
        if (retentionDays <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000L;
            try (Connection connection = storage.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM " + table + " WHERE ts < ?")) {
                statement.setLong(1, cutoff);
                statement.executeUpdate();
            } catch (SQLException e) {
                Log.warning("playerstats: could not prune old samples: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onShutdown() {
        synchronized (lock) {
            window.clear();
            // Re-read on the next enable rather than carrying a value across a reload, which
            // would ignore an edited 'record:' and re-arm the load race with a stale figure.
            record = Peak.NONE;
        }
        recordReady = false;
    }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "sample-every", "1m",
                "retention-days", DEFAULT_RETENTION_DAYS,
                "record", 0);
    }
}
