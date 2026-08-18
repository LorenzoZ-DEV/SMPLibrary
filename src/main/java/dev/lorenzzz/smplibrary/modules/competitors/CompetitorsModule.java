package dev.lorenzzz.smplibrary.modules.competitors;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.web.WebStats;
import dev.lorenzzz.smplibrary.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Keeps an eye on the competition: a list of rival server addresses, pinged on a timer, ranked
 * against this server's own player count so the dashboard can say plainly when one of them is
 * ahead.
 *
 * <p>The list lives in config.yml under {@code MODULES.competitors.servers} and is edited from
 * the dashboard's Competitors tab — no database, so it survives a restart even on a box where
 * the storage pool is down.
 *
 * <p>Pings are blocking socket work and never touch the server thread: a small daemon pool owned
 * by this module runs them, one task per rival so a single dead host cannot delay the others.
 */
public final class CompetitorsModule extends AbstractModule {

    private static final long DEFAULT_PERIOD = 2 * Ticks.MINUTE;
    /** Floor on the cadence — every check is real network traffic aimed at someone else's box. */
    private static final long MINIMUM_PERIOD = 30 * Ticks.SECOND;

    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int MIN_TIMEOUT_MS = 500;
    private static final int MAX_TIMEOUT_MS = 15_000;

    private static final int DEFAULT_PORT = 25565;
    private static final int MAX_RIVALS = 40;
    private static final int MAX_NAME_LENGTH = 40;
    private static final int MAX_WORKERS = 6;

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$");

    private static final String SERVERS_PATH = "MODULES.competitors.servers";

    /** One configured rival. {@code explicitPort} false means a client would follow SRV. */
    public record Rival(String id, String name, String host, int port, boolean explicitPort) {}

    /** The last thing a rival answered. {@code error} non-null means it was unreachable. */
    public record Result(ServerPinger.Status status, String error, long checkedAt) {}

    private final List<Rival> rivals = new CopyOnWriteArrayList<>();
    private final Map<String, Result> results = new ConcurrentHashMap<>();
    /** Rivals with a ping in flight, so a slow host is not queued twice by the next cycle. */
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    private ScheduledExecutorService pool;
    private String ourName = "Il mio server";
    private long periodMs = 120_000L;
    private int timeoutMs = DEFAULT_TIMEOUT_MS;

