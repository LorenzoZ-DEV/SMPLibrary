package dev.lorenzzz.smplibrary.security.dupe;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything the watcher needs to know, passed in by whoever starts it.
 *
 * <p>Deliberately a plain record with no reference to any config file: this is library code,
 * and a library that reads the host's config.yml decides for the host. The defaults match a
 * stock zEssentials install on SQLite; the table and column names were taken from the plugin's
 * own migrations ({@code CreateUserEconomyMigration} creates {@code %prefix%economies} with
 * {@code unique_id}, {@code economy_name}, {@code amount}), but the prefix is configurable over
 * there, so confirm them with {@link EconomyDupeWatcher#describeSchema()} before trusting a
 * silent zero.
 *
 * <p>{@link #source()} is where those tables live, which is not always a file: zEssentials on
 * {@code storage-type: HIKARICP} keeps them in MySQL. {@link ZEssentialsStorage#settings(Path)}
 * fills all of this in by reading zEssentials' own configuration, which is the way to get it
 * right without copying credentials by hand.
 *
 * <p>SQL identifiers cannot be bound as parameters, so every name that ends up spliced into a
 * query is checked against {@link #IDENTIFIER} here, once, at construction.
 */
public record DupeWatchSettings(
        DatabaseSource source,
        String table,
        String uuidColumn,
        String amountColumn,
        String economyColumn,
        Set<String> economies,
        String usersTable,
        String nameColumn,
        String updatedColumn,
        double tolerance,
        double unexplainedGainThreshold,
        int flushTimeoutSeconds,
        int expectedFlushSeconds,
        int payVerifySeconds,
        int auditIntervalSeconds,
        int joinVerifySeconds,
        Map<String, String> payCommands
) {

    /** What may be spliced into a statement: no quotes, no spaces, no comment markers. */
    public static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private static final Path DEFAULT_DATABASE = Path.of("plugins", "zEssentials", "database.db");

    /**
     * Every economy watched by default: zEssentials' own {@code money} plus the {@code coins}
     * this server spends as pistacchi. The real list lives in
     * {@code plugins/zEssentials/modules/economy/config.yml} — {@link ZEssentialsStorage} reads
     * it, and that is the way to be sure none is missed.
     */
    private static final Set<String> DEFAULT_ECONOMIES =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("money", "coins")));

    /**
     * Transfer commands and the economy each one moves. A namespace prefix
     * ({@code essentials:pay}) is stripped before the lookup, so only bare names go here.
     *
     * <p>Which currency an alias moves has to be stated: {@code /pay} moves {@code money} and
     * {@code /pistacchiopay} moves {@code coins}. Checking a transfer against the wrong one
     * would compare two balances that never moved and call it a silent failure.
     */
    private static final Map<String, String> DEFAULT_PAY_COMMANDS = Map.of(
            "pay", "money",
            "epay", "money",
            "pistacchiopay", "coins",
            "pistacchipay", "coins",
            "pagapers", "coins");

    public DupeWatchSettings {
        if (source == null) throw new IllegalArgumentException("source is required");

        table = identifier("table", table);
        uuidColumn = identifier("uuidColumn", uuidColumn);
        amountColumn = identifier("amountColumn", amountColumn);
        usersTable = identifier("usersTable", usersTable);
        nameColumn = identifier("nameColumn", nameColumn);
        // Both optional: a single-economy table has no economy column, and a schema without
        // timestamps simply gives up the "when was this row last written" detail.
        economyColumn = optionalIdentifier("economyColumn", economyColumn);
        updatedColumn = optionalIdentifier("updatedColumn", updatedColumn);

        economies = normalizeEconomies(economies);
        // Without a discriminator column the table holds exactly one economy, and asking it for
        // two would hand the same row back for both: two balances that always agree, and a
        // check that can never fail.
        if (economyColumn == null && economies.size() > 1) {
            throw new IllegalArgumentException("economyColumn is null, so the table holds a single"
                    + " economy, but " + economies.size() + " were configured: " + economies);
        }

        if (!(tolerance >= 0)) throw new IllegalArgumentException("tolerance must be >= 0");
        if (!(unexplainedGainThreshold > 0)) {
            throw new IllegalArgumentException("unexplainedGainThreshold must be > 0");
        }
        flushTimeoutSeconds = atLeast("flushTimeoutSeconds", flushTimeoutSeconds, 1);
        expectedFlushSeconds = atLeast("expectedFlushSeconds", expectedFlushSeconds, 1);
        payVerifySeconds = atLeast("payVerifySeconds", payVerifySeconds, 1);
        auditIntervalSeconds = atLeast("auditIntervalSeconds", auditIntervalSeconds, 5);
        joinVerifySeconds = atLeast("joinVerifySeconds", joinVerifySeconds, 1);

        if (expectedFlushSeconds > flushTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "expectedFlushSeconds (" + expectedFlushSeconds + ") cannot exceed flushTimeoutSeconds ("
                            + flushTimeoutSeconds + "): nothing would ever be reported as slow");
        }

        payCommands = normalizePayCommands(payCommands);
    }

    /** Stock zEssentials on SQLite, with {@code plugins/} relative to the server directory. */
    public static DupeWatchSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this instance's values, for tweaking one field. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    // ------------------------------------------------------------------ checks

    private static String identifier(String field, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match " + IDENTIFIER.pattern() + ", got: " + value);
        }
        return value;
    }

    private static String optionalIdentifier(String field, String value) {
        if (value == null || value.isBlank()) return null;
        return identifier(field, value);
    }

    private static int atLeast(String field, int value, int minimum) {
        if (value < minimum) {
            throw new IllegalArgumentException(field + " must be >= " + minimum + ", got " + value);
        }
        return value;
    }

    /** Economy names are data, not identifiers: they are bound as parameters, never spliced. */
    private static Set<String> normalizeEconomies(Set<String> economies) {
        if (economies == null || economies.isEmpty()) {
            throw new IllegalArgumentException("at least one economy is required");
        }
        var normalized = new LinkedHashSet<String>();
        for (String economy : economies) {
            if (economy == null || economy.isBlank()) continue;
            normalized.add(economy.trim());
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("at least one economy is required");
        return Collections.unmodifiableSet(normalized);
    }

    private static Map<String, String> normalizePayCommands(Map<String, String> commands) {
        if (commands == null) return Map.of();
        var normalized = new LinkedHashMap<String, String>();
        for (var entry : commands.entrySet()) {
            String alias = entry.getKey();
            String economy = entry.getValue();
            if (alias == null || alias.isBlank() || economy == null || economy.isBlank()) continue;
            normalized.put(alias.trim().toLowerCase(Locale.ROOT), economy.trim());
        }
        return Map.copyOf(normalized);
    }

    /** The economy a transfer command moves, or {@code null} when the command is not one. */
    public String payEconomy(String alias) {
        return alias == null ? null : this.payCommands.get(alias.toLowerCase(Locale.ROOT));
    }

    /** The first configured economy: the one to use where only one can be named. */
    public String primaryEconomy() {
        return this.economies.iterator().next();
    }

    // ----------------------------------------------------------------- builder

    /** Mutable staging area for a {@link DupeWatchSettings}; every field has a default. */
    public static final class Builder {

        private DatabaseSource source = new DatabaseSource.SqliteFile(DEFAULT_DATABASE);
        private String table = "zessentials_economies";
        private String uuidColumn = "unique_id";
        private String amountColumn = "amount";
        private String economyColumn = "economy_name";
        private Set<String> economies = DEFAULT_ECONOMIES;
        private String usersTable = "zessentials_users";
        private String nameColumn = "name";
        private String updatedColumn = "updated_at";
        private double tolerance = 0.01;
        private double unexplainedGainThreshold = 1_000_000;
        private int flushTimeoutSeconds = 30;
        private int expectedFlushSeconds = 5;
        private int payVerifySeconds = 5;
        private int auditIntervalSeconds = 30;
        private int joinVerifySeconds = 3;
        private Map<String, String> payCommands = DEFAULT_PAY_COMMANDS;

        private Builder() {}

        private Builder(DupeWatchSettings existing) {
            this.source = existing.source();
            this.table = existing.table();
            this.uuidColumn = existing.uuidColumn();
            this.amountColumn = existing.amountColumn();
            this.economyColumn = existing.economyColumn();
            this.economies = existing.economies();
            this.usersTable = existing.usersTable();
            this.nameColumn = existing.nameColumn();
            this.updatedColumn = existing.updatedColumn();
            this.tolerance = existing.tolerance();
            this.unexplainedGainThreshold = existing.unexplainedGainThreshold();
            this.flushTimeoutSeconds = existing.flushTimeoutSeconds();
            this.expectedFlushSeconds = existing.expectedFlushSeconds();
            this.payVerifySeconds = existing.payVerifySeconds();
            this.auditIntervalSeconds = existing.auditIntervalSeconds();
            this.joinVerifySeconds = existing.joinVerifySeconds();
            this.payCommands = existing.payCommands();
        }

        /** Where the tables live: a file, or a JDBC server. */
        public Builder source(DatabaseSource value) { this.source = value; return this; }

        /** Shortcut for the SQLite backend. */
        public Builder databaseFile(Path value) {
            return source(new DatabaseSource.SqliteFile(value));
        }

        /** Shortcut for zEssentials' HIKARICP / MYSQL backend. */
        public Builder mysql(String host, int port, String database, String username, String password) {
            return source(DatabaseSource.Jdbc.mysql(host, port, database, username, password));
        }

        public Builder mariadb(String host, int port, String database, String username, String password) {
            return source(DatabaseSource.Jdbc.mariadb(host, port, database, username, password));
        }

        public Builder jdbc(String url, String username, String password) {
            return source(new DatabaseSource.Jdbc(url, username, password));
        }
        public Builder table(String value) { this.table = value; return this; }
        public Builder uuidColumn(String value) { this.uuidColumn = value; return this; }
        public Builder amountColumn(String value) { this.amountColumn = value; return this; }

        /** {@code null} for a table that holds a single economy and has no discriminator. */
        public Builder economyColumn(String value) { this.economyColumn = value; return this; }
        /** Watch exactly one economy. */
        public Builder economyName(String value) { return economies(value); }

        /** Watch these economies, in this order; the first counts as the primary one. */
        public Builder economies(String... values) {
            return economies(new LinkedHashSet<>(List.of(values)));
        }

        public Builder economies(Set<String> values) { this.economies = values; return this; }
        public Builder usersTable(String value) { this.usersTable = value; return this; }
        public Builder nameColumn(String value) { this.nameColumn = value; return this; }

        /** {@code null} to skip reading when the row was last written. */
        public Builder updatedColumn(String value) { this.updatedColumn = value; return this; }
        public Builder tolerance(double value) { this.tolerance = value; return this; }
        public Builder unexplainedGainThreshold(double value) { this.unexplainedGainThreshold = value; return this; }
        public Builder flushTimeoutSeconds(int value) { this.flushTimeoutSeconds = value; return this; }
        public Builder expectedFlushSeconds(int value) { this.expectedFlushSeconds = value; return this; }

        /**
         * Raise this well past the confirmation menu when payments go through one: the check
         * fires {@code payVerifySeconds} after the command, and a player still staring at a
         * "confirm?" screen has not paid anyone yet.
         */
        public Builder payVerifySeconds(int value) { this.payVerifySeconds = value; return this; }
        public Builder auditIntervalSeconds(int value) { this.auditIntervalSeconds = value; return this; }
        public Builder joinVerifySeconds(int value) { this.joinVerifySeconds = value; return this; }
        /** Declares a transfer command and the economy it moves. */
        public Builder payCommand(String alias, String economy) {
            var updated = new LinkedHashMap<>(this.payCommands);
            updated.put(alias, economy);
            this.payCommands = updated;
            return this;
        }

        public Builder payCommands(Map<String, String> values) { this.payCommands = values; return this; }

        public DupeWatchSettings build() {
            return new DupeWatchSettings(
                    this.source, this.table, this.uuidColumn, this.amountColumn,
                    this.economyColumn, this.economies, this.usersTable, this.nameColumn,
                    this.updatedColumn, this.tolerance, this.unexplainedGainThreshold,
                    this.flushTimeoutSeconds, this.expectedFlushSeconds, this.payVerifySeconds,
                    this.auditIntervalSeconds, this.joinVerifySeconds, this.payCommands);
        }
    }
}
