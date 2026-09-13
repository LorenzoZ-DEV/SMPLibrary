package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bug reports, kept in {@code bugreports.yml} next to the other plugin files.
 *
 * <p>Deliberately not in the database: the pool is not reachable on the live server, and a
 * report that only exists until the next restart is worse than useless — the player has already
 * been told it was filed. A flat file has no such failure mode. Reads are served from an
 * in-memory copy; writes rewrite the file on an async task, so the submit path never blocks a
 * tick on disk I/O.
 */
public final class BugReportStore {

    private static final String FILE = "bugreports.yml";
    private static final int MAX_REPORTS = 500;

    private final Smp_library plugin;
    private final File file;
    private final List<BugReport> reports = new ArrayList<>();   // newest first
    private final AtomicLong version = new AtomicLong();

    public BugReportStore(Smp_library plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    // ------------------------------------------------------------------ load

    public void load() {
        synchronized (reports) {
            reports.clear();
            if (!file.exists()) return;

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yaml.getConfigurationSection("reports");
            if (root == null) return;

            for (String id : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(id);
                if (entry == null) continue;
                reports.add(new BugReport(id,
                        entry.getString("author", "?"),
                        entry.getString("author-id", ""),
                        entry.getString("type", "altro"),
                        entry.getString("title", ""),
                        entry.getString("body", ""),
                        entry.getString("media", ""),
                        entry.getString("context", ""),
                        entry.getLong("created-at"),
                        entry.getString("status", BugReport.OPEN)));
            }
            reports.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        }
        version.incrementAndGet();
    }

    // ----------------------------------------------------------------- reads

    /** Newest first. */
    public List<BugReport> all() {
        synchronized (reports) {
            return List.copyOf(reports);
        }
    }

    public BugReport byId(String id) {
        synchronized (reports) {
            for (BugReport report : reports) {
                if (report.id().equals(id)) return report;
            }
        }
        return null;
    }

    public int openCount() {
        synchronized (reports) {
            return (int) reports.stream().filter(r -> BugReport.OPEN.equals(r.status())).count();
        }
    }

    public int size() {
        synchronized (reports) {
            return reports.size();
        }
    }

    public long version() {
        return version.get();
    }

    public String json() {
        StringBuilder json = new StringBuilder("[");
        List<BugReport> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(',');
            json.append(list.get(i).json());
        }
        return json.append(']').toString();
    }

    // ---------------------------------------------------------------- writes

    /** Files a report and returns it with its assigned id. */
    public BugReport add(BugReport report) {
        BugReport stored = report.id() == null || report.id().isBlank()
                ? new BugReport(nextId(), report.author(), report.authorId(), report.type(),
                        report.title(), report.body(), report.media(), report.context(),
                        report.createdAt(), report.status())
                : report;

        synchronized (reports) {
            reports.add(0, stored);
            while (reports.size() > MAX_REPORTS) reports.remove(reports.size() - 1);
        }
        version.incrementAndGet();
        saveAsync();
        return stored;
    }

    /** Returns the updated report, or {@code null} when the id is unknown. */
    public BugReport setStatus(String id, String status) {
        BugReport updated = null;
        synchronized (reports) {
            for (int i = 0; i < reports.size(); i++) {
                if (!reports.get(i).id().equals(id)) continue;
                updated = reports.get(i).withStatus(status);
                reports.set(i, updated);
                break;
            }
        }
        if (updated != null) {
            version.incrementAndGet();
            saveAsync();
        }
        return updated;
    }

    public boolean delete(String id) {
        boolean removed;
        synchronized (reports) {
            removed = reports.removeIf(report -> report.id().equals(id));
        }
        if (removed) {
            version.incrementAndGet();
            saveAsync();
        }
        return removed;
    }

    private String nextId() {
        return Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + Integer.toString((int) (Math.random() * 1296), 36).toUpperCase();
    }

    private void saveAsync() {
        // Snapshot under the lock, write outside it — the file write must not hold up a submit.
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        for (BugReport report : all()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("author", report.author());
            values.put("author-id", report.authorId());
            values.put("type", report.type());
            values.put("title", report.title());
            values.put("body", report.body());
            values.put("media", report.media());
            values.put("context", report.context());
            values.put("created-at", report.createdAt());
            values.put("status", report.status());
            snapshot.put(report.id(), values);
        }

        Runnable write = () -> writeFile(snapshot);
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();   // shutting down: the report still has to land on disk
        }
    }

    private void writeFile(Map<String, Map<String, Object>> snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Object>> entry : snapshot.entrySet()) {
            for (Map.Entry<String, Object> field : entry.getValue().entrySet()) {
                yaml.set("reports." + entry.getKey() + "." + field.getKey(), field.getValue());
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                C.warning("bugreport: non riesco a creare " + parent);
                return;
            }
            yaml.save(file);
        } catch (IOException e) {
            C.warning("bugreport: salvataggio di " + FILE + " fallito: " + e.getMessage());
        }
    }
}
