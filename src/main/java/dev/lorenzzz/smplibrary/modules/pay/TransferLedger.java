package dev.lorenzzz.smplibrary.modules.pay;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bookkeeping half of a transfer: which ids have already run, which wallets are mid-flight,
 * and when each player last paid. It holds no money and talks to no economy — {@link PayModule}
 * asks it for permission, does the actual move, then reports back.
 *
 * <p>Three separate guards, because they answer three different questions:
 *
 * <ul>
 *   <li><b>id</b> — "has this exact transfer already happened?". Every {@code /pistacchipay}
 *       mints one id
 *       and it can only be spent once, so a duplicated request (a double dispatch, a retry, a
 *       replayed packet) is recognised as the same transfer rather than executed as a second one.
 *   <li><b>in-flight wallet</b> — "is this player already paying someone?". Two commands racing
 *       from the same sender are serialized here rather than in the economy, so the second one is
 *       refused outright instead of reading a balance the first is about to change.
 *   <li><b>cooldown</b> — the extra belt on top of the braces. It is not what makes the transfer
 *       safe; it just keeps a player from hammering the economy.
 * </ul>
 *
 * <p>All three are touched from the command thread and, in principle, from anywhere a plugin
 * decides to call {@code /pistacchipay}, hence the concurrent structures.
 */
final class TransferLedger {

    /**
     * How many settled transfer ids to keep. Only needed long enough to recognise a duplicate of
     * a request that is still in the air, so this is generous by a wide margin; the cap exists so
     * a long-running server does not grow the map forever.
     */
    private static final int HISTORY_SIZE = 10_000;

    /** Ids that are running or have run. Value is the epoch millis the id was claimed. */
    private final Map<UUID, Long> settled = Collections.synchronizedMap(
            new LinkedHashMap<UUID, Long>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
                    return size() > HISTORY_SIZE;
                }
            });

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastTransfer = new ConcurrentHashMap<>();

    /**
     * Claims a transfer id. False means it was claimed before — the caller must not move any
     * money, the transfer it describes either already happened or is happening right now.
     */
    boolean claim(UUID transferId) {
        return settled.putIfAbsent(transferId, System.currentTimeMillis()) == null;
    }

    /**
     * Releases an id claimed by {@link #claim} that never moved any money, so the player can try
     * the same payment again. Only ever called on a path that charged nothing.
     */
    void release(UUID transferId) {
        settled.remove(transferId);
    }

    /** Takes the sender's wallet; false when another transfer of theirs is still running. */
    boolean lock(UUID payer) {
        return inFlight.add(payer);
    }

    void unlock(UUID payer) {
        inFlight.remove(payer);
    }

    /** True when {@code payer} paid less than {@code cooldownMillis} ago. */
    boolean onCooldown(UUID payer, long cooldownMillis) {
        if (cooldownMillis <= 0) return false;
        Long last = lastTransfer.get(payer);
        return last != null && System.currentTimeMillis() - last < cooldownMillis;
    }

    /** Milliseconds left on {@code payer}'s cooldown; 0 when they can pay now. */
    long cooldownRemaining(UUID payer, long cooldownMillis) {
        if (cooldownMillis <= 0) return 0;
        Long last = lastTransfer.get(payer);
        if (last == null) return 0;
        return Math.max(0, cooldownMillis - (System.currentTimeMillis() - last));
    }

    /** Starts the cooldown. Called only after a transfer actually completed. */
    void markCompleted(UUID payer) {
        lastTransfer.put(payer, System.currentTimeMillis());
    }

    /** Drops every player-scoped entry; the id history is kept. Used on module shutdown. */
    void clearPlayers() {
        inFlight.clear();
        lastTransfer.clear();
    }
}
