package dev.lorenzzz.smplibrary.modules.pay;

/**
 * How one {@code /pistacchipay} ended. There is deliberately no "probably went through" value:
 * a transfer
 * is either {@link #COMPLETED} — the receiver holds the money — or it is not, and every way of
 * not completing says which one it was, so the command can tell the player something true.
 */
public enum TransferResult {

    /** Money left the sender and reached the receiver. The only success. */
    COMPLETED,

    /** The sender could not cover the amount at the moment the wallet was locked. */
    INSUFFICIENT_FUNDS,

    /** The sender paid too recently; {@code cooldown-seconds} has not elapsed yet. */
    COOLDOWN,

    /** Another transfer from this sender is mid-flight. Nothing was charged. */
    IN_PROGRESS,

    /** This transfer id already ran. Nothing was charged a second time. */
    DUPLICATE,

    /** The economy refused the operation. Any partial debit has been rolled back. */
    FAILED;

    public boolean completed() {
        return this == COMPLETED;
    }
}
