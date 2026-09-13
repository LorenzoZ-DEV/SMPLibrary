package dev.lorenzzz.smplibrary.security.dupe;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads zEssentials' own storage configuration, so nobody has to copy it by hand.
 *
 * <p>Duplicated credentials go stale the first time someone rotates a password, and a watcher
 * pointed at the wrong database does not fail loudly — it reports that every balance on the
 * server has failed to persist. Asking zEssentials where it writes is the only way to be sure
 * the answer stays right.
 *
 * <p>What it reads, in the order zEssentials itself does:
 * <ul>
 *   <li>{@code plugins/zEssentials/config.yml} — {@code storage-type} and the
 *       {@code database-configuration} block;</li>
 *   <li>{@code database-configuration.yml} in the server root, which overrides that block when
 *       present (that is how a network shares one database across plugins).</li>
 * </ul>
 *
 * <p>{@code SQLITE} means the file at {@code plugins/zEssentials/database.db}; every other
 * type — {@code HIKARICP}, {@code MYSQL}, {@code MARIADB} — is a server reached over JDBC,
 * because zEssentials routes all of them through the same Hikari connection.
 *
 * <p>It also reads {@code modules/economy/config.yml} for the list of currencies. Watching only
 * the one flagged {@code vault: true} would leave every other balance on the server unchecked,
 * and that list is not something to keep in a second place and hope stays in step.
 */
public final class ZEssentialsStorage {

    private static final String DEFAULT_FOLDER = "plugins/zEssentials";
    private static final String SQLITE_FILE = "database.db";
    private static final String GLOBAL_OVERRIDE = "database-configuration.yml";
    private static final String BLOCK = "database-configuration.";
    private static final String ECONOMY_CONFIG = "modules/economy/config.yml";

    private ZEssentialsStorage() {}

    /**
     * What zEssentials says about its own storage and currencies.
     *
     * @param storageType    as written in config.yml, upper-cased ({@code SQLITE}, {@code HIKARICP}, …)
     * @param tablePrefix    prefix applied to every table it creates
     * @param economies      every configured currency, default one first
     * @param vaultEconomy   the one currency reachable through Vault, or {@code null} if none is
     *                       flagged — the others can only be read through zEssentials itself
     */
    public record Discovery(String storageType, DatabaseSource source, String tablePrefix,
                            Set<String> economies, String vaultEconomy) {

        public Discovery {
            economies = economies == null || economies.isEmpty()
                    ? Set.of("money")
                    : Collections.unmodifiableSet(new LinkedHashSet<>(economies));
        }

        public boolean sqlite() {
            return this.source instanceof DatabaseSource.SqliteFile;
        }

        @Override
        public String toString() {
            return "zEssentials " + this.storageType + " -> " + this.source.describe()
                    + " (prefisso tabelle: " + this.tablePrefix
                    + ", economie: " + String.join(", ", this.economies)
                    + (this.vaultEconomy == null ? "" : ", vault: " + this.vaultEconomy) + ")";
        }
    }

    /** Reads the configuration from the default {@code plugins/zEssentials} folder. */
    public static Discovery discover() {
        return discover(Path.of(DEFAULT_FOLDER));
    }

