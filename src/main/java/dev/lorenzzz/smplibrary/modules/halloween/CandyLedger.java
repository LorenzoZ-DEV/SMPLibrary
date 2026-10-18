package dev.lorenzzz.smplibrary.modules.halloween;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;

/**
 * Who collected how many candies, kept in {@code playerdata/halloween.yml}.
 *
 * <p>A file and not the database on purpose: the counters are the whole event — the daily
 * leaderboard, the milestone rewards and the "already had your trick-or-treat today" flag all
 * read them — and this server's storage pool is not a given. Same reasoning as
 * {@link dev.lorenzzz.smplibrary.manager.ToggleManager}'s file backend, except there is no
 * database branch here at all: a handful of integers per player is not worth a schema.
 *
 * <p>Two counters per player, not one. {@code candies} is the season total, which is what the
 * milestone rewards and the season leaderboard are about; {@code today} is cleared at every
 * rollover and is what the daily top collector is crowned on. The day those {@code today}
 * values belong to is stored with them, so a server that was offline across midnight rolls
 * over on the first tick after it comes back instead of crowning a winner from two days ago.
 */
public final class CandyLedger {

    /** Where the counters live, relative to the plugin data folder. */
    static final String FILE_PATH = "playerdata/halloween.yml";

    /** One player's season. Mutated on the main thread, read by the async flush. */
    public static final class Season {
        private volatile String name;
        private volatile int candies;
        private volatile int today;
        private volatile int paidMilestone;
        private volatile String claimedDay;
        private final ConcurrentLinkedDeque<Credit> recent = new ConcurrentLinkedDeque<>();

        public String name() { return name; }
        public int candies() { return candies; }
        public int today() { return today; }
        public int paidMilestone() { return paidMilestone; }
    }

    /** One credit, as shown by {@code /halloween stats}. {@code at} is epoch millis. */
    public record Credit(long at, int amount) {}

    /** How many credits are kept per player. */
    private static final int RECENT = 5;

    /** A line of the leaderboard. */
    public record Standing(String name, int candies) {}

