package dev.lorenzzz.smplibrary.security.dupe;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The queries, written once.
 *
 * <p>zEssentials creates the same tables with the same columns whichever backend it is pointed
 * at — the migrations are shared — so the SQL is shared too. Only two things differ between a
 * file and a server, and they are exactly what the subclasses provide: how a connection is
 * obtained, and whether anything needs closing afterwards.
 *
 * <p>Every statement here is a {@code SELECT}. That is the read-only guarantee this class can
 * offer on its own; the file implementation adds {@code mode=ro} on top, and a server should be
 * given a user with {@code SELECT} and nothing else.
 */
public abstract class SqlBalanceProbe implements BalanceProbe {

    protected final DupeWatchSettings settings;

    protected SqlBalanceProbe(DupeWatchSettings settings) {
        if (settings == null) throw new IllegalArgumentException("settings are required");
        this.settings = settings;
    }

    /**
     * A connection to run one statement on. The caller closes it, which returns it to the pool
     * in the pooled implementation and really closes it in the file one.
     */
    protected abstract Connection connection() throws SQLException;

    @Override
    public boolean usable() {
        return unusableReason() == null;
    }

    @Override
    public void verify() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            rows.next();
        }
    }

    @Override
    public OptionalDouble readPersisted(UUID playerId, String economy) throws SQLException {
        if (playerId == null || economy == null) return OptionalDouble.empty();

        String sql = "SELECT " + this.settings.amountColumn()
                + " FROM " + this.settings.table()
                + " WHERE " + uuidPredicate()
                + economyPredicate(1)
                + " LIMIT 1";

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindUuid(statement, playerId);
            if (this.settings.economyColumn() != null) statement.setString(index, economy);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return OptionalDouble.empty();
                BigDecimal amount = rows.getBigDecimal(1);
                return amount == null ? OptionalDouble.empty() : OptionalDouble.of(amount.doubleValue());
            }
        }
    }

    @Override
    public Map<String, Double> readAll(UUID playerId) throws SQLException {
        var balances = new LinkedHashMap<String, Double>();
        if (playerId == null) return balances;

        var economies = List.copyOf(this.settings.economies());
        String economyColumn = this.settings.economyColumn();

        // Without a discriminator column there is one row and one economy, so the name comes
        // from the settings rather than from the result set.
        String select = economyColumn == null
                ? this.settings.amountColumn()
                : economyColumn + ", " + this.settings.amountColumn();

        String sql = "SELECT " + select
                + " FROM " + this.settings.table()
                + " WHERE " + uuidPredicate()
                + economyPredicate(economies.size());

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindUuid(statement, playerId);
            if (economyColumn != null) {
                for (String economy : economies) statement.setString(index++, economy);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String economy = economyColumn == null ? economies.get(0) : rows.getString(1);
                    BigDecimal amount = rows.getBigDecimal(economyColumn == null ? 1 : 2);
                    if (economy != null && amount != null) balances.put(economy, amount.doubleValue());
                }
            }
        }
        return balances;
    }

    /**
     * Read as text on purpose: the column is a timestamp whose storage format depends on the
     * driver and the backend — a string in SQLite, a real TIMESTAMP in MySQL — and this value
     * only ever goes in front of a human working out whether a row is stale.
     */
    @Override
    public Optional<String> readUpdatedAt(UUID playerId, String economy) throws SQLException {
        String column = this.settings.updatedColumn();
        if (column == null || playerId == null) return Optional.empty();

        String sql = "SELECT " + column
                + " FROM " + this.settings.table()
                + " WHERE " + uuidPredicate()
                + economyPredicate(1)
                + " LIMIT 1";

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindUuid(statement, playerId);
            if (this.settings.economyColumn() != null) statement.setString(index, economy);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.ofNullable(rows.getString(1));
            }
        }
    }

    /**
     * More than one row for a nickname means two UUIDs answer to the same name — a premium
     * account and an offline-mode one, usually — and from then on which balance you see
     * depends on which row the lookup happened to hit. A divergence with no dupe behind it,
     * and worth knowing before blaming anybody.
     */
    @Override
    public int countRowsForName(String nameColumn, String playerName) throws SQLException {
        String column = requireIdentifier(nameColumn);
        if (playerName == null || playerName.isBlank()) return 0;

        String sql = "SELECT COUNT(*) FROM " + this.settings.usersTable() + " WHERE " + column + " = ?";

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /**
     * Schema discovery through {@link DatabaseMetaData} rather than {@code sqlite_master} or
     * {@code information_schema}: one implementation that answers the same way on both
     * backends, and no table name from the database ever gets spliced into a statement.
     */
    @Override
    public List<BalanceProbe.TableInfo> describeSchema() throws SQLException {
        var tables = new ArrayList<BalanceProbe.TableInfo>();

        try (Connection connection = connection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();

            var names = new ArrayList<String>();
            try (ResultSet rows = metaData.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (rows.next()) names.add(rows.getString("TABLE_NAME"));
            }
            names.sort(String::compareToIgnoreCase);

            for (String name : names) {
                var columns = new ArrayList<String>();
                try (ResultSet rows = metaData.getColumns(catalog, null, name, "%")) {
                    while (rows.next()) columns.add(rows.getString("COLUMN_NAME"));
                }
                tables.add(new BalanceProbe.TableInfo(name, columns));
            }
        }
        return tables;
    }

    @Override
    public void close() {
        // Nothing held by default; the pooled probe overrides this.
    }

    // -------------------------------------------------------------- internals

    /**
     * zEssentials stores UUIDs as the 36-character dashed string, but other tools writing the
     * same tables have been known to strip the dashes, and a lookup that silently misses reads
     * as "never persisted" — the loudest alert this watcher has. Both forms are matched.
     */
    private String uuidPredicate() {
        return "(" + this.settings.uuidColumn() + " = ? OR " + this.settings.uuidColumn() + " = ?)";
    }

    /**
     * Filters on the economy column, for one name or for several.
     *
     * <p>The placeholders are counted, not the names spliced: an economy name is data, it comes
     * out of a config file, and it goes in as a bound parameter like any other value.
     */
    private String economyPredicate(int names) {
        if (this.settings.economyColumn() == null || names <= 0) return "";
        if (names == 1) return " AND " + this.settings.economyColumn() + " = ?";
        return " AND " + this.settings.economyColumn() + " IN (" + "?, ".repeat(names - 1) + "?)";
    }

    /** Binds the two UUID spellings and returns the next free parameter index. */
    private int bindUuid(PreparedStatement statement, UUID playerId) throws SQLException {
        statement.setString(1, playerId.toString());
        statement.setString(2, playerId.toString().replace("-", ""));
        return 3;
    }

    /** SQL identifiers cannot be bound as parameters, so they are whitelisted instead. */
    protected static String requireIdentifier(String value) {
        if (value == null || !DupeWatchSettings.IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("identificatore SQL non valido: " + value
                    + " (atteso " + DupeWatchSettings.IDENTIFIER.pattern() + ")");
        }
        return value;
    }
}
