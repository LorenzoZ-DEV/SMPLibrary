package dev.lorenzzz.smplibrary.security.dupe;

import java.util.Locale;
import java.util.UUID;

/**
 * One anomaly found by {@link EconomyDupeWatcher}.
 *
 * <p>An alert is a statement about what was measured, never a verdict: the watcher cannot tell
 * an exploit from a persistence bug, and the two look identical from the outside. Whoever
 * consumes these decides what to do with them.
 *
 * <p>{@code playerId} and {@code playerName} are {@code null} for the two system-level types —
 * a slow flush or an unreadable database is the server's problem, and pinning it on whoever
 * happened to be online would be misleading.
 *
 * @param economy  which currency this is about; zEssentials keeps several side by side and
 *                 "the balance is wrong" means nothing without saying which balance
 * @param expected the value that should be there (session balance, or the debited amount)
 * @param actual   the value actually observed (disk balance, or the credited amount)
 * @param detail   free text: what was compared, and anything that narrows down the cause
 */
public record DupeAlert(
        Type type,
        UUID playerId,
        String playerName,
        String economy,
        double expected,
        double actual,
        String detail
) {

    /** How much attention the anomaly deserves. */
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * The kinds of anomaly this watcher can tell apart.
     *
     * <p>The {@code code} of each is a stable identifier — it ends up in logs, in chat and in
     * the screenshots staff send each other, so it must never be renamed even if the enum
     * constant is.
     */
    public enum Type {

        /** Session balance and the value on disk disagree: the write never landed. */
        PERSISTENCE_MISMATCH(Severity.CRITICAL, "MoneyDupeA", "saldo di sessione diverso da quello su disco"),

        /** Someone was credited without anyone being debited. */
        MONEY_CREATED(Severity.CRITICAL, "MoneyDupeB", "accredito senza addebito corrispondente"),

        /** Someone was debited and the recipient never got it. */
        PAY_NOT_CREDITED(Severity.HIGH, "MoneyDupeC", "addebito senza accredito"),

        /** A jump in balance that no observed transaction accounts for. */
        UNEXPLAINED_GAIN(Severity.MEDIUM, "MoneyDupeD", "aumento non riconducibile a transazioni osservate"),

        /** The value does reach the disk, just later than it should. */
        SLOW_FLUSH(Severity.MEDIUM, "StorageLagA", "flush oltre la soglia di latenza attesa"),

        /** The storage could not be read at all, so disk-side checks are blind. */
        PROBE_UNAVAILABLE(Severity.LOW, "StorageProbeA", "impossibile leggere lo storage");

        private final Severity severity;
        private final String code;
        private final String description;

        Type(Severity severity, String code, String description) {
            this.severity = severity;
            this.code = code;
            this.description = description;
        }

        public Severity severity() { return this.severity; }

        /** Stable check code — safe to grep for, never renamed. */
        public String code() { return this.code; }

        public String description() { return this.description; }

        /** True for anomalies that are about the server, not about a player. */
        public boolean system() {
            return this == SLOW_FLUSH || this == PROBE_UNAVAILABLE;
        }
    }

    public DupeAlert {
        if (type == null) throw new IllegalArgumentException("type is required");
        detail = detail == null ? "" : detail;
        economy = economy == null ? "" : economy;
    }

    /** Player-scoped alert. */
    public static DupeAlert of(Type type, UUID playerId, String playerName, String economy,
                               double expected, double actual, String detail) {
        return new DupeAlert(type, playerId, playerName, economy, expected, actual, detail);
    }

    /** Server-scoped alert: no player is named, and no violation level is accumulated. */
    public static DupeAlert system(Type type, String economy, double expected, double actual, String detail) {
        return new DupeAlert(type, null, null, economy, expected, actual, detail);
    }

    /** Observed minus expected: positive means money appeared, negative means it vanished. */
    public double delta() {
        return this.actual - this.expected;
    }

    public boolean critical() {
        return this.type.severity() == Severity.CRITICAL;
    }

    /** The player's name, or a placeholder when the alert is not about anyone. */
    public String describeSubject() {
        if (this.playerName != null) return this.playerName;
        return this.playerId != null ? this.playerId.toString() : "-";
    }

    /** Plain two-decimal rendering; the numbers here are money, not measurements. */
    public static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public String toString() {
        return "[" + this.type.code() + "/" + this.type.severity() + "] "
                + describeSubject()
                + (this.economy.isEmpty() ? "" : " (" + this.economy + ")")
                + " atteso=" + format(this.expected)
                + " effettivo=" + format(this.actual)
                + " delta=" + format(delta())
                + (this.detail.isEmpty() ? "" : " — " + this.detail);
    }
}
