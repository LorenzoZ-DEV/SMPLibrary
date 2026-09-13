package dev.lorenzzz.smplibrary.security.dupe;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Read-only view of the balances as they are stored, whatever they are stored in.
 *
 * <p>The disk side of the comparison. Two implementations, picked by
 * {@link #create(DupeWatchSettings)} from the configured {@link DatabaseSource}:
 * {@link SqliteBalanceProbe} for a file and {@link HikariBalanceProbe} for a MySQL or MariaDB
 * server — which is what zEssentials uses under {@code storage-type: HIKARICP}.
 *
 * <p><strong>Never call any of this on the main thread.</strong> Every method does I/O, and on
 * the pooled implementation that I/O crosses the network.
 */
public interface BalanceProbe extends AutoCloseable {

    /** One table as the database describes itself. */
    record TableInfo(String name, List<String> columns) {

        public TableInfo {
            columns = List.copyOf(columns);
        }

        @Override
        public String toString() {
            return this.name + " (" + String.join(", ", this.columns) + ")";
        }
    }

    /** Builds the probe the settings ask for. */
    static BalanceProbe create(DupeWatchSettings settings) {
        if (settings == null) throw new IllegalArgumentException("settings are required");
        return switch (settings.source()) {
            case DatabaseSource.SqliteFile file -> new SqliteBalanceProbe(settings, file);
            case DatabaseSource.Jdbc jdbc -> new HikariBalanceProbe(settings, jdbc);
        };
    }

    /**
     * Cheap, non-blocking sanity check: is the driver here, does the file exist. It says
     * nothing about whether a remote server will actually answer — {@link #verify()} does.
     */
    boolean usable();

    /** Why {@link #usable()} is false, or {@code null} when it is true. */
    String unusableReason();

    /**
     * Opens a connection and runs a trivial query. Blocking, sometimes for as long as the
     * connection timeout, so this belongs on a background thread and nowhere else.
     */
    void verify() throws SQLException;

    /**
     * The balance on record right now, for one economy.
     *
     * <p>Empty means no row — which is not zero, and on a player holding money in session is
     * the strongest sign there is that nothing was ever persisted.
     */
    OptionalDouble readPersisted(UUID playerId, String economy) throws SQLException;

    /**
     * Every watched economy of one player, in a single round trip.
     *
     * <p>The quit check reads this once a second per departing player: one query for all the
     * currencies rather than one per currency, which over a network is the difference between a
     * diagnostic and a load generator. Economies with no row are absent from the map — again,
     * missing is not zero.
     */
    Map<String, Double> readAll(UUID playerId) throws SQLException;

    /** When the row was last written, as the database spells it; empty if the schema has no such column. */
    Optional<String> readUpdatedAt(UUID playerId, String economy) throws SQLException;

    /** Rows in the users table carrying this name. More than one explains a divergence on its own. */
    int countRowsForName(String nameColumn, String playerName) throws SQLException;

    /** Every table and column, so the configured names can be checked against reality. */
    List<TableInfo> describeSchema() throws SQLException;

    /** Human-readable target, credentials omitted. */
    String describe();

    /** Releases whatever the implementation holds — a no-op for the file one, the pool for the other. */
    @Override
    void close();
}