    public CompetitorsModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "competitors"; }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();

        long periodTicks = Ticks.parseAtLeast(
                cfg == null ? null : cfg.getString("check-every"), DEFAULT_PERIOD, MINIMUM_PERIOD);
        periodMs = periodTicks / Ticks.SECOND * 1000L;
        timeoutMs = clamp(cfg == null ? DEFAULT_TIMEOUT_MS : cfg.getInt("timeout-ms", DEFAULT_TIMEOUT_MS),
                MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        ourName = cfg == null ? ourName : cfg.getString("your-name", ourName);

        rivals.addAll(readRivals(cfg));

        pool = Executors.newScheduledThreadPool(MAX_WORKERS, runnable -> {
            Thread thread = new Thread(runnable, "smplibrary-competitors");
            thread.setDaemon(true);
            return thread;
        });
        // First sweep a few seconds in: on boot the network stack and any proxy in front of the
        // rivals are still settling, and a false "offline" on the first paint reads as a bug.
        pool.scheduleAtFixedRate(this::pingAll, 5_000L, periodMs, TimeUnit.MILLISECONDS);

        C.info("Competitors enabled — watching " + rivals.size()
                + " server(s) every " + (periodMs / 1000L) + "s.");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Reads {@code servers:} — a list of {@code {name, ip}} maps. Bad rows are skipped, not fatal. */
    private static List<Rival> readRivals(ConfigurationSection cfg) {
        List<Rival> parsed = new ArrayList<>();
        if (cfg == null) return parsed;

        for (Map<?, ?> row : cfg.getMapList("servers")) {
            Object ip = row.get("ip");
            if (ip == null) continue;

            Object name = row.get("name");
            Rival rival = parse(name == null ? null : String.valueOf(name), String.valueOf(ip));
            if (rival == null) {
                C.warning("competitors: skipping unusable entry '" + ip + "'.");
                continue;
            }
            if (parsed.stream().noneMatch(existing -> existing.id().equals(rival.id()))) parsed.add(rival);
        }
        return parsed;
    }

    /**
     * Builds a rival from what the dashboard form (or config.yml) says, or null when the
     * address cannot be one. Accepts {@code host}, {@code host:port} and a pasted
     * {@code minecraft://host} — server owners advertise all three.
     */
    private static Rival parse(String name, String ip) {
        String address = ip == null ? "" : ip.trim();
        int scheme = address.indexOf("://");
        if (scheme >= 0) address = address.substring(scheme + 3);
        if (address.endsWith("/")) address = address.substring(0, address.length() - 1);
        if (address.isEmpty()) return null;

        String host = address;
        int port = DEFAULT_PORT;
        boolean explicitPort = false;

        int colon = address.lastIndexOf(':');
        if (colon >= 0) {
            host = address.substring(0, colon);
            try {
                port = Integer.parseInt(address.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (port < 1 || port > 65535) return null;
            explicitPort = true;
        }

        host = host.trim();
        if (!HOST.matcher(host).matches()) return null;

        String id = host.toLowerCase(Locale.ROOT) + ':' + port;
        String label = name == null || name.isBlank() ? host : name.trim();
        if (label.length() > MAX_NAME_LENGTH) label = label.substring(0, MAX_NAME_LENGTH);
        return new Rival(id, label, host, port, explicitPort);
    }

    // ----------------------------------------------------------------- pings

    /** Queues one ping per rival. Runs on the pool's scheduler thread; must not block. */
    private void pingAll() {
        for (Rival rival : rivals) {
            // putIfAbsent doubles as the "already queued" test, so a host that is timing out
            // every cycle cannot pile up tasks faster than the pool drains them.
            if (inFlight.putIfAbsent(rival.id(), Boolean.TRUE) != null) continue;
            pool.execute(() -> {
                try {
                    pingOne(rival);
                } finally {
                    inFlight.remove(rival.id());
                }
            });
        }
    }

    private void pingOne(Rival rival) {
        try {
            ServerPinger.Status status = ServerPinger.ping(
                    rival.host(), rival.port(), timeoutMs, !rival.explicitPort());
            results.put(rival.id(), new Result(status, null, System.currentTimeMillis()));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            results.put(rival.id(), new Result(null, reason, System.currentTimeMillis()));
        }
    }

    /** Re-pings everything now, out of cadence — what the dashboard's refresh button calls. */
    public void refresh() {
        if (pool != null && !pool.isShutdown()) pool.execute(this::pingAll);
    }

    // ------------------------------------------------------------- the board

    /**
     * The whole tab's payload: this server and every rival in one ranked board, so the position
     * numbers and the "who is ahead" verdict are decided in a single place rather than being
     * recomputed by the browser.
     *
     * <p>Unreachable rivals sort last and carry no rank — a server nobody can reach has no
     * player count to compare, and guessing zero would flatter us.
     */
    public String json() {
        int ourOnline = Bukkit.getOnlinePlayers().size();
        int ourMax = Bukkit.getMaxPlayers();

        record Entry(String id, String name, String host, int port, boolean self,
                     Result result, int online) {}

        List<Entry> board = new ArrayList<>();
        board.add(new Entry("~self", ourName, Bukkit.getIp(), Bukkit.getPort(), true, null, ourOnline));
        for (Rival rival : rivals) {
            Result result = results.get(rival.id());
            int online = result != null && result.status() != null ? result.status().online() : -1;
            board.add(new Entry(rival.id(), rival.name(), rival.host(), rival.port(), false, result, online));
        }

        // Reachable first, then by players, then by name so equal counts do not jitter between
        // refreshes. Our own row is always reachable, so it never falls into the tail.
        board.sort(Comparator.comparing((Entry e) -> e.online() < 0)
                .thenComparing(Comparator.comparingInt(Entry::online).reversed())
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));

        StringBuilder json = new StringBuilder(1024);
        json.append("{\"enabled\":true");
        json.append(",\"now\":").append(System.currentTimeMillis());
        json.append(",\"checkEvery\":").append(periodMs);
        json.append(",\"you\":{\"name\":").append(WebStats.jsonStr(ourName))
                .append(",\"online\":").append(ourOnline)
                .append(",\"max\":").append(ourMax).append('}');

        int rank = 0;
        int ourRank = 0;
        int ranked = 0;
        int ahead = 0;
        String leader = null;
        int leaderOnline = -1;

        json.append(",\"board\":[");
        for (int i = 0; i < board.size(); i++) {
            Entry entry = board.get(i);
            boolean reachable = entry.online() >= 0;
            if (reachable) {
                ranked++;
                rank++;
                if (entry.self()) ourRank = rank;
                else if (entry.online() > ourOnline) ahead++;
                if (entry.online() > leaderOnline) {
                    leaderOnline = entry.online();
                    leader = entry.name();
                }
            }

            ServerPinger.Status status = entry.result() == null ? null : entry.result().status();
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(WebStats.jsonStr(entry.id()))
                    .append(",\"name\":").append(WebStats.jsonStr(entry.name()))
                    .append(",\"address\":").append(WebStats.jsonStr(address(entry.host(), entry.port())))
                    .append(",\"self\":").append(entry.self())
                    // Answered, but possibly without a player count: a server is free to hide
                    // one, and that is a different thing from being down.
                    .append(",\"reachable\":").append(entry.self() || status != null)
                    .append(",\"rank\":").append(reachable ? String.valueOf(rank) : "null")
                    .append(",\"online\":").append(reachable ? String.valueOf(entry.online()) : "null")
                    .append(",\"gap\":").append(reachable && !entry.self()
                            ? String.valueOf(entry.online() - ourOnline) : "null");

            if (entry.self()) {
                json.append(",\"max\":").append(ourMax)
                        .append(",\"motd\":null,\"version\":null,\"ping\":null")
                        .append(",\"error\":null,\"checkedAt\":").append(System.currentTimeMillis());
            } else {
                json.append(",\"max\":").append(status == null ? "null" : String.valueOf(status.max()))
                        .append(",\"motd\":").append(WebStats.jsonStr(status == null ? null : status.motd()))
                        .append(",\"version\":").append(WebStats.jsonStr(status == null ? null : status.version()))
                        .append(",\"ping\":").append(status == null ? "null" : String.valueOf(status.latencyMs()))
                        .append(",\"error\":").append(WebStats.jsonStr(
                                entry.result() == null ? "not checked yet" : entry.result().error()))
                        .append(",\"checkedAt\":").append(entry.result() == null
                                ? "null" : String.valueOf(entry.result().checkedAt()));
            }
            json.append('}');
        }
        json.append(']');

        json.append(",\"rank\":").append(ourRank);
        json.append(",\"ranked\":").append(ranked);
        json.append(",\"tracked\":").append(rivals.size());
        json.append(",\"ahead\":").append(ahead);
        json.append(",\"leader\":").append(WebStats.jsonStr(leader));
        json.append(",\"leaderOnline\":").append(leaderOnline < 0 ? "null" : String.valueOf(leaderOnline));
        return json.append('}').toString();
    }

    private static String address(String host, int port) {
        String shown = host == null || host.isBlank() ? "this server" : host;
        return port == DEFAULT_PORT ? shown : shown + ':' + port;
    }

    // --------------------------------------------------------------- editing

    /**
     * Adds a rival and persists it. Returns null on success, otherwise a message fit to show
     * the caller.
     *
     * <p>Must run on the server thread: it saves config.yml.
     */
    public String add(String name, String ip) {
        if (rivals.size() >= MAX_RIVALS) return "the list is full (" + MAX_RIVALS + " servers)";

        Rival rival = parse(name, ip);
        if (rival == null) return "'" + ip + "' is not a valid address — use host or host:port";
        if (rivals.stream().anyMatch(existing -> existing.id().equals(rival.id()))) {
            return rival.host() + " is already on the list";
        }

        rivals.add(rival);
        saveRivals();
        refresh();
        return null;
    }

    /** Removes a rival by id. Returns null on success, otherwise a message. Server thread only. */
    public String remove(String id) {
        if (id == null || !rivals.removeIf(rival -> rival.id().equals(id))) {
            return "no server with id '" + id + "'";
        }
        results.remove(id);
        saveRivals();
        return null;
    }

    /**
     * Writes the in-memory list back to config.yml.
     *
     * <p>The file is re-read first because the dashboard's config editor writes it straight to
     * disk, so the copy behind {@code getConfig()} can be stale and {@code saveConfig()} would
     * put those stale values back. The {@code servers:} key itself is rebuilt from this module's
     * list, which is the one the tab has been editing.
     */
    private void saveRivals() {
        plugin.reloadConfig();

        List<Map<String, Object>> rows = new ArrayList<>(rivals.size());
        for (Rival rival : rivals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rival.name());
            row.put("ip", rival.explicitPort() ? rival.host() + ':' + rival.port() : rival.host());
            rows.add(row);
        }

        plugin.getConfig().set(SERVERS_PATH, rows);
        plugin.saveConfig();
        if (plugin.getManagers() != null) plugin.getManagers().config().reload();
    }

    @Override
    protected void onShutdown() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        rivals.clear();
        results.clear();
        inFlight.clear();
    }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "check-every", "2m",
                "timeout-ms", DEFAULT_TIMEOUT_MS,
                "your-name", "Il mio server",
                "servers", List.of());
    }
}
