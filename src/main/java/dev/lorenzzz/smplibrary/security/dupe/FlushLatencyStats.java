package dev.lorenzzz.smplibrary.security.dupe;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How long the economy actually takes to reach the disk.
 *
 * <p>This is the number the whole module exists to produce. "The balance is wrong sometimes"
 * is not something you can act on; "the median quit takes 11 seconds to persist and one in
 * twenty never does" tells you whether the write-behind cache needs fixing or whether the
 * writes should simply move onto the main thread and be done with.
 *
 * <p>Written from the watcher's single scheduler thread and read from anywhere, hence the
 * atomics. A {@link Snapshot} is taken field by field, so a sample landing mid-read can leave
 * the average very slightly out of step with the count — irrelevant at this resolution, and
 * cheaper than locking a hot path that is not hot.
 */
public final class FlushLatencyStats {

    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong totalMillis = new AtomicLong();
    private final AtomicLong minMillis = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxMillis = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();

    /** An immutable reading; {@code samples == 0} means nothing has been measured yet. */
    public record Snapshot(long samples, long minMillis, double averageMillis, long maxMillis, long timeouts) {

        /** Average over the samples that did land, or empty when there are none. */
        public OptionalDouble average() {
            return this.samples == 0 ? OptionalDouble.empty() : OptionalDouble.of(this.averageMillis);
        }

        @Override
        public String toString() {
            if (this.samples == 0) {
                return "nessun flush misurato" + (this.timeouts == 0 ? "" : ", " + this.timeouts + " mai allineati");
            }
            return String.format(Locale.ROOT,
                    "%d flush misurati — min %.2fs, media %.2fs, max %.2fs, %d mai allineati",
                    this.samples, this.minMillis / 1000d, this.averageMillis / 1000d,
                    this.maxMillis / 1000d, this.timeouts);
        }
    }

    /** Records one flush that completed, however slowly. */
    void record(long millis) {
        if (millis < 0) return;
        this.samples.incrementAndGet();
        this.totalMillis.addAndGet(millis);
        this.minMillis.accumulateAndGet(millis, Math::min);
        this.maxMillis.accumulateAndGet(millis, Math::max);
    }

    /**
     * Records a flush that never happened. Kept apart from the timings on purpose: folding a
     * timeout in as "30 seconds" would quietly turn a lost write into a slow one.
     */
    void recordTimeout() {
        this.timeouts.incrementAndGet();
    }

    /** Current reading. Safe from any thread. */
    public Snapshot snapshot() {
        long count = this.samples.get();
        long total = this.totalMillis.get();
        long min = this.minMillis.get();
        return new Snapshot(
                count,
                count == 0 ? 0 : min,
                count == 0 ? 0 : (double) total / count,
                this.maxMillis.get(),
                this.timeouts.get());
    }

    /** Drops everything measured so far — for a "start counting again from here" command. */
    public void reset() {
        this.samples.set(0);
        this.totalMillis.set(0);
        this.minMillis.set(Long.MAX_VALUE);
        this.maxMillis.set(0);
        this.timeouts.set(0);
    }
}
