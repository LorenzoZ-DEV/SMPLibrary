package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-player ON/OFF state for the /settings dialog.
 *
 * <p>The toggles used to live in a plain in-memory map that was dropped on quit, so every
 * session started with every entry showing ON. That is not only wrong on screen: the
 * commands behind those buttons ({@code tptoggle}, {@code togglemsg}, …) belong to plugins
 * that <em>do</em> remember their state, so a stale ON label made the next click flip the
 * real setting the wrong way.
 *
 * <p>Two backends, picked once at start-up. The database is used when the pool is actually
 * up; otherwise the toggles go to {@code playerdata/settings-toggles.yml}. The file backend
 * exists because the pool is not a given — a server whose JDBC driver is missing fails
 * {@link StorageManager#enable()} and every query with it, and half a dozen booleans per
 * player are not worth losing over that.
 */
public final class SettingsStateStore {

    /** Where the file backend keeps the toggles, relative to the plugin data folder. */
    private static final String FILE_PATH = "playerdata/settings-toggles.yml";

    /** Ticks to coalesce file writes: a player clicking through the dialog is one save, not six. */
    private static final long SAVE_DELAY = 20L;

    private final Smp_library plugin;
    private final StorageManager storage;
    private final String table;
    private final File file;

    /** True when the database is usable; false means the file backend is in charge. */
    private volatile boolean persistent;

    /** <PlayerUUID, <ItemKey, true=ON / false=OFF>>, populated once the load lands. */
    private final Map<UUID, Map<String, Boolean>> cache = new ConcurrentHashMap<>();

    /** In-flight database loads, so a join preload and a /settings a tick later share one query. */
    private final Map<UUID, CompletableFuture<Map<String, Boolean>>> loading = new ConcurrentHashMap<>();

    /** File backend: the whole file in memory, guarded by {@link #fileLock}. */
    private YamlConfiguration yaml;
    private final Object fileLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();

    public SettingsStateStore(Smp_library plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.table = (storage == null ? "smp_" : storage.prefix()) + "settings_toggles";
        this.file = new File(plugin.getDataFolder(), FILE_PATH);
    }

    /** Picks the backend. Call once, before anything reads or writes. */
    public void initialize() {
        persistent = createTable();
        if (!persistent) loadFile();
    }

    /** Which backend is live — for the start-up log line. */
    public String backend() {
        return persistent ? "database" : file.getName();
    }

    // ----------------------------------------------------------------- reads

    /**
     * Makes sure the player's toggles are cached, then runs {@code whenReady} on the main
     * thread. Callers that open the dialog must go through this: showing it before the load
     * lands would render defaults and let a click persist them over the stored values.
     */
    public void whenLoaded(UUID playerId, Runnable whenReady) {
        if (cache.containsKey(playerId)) {
            whenReady.run();
            return;
        }

        // The file backend is already in memory — nothing to wait for.
        if (!persistent) {
            cache.putIfAbsent(playerId, readFile(playerId));
            whenReady.run();
            return;
        }

        loading.computeIfAbsent(playerId, this::startLoad)
                .thenAccept(loaded -> {
                    cache.putIfAbsent(playerId, loaded);
                    loading.remove(playerId);
                    runOnMain(whenReady);
                });
    }

    private CompletableFuture<Map<String, Boolean>> startLoad(UUID playerId) {
        CompletableFuture<Map<String, Boolean>> future = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            future.complete(new ConcurrentHashMap<>());
            return future;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> future.complete(read(playerId)));
        return future;
    }

    private Map<String, Boolean> read(UUID playerId) {
        Map<String, Boolean> states = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT setting_key, enabled FROM " + table + " WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) states.put(rows.getString(1), rows.getInt(2) != 0);
            }
        } catch (SQLException | RuntimeException e) {
            C.warning("settings: could not read the toggles of " + playerId + ": " + describe(e));
        }
        return states;
    }

    /** Current state of a toggle; {@code true} (ON) until the player flips it. */
    public boolean get(UUID playerId, String key) {
        Map<String, Boolean> states = cache.get(playerId);
        return states == null || states.getOrDefault(key, true);
    }

    // ---------------------------------------------------------------- writes

    /** Stores a toggle: cache first so the redraw is instant, backend right after. */
    public void set(UUID playerId, String key, boolean enabled) {
        cache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(key, enabled);
        if (persistent) {
            persistAsync(playerId, key, enabled);
        } else {
            writeFile(playerId, key, enabled);
        }
    }

    /**
     * Update-then-insert rather than an upsert: SQLite spells that {@code ON CONFLICT DO UPDATE}
     * and MySQL/MariaDB {@code ON DUPLICATE KEY UPDATE}, and storage.yml can select any of the
     * three — same reasoning as the player record in playerstats.
     */
    private void persistAsync(UUID playerId, String key, boolean enabled) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = storage.connection()) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + table + " SET enabled = ? WHERE player_uuid = ? AND setting_key = ?")) {
                    update.setInt(1, enabled ? 1 : 0);
                    update.setString(2, playerId.toString());
                    update.setString(3, key);
                    if (update.executeUpdate() > 0) return;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + table + " (player_uuid, setting_key, enabled) VALUES (?, ?, ?)")) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, key);
                    insert.setInt(3, enabled ? 1 : 0);
                    insert.executeUpdate();
                }
            } catch (SQLException | RuntimeException e) {
                C.warning("settings: could not store '" + key + "' for " + playerId + ": " + describe(e));
            }
        });
    }

    // --------------------------------------------------------- file backend

    private void loadFile() {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        synchronized (fileLock) {
            yaml = YamlConfiguration.loadConfiguration(file);
        }
    }

    private Map<String, Boolean> readFile(UUID playerId) {
        Map<String, Boolean> states = new ConcurrentHashMap<>();
        synchronized (fileLock) {
            if (yaml == null) return states;
            ConfigurationSection section = yaml.getConfigurationSection(playerId.toString());
            if (section == null) return states;
            for (String key : section.getKeys(false)) {
                states.put(key, section.getBoolean(key, true));
            }
        }
        return states;
    }

    private void writeFile(UUID playerId, String key, boolean enabled) {
        synchronized (fileLock) {
            if (yaml == null) yaml = new YamlConfiguration();
            yaml.set(playerId + "." + key, enabled);
        }
        scheduleSave();
    }

    /**
     * Queues one save a second from now. The flag is cleared before the write, so a toggle
     * landing mid-save queues the next one instead of being swallowed.
     */
    private void scheduleSave() {
        if (!saveScheduled.compareAndSet(false, true)) return;
        if (!plugin.isEnabled()) {
            saveScheduled.set(false);
            saveNow();
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            saveScheduled.set(false);
            saveNow();
        }, SAVE_DELAY);
    }

    private void saveNow() {
        synchronized (fileLock) {
            if (yaml == null) return;
            try {
                yaml.save(file);
            } catch (IOException e) {
                C.warning("settings: could not write " + FILE_PATH + ": " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------- lifecycle

    /** Drops the cached toggles of a player who left; the backend keeps them. */
    public void unload(UUID playerId) {
        cache.remove(playerId);
        loading.remove(playerId);
    }

    /** Flushes a pending file write, then forgets everything held in memory. */
    public void clear() {
        if (!persistent && saveScheduled.getAndSet(false)) saveNow();
        cache.clear();
        loading.clear();
    }

    private boolean createTable() {
        if (storage == null) return false;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS " + table + " ("
                             + "player_uuid VARCHAR(36) NOT NULL, setting_key VARCHAR(64) NOT NULL,"
                             + " enabled INTEGER NOT NULL, PRIMARY KEY (player_uuid, setting_key))")) {
            statement.execute();
            return true;
        } catch (SQLException | RuntimeException e) {
            C.warning("settings: database unavailable (" + describe(e) + ") — storing the toggles in "
                    + FILE_PATH + " instead.");
            return false;
        }
    }

    /** A bare {@code getMessage()} is null for an NPE, which is exactly the pool-down case. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
