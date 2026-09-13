package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.modules.web.WebStats;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Every error the plugin produces, kept in memory instead of on the console.
 *
 * <p>Identical failures collapse into one entry with a hit counter — a listener that throws on
 * every block break would otherwise push everything else out of a bounded list within seconds,
 * which is exactly the case where the console log is useless too.
 *
 * <p>Storage only. {@link ErrorCapture} is what diverts the logging away from the console, and
 * {@link DebugModule} is what tells admins and the dashboard that something arrived.
 */
public final class ErrorStore {

    private static final int MAX_ENTRIES = 60;
    private static final int MAX_STACK_CHARS = 8000;
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault());

    /** One failure signature: the first occurrence, plus how often it has repeated since. */
    public static final class Entry {
        private final String id;
        private final String source;
        private final String type;
        private final String message;
        private final String stack;
        private final long firstSeen;
        private volatile long lastSeen;
        private volatile int count;

        private Entry(String id, String source, String type, String message, String stack, long now) {
            this.id = id;
            this.source = source;
            this.type = type;
            this.message = message;
            this.stack = stack;
            this.firstSeen = now;
            this.lastSeen = now;
            this.count = 1;
        }

        public String id() { return id; }
        public String source() { return source; }
        public String type() { return type; }
        public String message() { return message; }
        public String stack() { return stack; }
        public int count() { return count; }
        public long lastSeen() { return lastSeen; }

        /** One line for chat and console — never the stack, and never long enough to wrap twice. */
        public String headline() {
            String detail = message == null || message.isBlank() ? "(nessun messaggio)" : message;
            if (detail.length() > 120) detail = detail.substring(0, 117) + "…";
            return type + ": " + detail.replace('\n', ' ');
        }

        private String json() {
            return "{\"id\":" + WebStats.jsonStr(id)
                    + ",\"source\":" + WebStats.jsonStr(source)
                    + ",\"type\":" + WebStats.jsonStr(type)
                    + ",\"message\":" + WebStats.jsonStr(message)
                    + ",\"stack\":" + WebStats.jsonStr(stack)
                    + ",\"count\":" + count
                    + ",\"first\":" + WebStats.jsonStr(CLOCK.format(Instant.ofEpochMilli(firstSeen)))
                    + ",\"last\":" + WebStats.jsonStr(CLOCK.format(Instant.ofEpochMilli(lastSeen)))
                    + ",\"lastEpoch\":" + lastSeen + "}";
        }
    }

    /** Signature -> entry, newest last. Access is always under {@code entries}'s monitor. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong total = new AtomicLong();

    /** Adds a failure and returns the entry it landed in — a fresh one, or an existing repeat. */
    public Entry record(String source, String message, Throwable thrown) {
        String type = thrown == null ? "Error" : thrown.getClass().getSimpleName();
        String detail = describe(message, thrown);
        String stack = stackOf(message, thrown);
        String signature = source + '|' + type + '|' + detail + '|' + firstFrame(thrown);

        long now = System.currentTimeMillis();
        Entry entry;
        boolean fresh;
        synchronized (entries) {
            Entry existing = entries.get(signature);
            if (existing != null) {
                existing.lastSeen = now;
                existing.count++;
                entry = existing;
                fresh = false;
            } else {
                entry = new Entry(Long.toHexString(now) + '-' + Integer.toHexString(signature.hashCode()),
                        source, type, detail, stack, now);
                entries.put(signature, entry);
                while (entries.size() > MAX_ENTRIES) {
                    entries.remove(entries.keySet().iterator().next());
                }
                fresh = true;
            }
        }

        total.incrementAndGet();
        version.incrementAndGet();
        // Only a new signature is worth interrupting anyone for; a repeat just moves the counter.
        if (fresh) {
            for (Consumer<Entry> listener : listeners) {
                try {
                    listener.accept(entry);
                } catch (Exception ignored) {
                    // a broken listener must never turn error handling into another error
                }
            }
        }
        return entry;
    }

    public void onNewError(Consumer<Entry> listener) {
        listeners.add(listener);
    }

    /** Newest first. */
    public List<Entry> recent() {
        List<Entry> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries.values());
        }
        copy.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return copy;
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** Every failure counted, repeats included — the headline number on the dashboard. */
    public long totalOccurrences() {
        return total.get();
    }

    /** Bumped on every record; the dashboard stream uses it to skip unchanged pushes. */
    public long version() {
        return version.get();
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        total.set(0);
        version.incrementAndGet();
    }

    public String json() {
        StringBuilder json = new StringBuilder("[");
        List<Entry> list = recent();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(',');
            json.append(list.get(i).json());
        }
        return json.append(']').toString();
    }

    /** The evidence block handed to the AI: headline plus a trimmed stack, newest first. */
    public String asEvidence(int limit) {
        StringBuilder text = new StringBuilder();
        List<Entry> list = recent();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            Entry entry = list.get(i);
            text.append("--- errore #").append(i + 1)
                    .append(" (x").append(entry.count).append(", ultima ")
                    .append(CLOCK.format(Instant.ofEpochMilli(entry.lastSeen))).append(", da ")
                    .append(entry.source).append(")\n")
                    .append(entry.stack).append('\n');
        }
        return text.isEmpty() ? "(nessun errore registrato)" : text.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static String describe(String message, Throwable thrown) {
        String thrownMessage = thrown == null ? null : thrown.getMessage();
        if (thrownMessage != null && !thrownMessage.isBlank()) return thrownMessage;
        return message == null ? "" : message;
    }

    /** The first stack frame belonging to this plugin, else the very first frame. */
    private static String firstFrame(Throwable thrown) {
        if (thrown == null) return "";
        StackTraceElement[] frames = thrown.getStackTrace();
        if (frames.length == 0) return "";
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith("dev.lorenzzz.smplibrary")) return frame.toString();
        }
        return frames[0].toString();
    }

    private static String stackOf(String message, Throwable thrown) {
        StringWriter writer = new StringWriter();
        if (message != null && !message.isBlank()) writer.write(message + "\n");
        if (thrown != null) {
            try (PrintWriter print = new PrintWriter(writer)) {
                thrown.printStackTrace(print);
            }
        }
        String stack = writer.toString();
        return stack.length() <= MAX_STACK_CHARS
                ? stack
                : stack.substring(0, MAX_STACK_CHARS) + "\n… (stack troncato)";
    }

    /** Counts by source, for the dashboard's summary row. */
    public Map<String, Integer> bySource() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry entry : recent()) {
            counts.merge(entry.source, entry.count, Integer::sum);
        }
        return counts;
    }
}