    /**
     * Reads the configuration from an explicit folder.
     *
     * <p>Never throws: a missing or unreadable config falls back to the stock SQLite layout,
     * and the probe then reports a missing file rather than the plugin failing to start.
     */
    public static Discovery discover(Path zEssentialsFolder) {
        Path folder = zEssentialsFolder == null ? Path.of(DEFAULT_FOLDER) : zEssentialsFolder;

        YamlConfiguration config = load(folder.resolve("config.yml"));
        YamlConfiguration global = load(Path.of(GLOBAL_OVERRIDE));

        String storageType = string(config, global, "storage-type", "SQLITE").toUpperCase(Locale.ROOT);
        String prefix = database(config, global, "table-prefix", "zessentials_");

        Economies economies = readEconomies(folder);

        if (storageType.equals("SQLITE") || storageType.equals("JSON")) {
            // JSON keeps nothing in a database at all, but pointing at the SQLite path gives a
            // "file non trovato" that names the file, which is a better clue than silence.
            return new Discovery(storageType, new DatabaseSource.SqliteFile(folder.resolve(SQLITE_FILE)),
                    prefix, economies.names(), economies.vault());
        }

        String host = database(config, global, "host", "127.0.0.1");
        int port = databaseInt(config, global, "port", 3306);
        String name = database(config, global, "database", "zessentials");
        String user = database(config, global, "user", "root");
        String password = database(config, global, "password", "");

        DatabaseSource source = storageType.equals("MARIADB")
                ? DatabaseSource.Jdbc.mariadb(host, port, name, user, password)
                : DatabaseSource.Jdbc.mysql(host, port, name, user, password);

        return new Discovery(storageType, source, prefix, economies.names(), economies.vault());
    }

    /** The currency list as the economy module declares it. */
    private record Economies(Set<String> names, String vault) {}

    /**
     * Reads {@code modules/economy/config.yml}: {@code default-economy} first, then every entry
     * of {@code economies}, noting which one carries {@code vault: true}.
     *
     * <p>A missing or unreadable file falls back to {@code money}, which is what zEssentials
     * ships as its default economy.
     */
    private static Economies readEconomies(Path folder) {
        YamlConfiguration config = load(folder.resolve(ECONOMY_CONFIG));
        if (config == null) return new Economies(Set.of("money"), "money");

        var names = new LinkedHashSet<String>();
        String defaultEconomy = config.getString("default-economy");
        if (defaultEconomy != null && !defaultEconomy.isBlank()) names.add(defaultEconomy.trim());

        String vault = null;
        for (Map<?, ?> entry : config.getMapList("economies")) {
            Object name = entry.get("name");
            if (name == null || name.toString().isBlank()) continue;
            names.add(name.toString().trim());
            if (Boolean.TRUE.equals(entry.get("vault"))) vault = name.toString().trim();
        }

        if (names.isEmpty()) names.add("money");
        return new Economies(names, vault);
    }

    /**
     * Settings pre-filled from zEssentials: backend, table names and prefix.
     *
     * <p>Everything else keeps its default, so the usual call is
     * {@code ZEssentialsStorage.settings().payVerifySeconds(20).build()}.
     */
    public static DupeWatchSettings.Builder settings() {
        return settings(Path.of(DEFAULT_FOLDER));
    }

    public static DupeWatchSettings.Builder settings(Path zEssentialsFolder) {
        Discovery discovery = discover(zEssentialsFolder);
        return DupeWatchSettings.builder()
                .source(discovery.source())
                .table(discovery.tablePrefix() + "economies")
                .usersTable(discovery.tablePrefix() + "users")
                .economies(discovery.economies());
    }

    // -------------------------------------------------------------- internals

    private static YamlConfiguration load(Path file) {
        File handle = file.toFile();
        return handle.isFile() ? YamlConfiguration.loadConfiguration(handle) : null;
    }

    /** The root override wins when it carries the key, exactly as zEssentials resolves it. */
    private static String database(YamlConfiguration config, YamlConfiguration global,
                                   String key, String fallback) {
        return string(config, global, BLOCK + key, fallback);
    }

    private static String string(YamlConfiguration config, YamlConfiguration global,
                                 String path, String fallback) {
        if (global != null && global.contains(path)) return global.getString(path, fallback);
        if (config != null && config.contains(path)) return config.getString(path, fallback);
        return fallback;
    }

    private static int databaseInt(YamlConfiguration config, YamlConfiguration global,
                                   String key, int fallback) {
        String path = BLOCK + key;
        if (global != null && global.contains(path)) return global.getInt(path, fallback);
        if (config != null && config.contains(path)) return config.getInt(path, fallback);
        return fallback;
    }
}
