package dev.lorenzzz.smplibrary.security.dupe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/**
 * The file backend: zEssentials on {@code storage-type: SQLITE}, writing to
 * {@code plugins/zEssentials/database.db}.
 *
 * <p>Everything here is deliberately unfriendly to the writer on the other side. The connection
 * is opened with {@code mode=ro}, so this code physically cannot write to the economy database
 * however wrong it gets; it is opened and closed around every single query, so it never sits on
 * a shared lock while zEssentials wants to commit; and {@code busy_timeout} lets a read wait its
 * turn instead of failing the instant a write is in flight.
 *
 * <p>The driver is resolved by name at runtime rather than referenced directly, so the class
 * loads on a server where no SQLite driver exists at all — {@link #unusableReason()} then says
 * so, and the watcher falls back to the checks that need no disk value.
 */
public final class SqliteBalanceProbe extends SqlBalanceProbe {

    /** Resolved by name so a missing driver is a message, not a {@code NoClassDefFoundError}. */
    private static final String DRIVER_CLASS = "org.sqlite.JDBC";

    /** Long enough to outlast a normal commit on the other side, short enough not to pile up. */
    private static final int BUSY_TIMEOUT_MS = 3000;

    private final Path file;

    SqliteBalanceProbe(DupeWatchSettings settings, DatabaseSource.SqliteFile source) {
        super(settings);
        this.file = source.file();
    }

    /** Convenience for callers that already know the backend is a file. */
    public SqliteBalanceProbe(DupeWatchSettings settings) {
        this(settings, requireFile(settings));
    }

    private static DatabaseSource.SqliteFile requireFile(DupeWatchSettings settings) {
        if (settings.source() instanceof DatabaseSource.SqliteFile file) return file;
        throw new IllegalArgumentException("these settings do not point at a SQLite file: "
                + settings.source().describe());
    }

    @Override
    public String unusableReason() {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException | LinkageError e) {
            return "driver JDBC '" + DRIVER_CLASS + "' non disponibile";
        }
        if (!Files.isRegularFile(this.file)) return "file non trovato: " + this.file.toAbsolutePath();
        if (!Files.isReadable(this.file)) return "file non leggibile: " + this.file.toAbsolutePath();
        return null;
    }

    @Override
    public String describe() {
        return "SQLite " + this.file.toAbsolutePath();
    }

    @Override
    protected Connection connection() throws SQLException {
        return open(this.file);
    }

    /** Tables and columns of a SQLite file, without building a probe first. */
    public static List<BalanceProbe.TableInfo> describeSchema(Path databaseFile) throws SQLException {
        var settings = DupeWatchSettings.builder().databaseFile(databaseFile).build();
        try (var probe = new SqliteBalanceProbe(settings)) {
            return probe.describeSchema();
        }
    }

    private static Connection open(Path databaseFile) throws SQLException {
        Connection connection = driver().connect(readOnlyUrl(databaseFile), new Properties());
        if (connection == null) {
            throw new SQLException("il driver SQLite ha rifiutato l'URL per " + databaseFile);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /**
     * {@code file:} URI form, which is the only way to ask SQLite for a read-only handle. The
     * path comes from {@link Path#toUri()} so a Windows drive letter and a POSIX path both come
     * out as something the URI parser accepts.
     */
    static String readOnlyUrl(Path databaseFile) {
        return "jdbc:sqlite:file:" + databaseFile.toAbsolutePath().toUri().getRawPath() + "?mode=ro";
    }

    /**
     * A driver instance, built by name.
     *
     * <p>Not {@code DriverManager}: that resolves drivers per calling class loader and hands
     * back whichever registered one claims the URL first, which on a server full of plugins
     * shipping their own copies is not a decision worth leaving to chance.
     */
    private static Driver driver() throws SQLException {
        try {
            return (Driver) Class.forName(DRIVER_CLASS).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new SQLException("driver JDBC '" + DRIVER_CLASS + "' non disponibile", e);
        }
    }
}
