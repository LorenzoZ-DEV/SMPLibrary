package dev.lorenzzz.smplibrary.security.dupe;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The server backend: zEssentials on {@code storage-type: HIKARICP} (or {@code MYSQL}), where
 * the economy lives in MySQL or MariaDB instead of a file.
 *
 * <p>A remote query costs a network round trip, and the quit check makes one every second per
 * departing player, so connections are pooled rather than opened per statement — the opposite
 * of the SQLite probe, where holding a connection is what causes trouble. The pool is
 * deliberately tiny: two connections is plenty for a diagnostic, and it must never be the
 * reason the economy plugin waits for one of its own.
 *
 * <h2>Read-only</h2>
 * There is no {@code mode=ro} for a network database. Three things stand in for it: the pool is
 * marked read-only, which MySQL and MariaDB connectors turn into {@code SET SESSION TRANSACTION
 * READ ONLY} on checkout; {@link SqlBalanceProbe} only ever builds {@code SELECT} statements;
 * and the account this connects with should be granted {@code SELECT} and nothing else. The
 * third is the only one a database can actually enforce, so grant it that way.
 *
 * <h2>Drivers</h2>
 * Paper gives every plugin its own class loader, so the driver zEssentials uses is not
 * necessarily visible from here. Each candidate is tried by name, and a MySQL URL will fall
 * back to the MariaDB driver (and the other way round) since either one speaks the protocol.
 * When none of them is present the probe says so and the watcher keeps running on its
 * cache-only checks.
 */
public final class HikariBalanceProbe extends SqlBalanceProbe {

    private static final String POOL_NAME = "smp-library-dupewatch";

    private static final List<String> MYSQL_DRIVERS = List.of("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver");
    private static final List<String> MARIADB_DRIVERS = List.of("org.mariadb.jdbc.Driver");
    private static final List<String> SQLITE_DRIVERS = List.of("org.sqlite.JDBC");

    private final DatabaseSource.Jdbc source;

    /** Built on first use, not in the constructor: constructing this must never touch a network. */
    private volatile HikariDataSource pool;
    private volatile boolean closed;

    HikariBalanceProbe(DupeWatchSettings settings, DatabaseSource.Jdbc source) {
        super(settings);
        this.source = source;
    }

    /** A driver that can speak the URL, together with the URL it wants to be given. */
    private record ResolvedDriver(Driver driver, String url, String className) {}