    private final File file;
    private final Map<UUID, Season> seasons = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** The day {@link Season#today} belongs to; {@code null} before the first rollover. */
    private volatile LocalDate day;

    /** Which run of the season the counters belong to; 0 before the first one is opened. */
    private volatile int seasonYear;

    /** Event an admin pinned for {@link #day}, or {@code null} to let the date decide. */
    private volatile DailyEvent override;

    public CandyLedger(Smp_library plugin) {
        this.file = new File(plugin.getDataFolder(), FILE_PATH);
    }

    // ------------------------------------------------------------------ disk

    /** Reads the file, or starts an empty season when there is none yet. */
    public void load() {
        seasons.clear();
        override = null;
        day = null;
        if (!file.isFile()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        day = parseDay(yaml.getString("day"));
        seasonYear = yaml.getInt("season-year");
        override = DailyEvent.parse(yaml.getString("event-override"));

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID playerId = parseId(key);
            ConfigurationSection row = players.getConfigurationSection(key);
            if (playerId == null || row == null) continue;

            Season season = new Season();
            season.name = row.getString("name", key);
            season.candies = Math.max(0, row.getInt("candies"));
            season.today = Math.max(0, row.getInt("today"));
            season.paidMilestone = Math.max(0, row.getInt("paid-milestone"));
            season.claimedDay = row.getString("claimed");
            for (String raw : row.getStringList("recent")) {
                String[] parts = raw.split(":");
                try {
                    season.recent.addLast(new Credit(Long.parseLong(parts[0]), Integer.parseInt(parts[1])));
                } catch (RuntimeException malformed) {
                    // hand-edited file: skip the bad line, keep the rest
                }
            }
            seasons.put(playerId, season);
        }
    }

    /** Writes the file, but only when something changed since the last write. */
    public void flushIfDirty() {
        if (dirty.compareAndSet(true, false)) write();
    }

    /**
     * Writes unconditionally, waiting for any write already in flight.
     *
     * <p>For shutdown. {@link #flushIfDirty()} is the wrong call there: the periodic save runs
     * off the main thread, and if it has already taken the dirty flag but is still serialising,
     * flushIfDirty() sees a clean ledger and returns — so disable would walk away from a
     * half-written file.
     */
    public void flush() {
        dirty.set(false);
        write();
    }

    // synchronized: the periodic save writes from an async task and shutdown writes from the
    // main thread. Two threads inside this method at once interleave their lines in the same
    // file, which is how a season of candy turns into an unparsable yaml.
    private synchronized void write() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("day", day == null ? null : day.toString());
        yaml.set("season-year", seasonYear == 0 ? null : seasonYear);
        yaml.set("event-override", override == null ? null : override.key());
        for (Map.Entry<UUID, Season> entry : seasons.entrySet()) {
            String path = "players." + entry.getKey();
            Season season = entry.getValue();
            yaml.set(path + ".name", season.name);
            yaml.set(path + ".candies", season.candies);
            yaml.set(path + ".today", season.today);
            yaml.set(path + ".paid-milestone", season.paidMilestone);
            yaml.set(path + ".claimed", season.claimedDay);
            yaml.set(path + ".recent", season.recent.stream().map(c -> c.at() + ":" + c.amount()).toList());
        }

        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            yaml.save(file);
        } catch (IOException e) {
            // Dirty again on purpose: the next pass (or the shutdown flush) retries the write.
            dirty.set(true);
            Log.warning("halloween: impossibile scrivere " + FILE_PATH + " (" + e.getMessage() + ")");
        }
    }

    // ----------------------------------------------------------------- counts

    /** The player's row, created on first sight. The stored name is refreshed as we go. */
    public Season of(UUID playerId, String name) {
        Season season = seasons.computeIfAbsent(playerId, id -> {
            dirty.set(true);
            return new Season();
        });
        if (name != null && !name.equals(season.name)) {
            season.name = name;
            dirty.set(true);
        }
        return season;
    }

    public int candies(UUID playerId) {
        Season season = seasons.get(playerId);
        return season == null ? 0 : season.candies;
    }

    public int today(UUID playerId) {
        Season season = seasons.get(playerId);
        return season == null ? 0 : season.today;
    }

    /** Credits candies to both counters and returns the new season total. */
    public int add(UUID playerId, String name, int amount) {
        Season season = of(playerId, name);
        season.candies = Math.max(0, season.candies + amount);
        season.today = Math.max(0, season.today + amount);
        season.recent.addFirst(new Credit(System.currentTimeMillis(), amount));
        while (season.recent.size() > RECENT) season.recent.removeLast();
        dirty.set(true);
        return season.candies;
    }

    /** The player's last credits, newest first. */
    public List<Credit> recent(UUID playerId) {
        Season season = seasons.get(playerId);
        return season == null ? List.of() : new ArrayList<>(season.recent);
    }

    /** Records the milestone already paid for, so a reward is never handed out twice. */
    public void paidMilestone(UUID playerId, String name, int milestone) {
        of(playerId, name).paidMilestone = milestone;
        dirty.set(true);
    }

    // --------------------------------------------------------- trick or treat

    /** True when this player still has today's trick-or-treat to spend. */
    public boolean canClaim(UUID playerId, LocalDate today) {
        Season season = seasons.get(playerId);
        return season == null || !today.toString().equals(season.claimedDay);
    }

    /** Burns today's trick-or-treat; false when it was already spent. */
    public boolean claim(UUID playerId, String name, LocalDate today) {
        Season season = of(playerId, name);
        if (today.toString().equals(season.claimedDay)) return false;
        season.claimedDay = today.toString();
        dirty.set(true);
        return true;
    }

    // ----------------------------------------------------------- the calendar

    /** The day the {@code today} counters belong to, {@code null} before the first rollover. */
    public LocalDate day() {
        return day;
    }

    /** Moves the ledger to {@code newDay}: daily counters and the pinned event are cleared. */
    public void rollTo(LocalDate newDay) {
        day = newDay;
        override = null;
        for (Season season : seasons.values()) season.today = 0;
        dirty.set(true);
    }

    /** Which run of the season the counters belong to; 0 when no season has been opened yet. */
    public int seasonYear() {
        return seasonYear;
    }

    /**
     * Opens a new run of the season: the counters go back to zero, because a leaderboard that
     * still holds last October's totals is nobody's leaderboard — the milestone rewards would
     * be unreachable too, with every returning player already past them.
     *
     * <p>Last season is not thrown away: the file is copied to {@code halloween-<year>.yml}
     * first, so whoever won it can still be looked up next year.
     */
    public void startSeason(int year) {
        if (seasonYear != 0 && seasonYear != year && !seasons.isEmpty()) archive();
        seasons.clear();
        day = null;
        override = null;
        seasonYear = year;
        dirty.set(true);
    }

    private void archive() {
        write(); // the copy below is of the file, so the file has to be current first
        if (!file.isFile()) return;

        File archive = new File(file.getParentFile(), "halloween-" + seasonYear + ".yml");
        try {
            Files.copy(file.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.info("halloween: stagione " + seasonYear + " archiviata in " + archive.getName() + ".");
        } catch (IOException e) {
            Log.warning("halloween: impossibile archiviare la stagione " + seasonYear
                    + " (" + e.getMessage() + ") — i contatori vengono azzerati comunque.");
        }
    }

    public DailyEvent override() {
        return override;
    }

    /** Pins today's event (admin command). Cleared by the next rollover. */
    public void override(DailyEvent event) {
        override = event;
        dirty.set(true);
    }

    // ------------------------------------------------------------ leaderboard

    /** Top collectors of the current day, best first, skipping anyone still at zero. */
    public List<Standing> topToday(int limit) {
        return top(limit, Season::today);
    }

    /** Top collectors of the whole season, best first. */
    public List<Standing> topSeason(int limit) {
        return top(limit, Season::candies);
    }

    private List<Standing> top(int limit, ToIntFunction<Season> value) {
        List<Standing> standings = new ArrayList<>();
        for (Season season : seasons.values()) {
            int candies = value.applyAsInt(season);
            if (candies > 0) {
                standings.add(new Standing(season.name == null ? "?" : season.name, candies));
            }
        }
        standings.sort(Comparator.comparingInt(Standing::candies).reversed());
        return standings.size() <= limit ? standings : new ArrayList<>(standings.subList(0, limit));
    }

    /** Drops the in-memory state. The file keeps everything. */
    public void clear() {
        seasons.clear();
    }

    private static LocalDate parseDay(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException malformed) {
            Log.warning("halloween: data illeggibile in " + FILE_PATH + " (" + raw + ")");
            return null;
        }
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
