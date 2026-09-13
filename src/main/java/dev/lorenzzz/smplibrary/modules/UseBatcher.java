package dev.lorenzzz.smplibrary.modules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batches the "spend one use" writes of the custom tools.
 *
 * <p>Rewriting the held stack changes its item components, and the vanilla client throws away the
 * block-breaking progress of whatever it is mining whenever the components of the held item
 * change. Decrementing the counter on the item on every single break therefore makes continuous
 * mining stutter, and on a fast tool — where a block takes a tick or two — it can stall the break
 * outright: the player hammers a block that never gives. The uses are counted here in memory
 * instead and pushed onto the item at most once per {@code intervalMs}, so digging a tunnel costs
 * one resync per second rather than one per block.
 *
 * <p>Booked uses that never get written (a crash, the tool leaving the hand mid-batch) are simply
 * not spent: at most one interval's worth, against budgets in the hundreds. Below {@link #FLOOR}
 * remaining uses every break writes through, so the countdown a player watches run out is exact.
 *
 * <p>State is keyed by player, not by item — a player holds one tool at a time. The callers flush
 * on quit and when the held slot changes so the booked uses land on the stack that earned them.
 */
public final class UseBatcher {

    /** Below this many remaining uses, every break writes through instead of batching. */
    private static final int FLOOR = 20;

    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWrite = new ConcurrentHashMap<>();
    private final long intervalMs;

    public UseBatcher(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /**
     * Books one use for {@code playerId} and decides whether the item should be rewritten now.
     *
     * @param remaining the use count currently written on the item
     * @return how many uses to subtract from the item now, or {@code 0} to keep batching
     */
    public int spend(UUID playerId, int remaining) {
        int booked = pending.merge(playerId, 1, Integer::sum);
        long now = System.currentTimeMillis();
        // No entry means this is the first break of a batch: write it through, so a player who
        // breaks one block and looks at the lore sees the tool tick down.
        long last = lastWrite.getOrDefault(playerId, 0L);
        if (remaining - booked > FLOOR && now - last < intervalMs) return 0;

        pending.remove(playerId);
        lastWrite.put(playerId, now);
        return booked;
    }

    /** Uses booked but not yet written, left in place. */
    public int peek(UUID playerId) {
        return pending.getOrDefault(playerId, 0);
    }

    /** Takes the booked uses and clears them — for a write happening outside {@link #spend}. */
    public int drain(UUID playerId) {
        Integer booked = pending.remove(playerId);
        lastWrite.put(playerId, System.currentTimeMillis());
        return booked == null ? 0 : booked;
    }

    /** Drops whatever this player had booked (the tool is gone, or the uses were just written). */
    public void forget(UUID playerId) {
        pending.remove(playerId);
        lastWrite.remove(playerId);
    }

    public void clear() {
        pending.clear();
        lastWrite.clear();
    }
}