    @Override
    public String unusableReason() {
        if (this.closed) return "probe chiuso";
        try {
            resolve();
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    @Override
    public String describe() {
        return this.source.describe();
    }

    @Override
    protected Connection connection() throws SQLException {
        return pool().getConnection();
    }

    @Override
    public void close() {
        this.closed = true;
        HikariDataSource open = this.pool;
        this.pool = null;
        if (open != null) open.close();
    }

    // ------------------------------------------------------------------ pool

    private HikariDataSource pool() throws SQLException {
        HikariDataSource existing = this.pool;
        if (existing != null) return existing;

        synchronized (this) {
            if (this.closed) throw new SQLException("probe chiuso");
            if (this.pool != null) return this.pool;

            ResolvedDriver resolved = resolve();

            var properties = new Properties();
            if (!this.source.username().isEmpty()) properties.setProperty("user", this.source.username());
            if (!this.source.password().isEmpty()) properties.setProperty("password", this.source.password());

            var config = new HikariConfig();
            config.setPoolName(POOL_NAME);
            config.setDataSource(new DriverDataSource(resolved.driver(), resolved.url(), properties));
            // MySQL and MariaDB turn this into SET SESSION TRANSACTION READ ONLY on checkout.
            // SQLite cannot: its driver refuses to change the flag after connecting, and every
            // pooled connection would die on setup — so there the URL carries mode=ro instead,
            // which is stricter anyway. Asking for read-only twice is what broke it.
            config.setReadOnly(!isSqlite(resolved.url()));
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(5_000);
            config.setValidationTimeout(3_000);
            config.setIdleTimeout(60_000);
            config.setMaxLifetime(600_000);
            // Never fail construction: an economy database that is down for a minute must not
            // take a diagnostic — or the plugin holding it — down with it.
            config.setInitializationFailTimeout(-1);

            this.pool = new HikariDataSource(config);
            return this.pool;
        }
    }

    /**
     * Finds a driver for the configured URL, switching protocol family if that is what it
     * takes: MariaDB's connector talks to MySQL servers and vice versa, and on a server where
     * only one of the two is reachable from our class loader, using it beats not running.
     */
    private ResolvedDriver resolve() throws SQLException {
        String url = this.source.url();

        List<String> preferred;
        List<String> fallback;
        String fallbackUrl;

        if (url.startsWith("jdbc:mysql:")) {
            preferred = MYSQL_DRIVERS;
            fallback = MARIADB_DRIVERS;
            fallbackUrl = "jdbc:mariadb:" + url.substring("jdbc:mysql:".length());
        } else if (url.startsWith("jdbc:mariadb:")) {
            preferred = MARIADB_DRIVERS;
            fallback = MYSQL_DRIVERS;
            fallbackUrl = "jdbc:mysql:" + url.substring("jdbc:mariadb:".length());
        } else if (isSqlite(url)) {
            preferred = SQLITE_DRIVERS;
            fallback = List.of();
            fallbackUrl = null;
            url = readOnlySqlite(url);
        } else {
            preferred = List.of();
            fallback = List.of();
            fallbackUrl = null;
        }

        for (String className : preferred) {
            Driver driver = load(className);
            if (driver != null) return new ResolvedDriver(driver, url, className);
        }
        for (String className : fallback) {
            Driver driver = load(className);
            if (driver != null) return new ResolvedDriver(driver, fallbackUrl, className);
        }

        throw new SQLException("nessun driver JDBC disponibile per " + this.source.describe()
                + " — cercati: " + String.join(", ", concat(preferred, fallback))
                + ". Il driver di zEssentials non e' visibile da qui: Paper isola i classloader"
                + " dei plugin, quindi va aggiunto alle 'libraries' del server o del plugin.");
    }

    private static boolean isSqlite(String url) {
        return url.startsWith("jdbc:sqlite:");
    }

    /**
     * Pooling a SQLite file is unusual but legal — and if someone does it, the connection still
     * has no business being writable. A plain path becomes the read-only URI form; a URL that
     * already carries parameters is left exactly as given, since rewriting someone else's query
     * string is a good way to break it.
     */
    private static String readOnlySqlite(String url) {
        if (url.indexOf('?') >= 0) return url;
        String path = url.substring("jdbc:sqlite:".length());
        if (path.isBlank() || path.startsWith(":")) return url;   // :memory: and friends
        if (path.startsWith("file:")) return url + "?mode=ro";
        return SqliteBalanceProbe.readOnlyUrl(java.nio.file.Path.of(path));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        var all = new java.util.ArrayList<>(first);
        all.addAll(second);
        return all.isEmpty() ? List.of("(nessun candidato per questo URL)") : all;
    }

    private static Driver load(String className) {
        try {
            return (Driver) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * A {@link DataSource} over one driver instance.
     *
     * <p>Hikari would otherwise go through {@code DriverManager}, which resolves drivers per
     * calling class loader and picks whichever registered one claims the URL first — not a
     * decision worth leaving to chance on a server full of plugins shipping their own copies.
     */
    private record DriverDataSource(Driver driver, String url, Properties properties) implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = this.driver.connect(this.url, this.properties);
            if (connection == null) throw new SQLException("il driver ha rifiutato l'URL: " + this.url);
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            var overrides = new Properties();
            overrides.putAll(this.properties);
            if (username != null) overrides.setProperty("user", username);
            if (password != null) overrides.setProperty("password", password);
            Connection connection = this.driver.connect(this.url, overrides);
            if (connection == null) throw new SQLException("il driver ha rifiutato l'URL: " + this.url);
            return connection;
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) return type.cast(this);
            throw new SQLException("non e' un wrapper di " + type);
        }
        @Override public boolean isWrapperFor(Class<?> type) {
            return type.isInstance(this);
        }
    }
}
