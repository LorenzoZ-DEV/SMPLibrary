package dev.lorenzzz.smplibrary.modules.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGameType;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import dev.lorenzzz.smplibrary.modules.antipearl.AntiPearlGlitchModule;
import dev.lorenzzz.smplibrary.modules.dupewatch.DupeWatchModule;
import dev.lorenzzz.smplibrary.modules.competitors.CompetitorsModule;
import dev.lorenzzz.smplibrary.modules.debug.BugReport;
import dev.lorenzzz.smplibrary.modules.debug.DebugModule;
import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The dashboard's HTTP layer.
 *
 * <p>Every route is behind {@link WebAuth}: a request either already carries a session cookie
 * or presents a token, which is swapped for one. Anything else gets a 401 and, for browsers,
 * a small login page — the dashboard writes to config.yml and storage.yml, so it must never
 * answer an anonymous caller.
 */
public final class WebServer {

    private static final long SSE_PERIOD_MILLIS = 1000L;   // one snapshot per second
    private static final String ASSET_PREFIX = "web/";

    /**
     * A wedged client costs one parked thread until its socket finally errors out, and it keeps
     * its slot for exactly that long, so this is a hard ceiling on threads rather than on tabs.
     * Legitimate use needs one per open dashboard.
     */
    private static final int MAX_STREAMS = 16;

    /** Static assets, mapped to their content type. */
    private static final Map<String, String> ASSETS = Map.of(
            "index.html", "text/html; charset=utf-8",
            "style.css", "text/css; charset=utf-8",
            "app.js", "application/javascript; charset=utf-8");

    private final Smp_library plugin;
    private final WebAuth auth;
    private final WebStats stats;
    private final ConfigEditor editor;
    private final WebLog log;

    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService broadcaster;
    private volatile long lastLogVersion = -1L;
    private volatile long lastDebugVersion = -1L;
    private volatile long lastPearlVersion = -1L;
    private volatile long lastDupeVersion = -1L;
    private String boundHost;
    private int boundPort;

    public WebServer(Smp_library plugin, WebAuth auth, WebStats stats, ConfigEditor editor, WebLog log) {
        this.plugin = plugin;
        this.auth = auth;
        this.stats = stats;
        this.editor = editor;
        this.log = log;
    }

    public void start(String host, int port) throws IOException {
        this.boundHost = host;
        this.boundPort = port;

        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "smplibrary-web");
            thread.setDaemon(true);
            return thread;
        });

        // One thread pushes snapshots to every stream. scheduleAtFixedRate never overlaps runs,
        // and the tick itself only builds strings and hands them to per-client queues, so a slow
        // browser can no longer make the server grow threads.
        broadcaster = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "smplibrary-sse-broadcast");
            thread.setDaemon(true);
            return thread;
        });
        broadcaster.scheduleAtFixedRate(
                this::broadcast, SSE_PERIOD_MILLIS, SSE_PERIOD_MILLIS, TimeUnit.MILLISECONDS);

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
        Log.info("&7Web dashboard listening on &f" + host + ":" + port);
    }

    public String boundHost() { return boundHost; }

    public int boundPort() { return boundPort; }

    public void stop() {
        if (broadcaster != null) {
            broadcaster.shutdownNow();
            broadcaster = null;
        }
        for (SseClient client : clients) {
            client.close();
        }
        clients.clear();

        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // -------------------------------------------------------------- dispatch

    private void dispatch(HttpExchange exchange) throws IOException {
        // The event stream stays open past this method; everything else is closed here.
        boolean streaming = false;
        try {
            securityHeaders(exchange);
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/api/logout")) {
                auth.logout(exchange);
                exchange.getResponseHeaders().add("Set-Cookie", auth.clearedCookie());
                sendJson(exchange, 200, "{\"ok\":true}");
                return;
            }

            if (!authorize(exchange)) {
                rejectUnauthorized(exchange, path);
                return;
            }

            streaming = route(exchange, path);
        } catch (Exception e) {
            try {
                sendJson(exchange, 500, "{\"error\":" + WebStats.jsonStr(String.valueOf(e.getMessage())) + "}");
            } catch (IOException ignored) {
                // client already hung up
            }
        } finally {
            if (!streaming) exchange.close();
        }
    }

    /** Returns true when the response is a stream this method must leave open. */
    private boolean route(HttpExchange exchange, String path) throws IOException {
        String asset = assetFor(path);
        if (asset != null) {
            serveResource(exchange, asset);
            return false;
        }

        switch (path) {
            case "/api/stats" -> sendJson(exchange, 200, stats.snapshot());
            case "/api/log" -> sendJson(exchange, 200, logJson());
            case "/api/scopes" -> sendJson(exchange, 200, scopesJson());
            case "/api/players/history" -> sendJson(exchange, 200, stats.playerHistory());
            case "/api/competitors" -> sendJson(exchange, 200, competitorsJson());
            case "/api/competitors/add" -> competitorsAddRoute(exchange);
            case "/api/competitors/remove" -> competitorsRemoveRoute(exchange);
            case "/api/competitors/refresh" -> competitorsRefreshRoute(exchange);
            case "/api/chatgames/start" -> chatGamesStartRoute(exchange);
            case "/api/antipearl" -> sendJson(exchange, 200, antiPearlJson());
            case "/api/dupewatch" -> sendJson(exchange, 200, dupeWatchJson());
            case "/api/debug" -> sendJson(exchange, 200, debugJson());
            case "/api/debug/analyze" -> debugAnalyzeRoute(exchange);
            case "/api/debug/errors/clear" -> debugClearErrorsRoute(exchange);
            case "/api/debug/report/status" -> debugReportStatusRoute(exchange);
            case "/api/debug/report/delete" -> debugReportDeleteRoute(exchange);
            case "/api/stream" -> {
                return sse(exchange);
            }
            default -> {
                if (path.startsWith("/api/config/")) {
                    configRoute(exchange, path.substring("/api/config/".length()));
                } else if (path.startsWith("/api/module/") && path.endsWith("/toggle")) {
                    moduleToggleRoute(exchange, path);
                } else {
                    sendText(exchange, 404, "not found");
                }
            }
        }
        return false;
    }

    /** Maps a request path to a bundled asset name, or null when it isn't one. */
    private static String assetFor(String path) {
        if (path.equals("/")) return "index.html";
        if (path.startsWith("/api/")) return null;
        String name = path.substring(path.lastIndexOf('/') + 1); // also accepts the /web/ prefix
        return ASSETS.containsKey(name) ? name : null;
    }

    // ------------------------------------------------------------------ auth

    private boolean authorize(HttpExchange exchange) {
        if (auth.hasSession(exchange)) return true;

        String session = auth.login(exchange);
        if (session == null) return false;

        exchange.getResponseHeaders().add("Set-Cookie", auth.sessionCookie(session));
        log.log("Dashboard login from " + remoteHost(exchange));
        return true;
    }

    /** API callers get JSON they can act on; browsers get a page that asks for the token. */
    private void rejectUnauthorized(HttpExchange exchange, String path) throws IOException {
        if (path.startsWith("/api/")) {
            sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
        } else {
            sendHtml(exchange, 401, loginPage());
        }
    }

    private static void securityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
    }

    private static String remoteHost(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null ? "unknown" : remote.getHostString();
    }

    // ---------------------------------------------------------------- routes

    private void chatGamesStartRoute(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return;
        }

        // Round set-up touches the config and broadcasts to players: main thread only.
        ChatGameType started = onMainThread(() -> {
            ChatGamesModule module = plugin.modules() == null
                    ? null : plugin.modules().module("chatgames", ChatGamesModule.class);
            return module == null ? null : module.startRound();
        });

        if (started == null) {
            sendJson(exchange, 400, "{\"error\":\"chat games module is off, or no game type has usable data\"}");
            return;
        }
        log.log("Started a " + started.name() + " chat game from the dashboard by " + remoteHost(exchange));
        sendJson(exchange, 200, "{\"ok\":true,\"type\":" + WebStats.jsonStr(started.name())
                + ",\"log\":" + logJson() + "}");
    }

    // ------------------------------------------------------------ anti-pearl

    private AntiPearlGlitchModule antiPearl() {
        return plugin.modules() == null
                ? null : plugin.modules().module("antipearlglitch", AntiPearlGlitchModule.class);
    }

    private String antiPearlJson() {
        AntiPearlGlitchModule module = antiPearl();
        return module == null
                ? "{\"running\":false,\"total\":0,\"moved\":0,\"cancelled\":0,\"pushed\":0,"
                        + "\"adminBypass\":false,\"timeWindowMs\":0,\"searchDistance\":0,"
                        + "\"staffMessage\":\"\",\"watching\":0,\"events\":[]}"
                : module.json();
    }

    // -------------------------------------------------------------- anti-dupe

    private DupeWatchModule dupeWatch() {
        return plugin.modules() == null
                ? null : plugin.modules().module("dupewatch", DupeWatchModule.class);
    }

    private String dupeWatchJson() {
        DupeWatchModule module = dupeWatch();
        return module == null
                ? "{\"running\":false,\"source\":\"--\",\"balanceSource\":\"--\","
                        + "\"probeAvailable\":false,\"economies\":[],\"total\":0,\"critical\":0,"
                        + "\"counts\":{},\"flush\":{\"samples\":0,\"minMs\":0,\"avgMs\":0,"
                        + "\"maxMs\":0,\"timeouts\":0},\"players\":[],\"alerts\":[]}"
                : module.json();
    }

    // ----------------------------------------------------------------- debug

    private DebugModule debug() {
        return plugin.modules() == null
                ? null : plugin.modules().module("debug", DebugModule.class);
    }

    private String debugJson() {
        DebugModule module = debug();
        return module == null
                ? "{\"running\":false,\"errors\":[],\"reports\":[],\"analysis\":null,\"analysing\":false,"
                        + "\"ai\":{\"configured\":false,\"model\":\"\",\"keySource\":\"assente\",\"autoAnalyze\":false},"
                        + "\"summary\":{\"errorSignatures\":0,\"errorTotal\":0,\"sources\":\"\","
                        + "\"reportsOpen\":0,\"reportsTotal\":0}}"
                : "{\"running\":true," + module.json().substring(1);
    }

    /**
     * {@code POST /api/debug/analyze?q=…} or {@code ?report=<id>} — starts one AI diagnosis.
     * The call itself runs on the module's worker; this answers as soon as it is queued, and the
     * dashboard picks the result up from the stream.
     */
    private void debugAnalyzeRoute(HttpExchange exchange) throws IOException {
        DebugModule module = postToDebug(exchange);
        if (module == null) return;

        if (!module.aiConfigured()) {
            sendJson(exchange, 400, "{\"error\":\"nessuna API key: imposta DEEPSEEK_API_KEY "
                    + "oppure MODULES.debug.ai.api-key\"}");
            return;
        }

        Map<String, String> params = query(exchange);
        String reportId = params.get("report");
        boolean started = reportId != null && !reportId.isBlank()
                ? module.analyzeReport(reportId, "dashboard " + remoteHost(exchange))
                : module.analyze(params.get("q"), "dashboard " + remoteHost(exchange));

        if (!started) {
            sendJson(exchange, 409, "{\"error\":\"un'analisi e' gia' in corso, oppure la "
                    + "segnalazione non esiste\"}");
            return;
        }
        log.log("AI analysis started from the dashboard by " + remoteHost(exchange));
        sendJson(exchange, 200, "{\"ok\":true,\"debug\":" + debugJson() + "}");
    }

    private void debugClearErrorsRoute(HttpExchange exchange) throws IOException {
        DebugModule module = postToDebug(exchange);
        if (module == null) return;

        module.clearErrors();
        log.log("Cleared the captured errors from the dashboard by " + remoteHost(exchange));
        sendJson(exchange, 200, "{\"ok\":true,\"debug\":" + debugJson() + "}");
    }

    /** {@code POST /api/debug/report/status?id=…&value=open|closed} */
    private void debugReportStatusRoute(HttpExchange exchange) throws IOException {
        DebugModule module = postToDebug(exchange);
        if (module == null) return;

        Map<String, String> params = query(exchange);
        String value = BugReport.CLOSED.equals(params.get("value")) ? BugReport.CLOSED : BugReport.OPEN;
        if (module.setReportStatus(params.getOrDefault("id", ""), value) == null) {
            sendJson(exchange, 404, "{\"error\":\"segnalazione non trovata\"}");
            return;
        }
        sendJson(exchange, 200, "{\"ok\":true,\"debug\":" + debugJson() + "}");
    }

    private void debugReportDeleteRoute(HttpExchange exchange) throws IOException {
        DebugModule module = postToDebug(exchange);
        if (module == null) return;

        if (!module.deleteReport(query(exchange).getOrDefault("id", ""))) {
            sendJson(exchange, 404, "{\"error\":\"segnalazione non trovata\"}");
            return;
        }
        log.log("Deleted a bug report from the dashboard by " + remoteHost(exchange));
        sendJson(exchange, 200, "{\"ok\":true,\"debug\":" + debugJson() + "}");
    }

    /** The running debug module for a POST-only route, or null once this method has answered. */
    private DebugModule postToDebug(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return null;
        }
        DebugModule module = debug();
        if (module == null) {
            sendJson(exchange, 400, "{\"error\":\"il modulo debug non e' in esecuzione\"}");
        }
        return module;
    }

    // ----------------------------------------------------------- competitors

    private CompetitorsModule competitors() {
        return plugin.modules() == null
                ? null : plugin.modules().module("competitors", CompetitorsModule.class);
    }

    private String competitorsJson() {
        CompetitorsModule module = competitors();
        return module == null
                ? "{\"enabled\":false,\"board\":[],\"tracked\":0,\"ahead\":0,\"rank\":0}"
                : module.json();
    }

    /** {@code POST /api/competitors/add?name=…&ip=host[:port]} */
    private void competitorsAddRoute(HttpExchange exchange) throws IOException {
        CompetitorsModule module = postToCompetitors(exchange);
        if (module == null) return;

        Map<String, String> params = query(exchange);
        String ip = params.getOrDefault("ip", "");
        String name = params.get("name");
        if (ip.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"no address given\"}");
            return;
        }

        // add() writes config.yml, so it belongs on the server thread. Empty means it worked;
        // null means the server never got round to it.
        String outcome = onMainThread(() -> {
            String error = module.add(name, ip);
            return error == null ? "" : error;
        });

        if (outcome == null) {
            sendJson(exchange, 504, "{\"error\":\"the server did not save the list in time\"}");
            return;
        }
        if (!outcome.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":" + WebStats.jsonStr(outcome) + "}");
            return;
        }
        log.log("Added competitor " + ip + " from the dashboard by " + remoteHost(exchange));
        sendJson(exchange, 200, "{\"ok\":true,\"competitors\":" + competitorsJson()
                + ",\"log\":" + logJson() + "}");
    }

    /** {@code POST /api/competitors/remove?id=host:port} */
    private void competitorsRemoveRoute(HttpExchange exchange) throws IOException {
        CompetitorsModule module = postToCompetitors(exchange);
        if (module == null) return;

        String id = query(exchange).getOrDefault("id", "");
        String outcome = onMainThread(() -> {
            String error = module.remove(id);
            return error == null ? "" : error;
        });

        if (outcome == null) {
            sendJson(exchange, 504, "{\"error\":\"the server did not save the list in time\"}");
            return;
        }
        if (!outcome.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":" + WebStats.jsonStr(outcome) + "}");
            return;
        }
        log.log("Removed competitor " + id + " from the dashboard by " + remoteHost(exchange));
        sendJson(exchange, 200, "{\"ok\":true,\"competitors\":" + competitorsJson()
                + ",\"log\":" + logJson() + "}");
    }

    /**
     * {@code POST /api/competitors/refresh} — queues a ping sweep and answers with the board as
     * it stands. The results land asynchronously; the browser's next poll picks them up.
     */
    private void competitorsRefreshRoute(HttpExchange exchange) throws IOException {
        CompetitorsModule module = postToCompetitors(exchange);
        if (module == null) return;

        module.refresh();
        sendJson(exchange, 200, "{\"ok\":true,\"competitors\":" + competitorsJson() + "}");
    }

    /**
     * The running competitors module for a POST-only route, or null once this method has
     * already answered the request — a wrong verb or a module that is switched off.
     */
    private CompetitorsModule postToCompetitors(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return null;
        }
        CompetitorsModule module = competitors();
        if (module == null) {
            sendJson(exchange, 400, "{\"error\":\"the competitors module is not running\"}");
        }
        return module;
    }

    /** Query string as a map. Values arrive percent-encoded from the dashboard. */
    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return params;

        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            params.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private void moduleToggleRoute(HttpExchange exchange, String path) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "method not allowed");
            return;
        }
        String moduleId = path.substring("/api/module/".length(), path.length() - "/toggle".length());

        // Starting a module registers listeners and schedules tasks: server thread only. We
        // wait for the result rather than fire-and-forget, so the response carries the state
        // the module is actually in — the browser used to be told "ok" before anything moved.
        ConfigEditor.ToggleResult result =
                onMainThread(() -> editor.toggleModule(moduleId, remoteHost(exchange)));

        if (result == null) {
            sendJson(exchange, 504, "{\"error\":\"the server did not apply the toggle in time\"}");
            return;
        }
        if (result.id() == null) {
            sendJson(exchange, 400, "{\"error\":" + WebStats.jsonStr(result.error()) + "}");
            return;
        }

        // The flip landed even when the module then threw on the way up; that is a warning to
        // show, not a failed request, and the module list below already reflects it.
        sendJson(exchange, 200, "{\"ok\":true"
                + ",\"id\":" + WebStats.jsonStr(result.id())
                + ",\"enabled\":" + result.enabled()
                + ",\"running\":" + result.running()
                + ",\"warning\":" + WebStats.jsonStr(result.error())
                + ",\"modules\":" + stats.modulesJson()
                + ",\"log\":" + logJson() + "}");
    }

    private void configRoute(HttpExchange exchange, String scope) throws IOException {
        if (!ConfigEditor.scopes().contains(scope)) {
            sendText(exchange, 404, "unknown scope");
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String text;
            try {
                text = editor.read(scope);
            } catch (IOException e) {
                sendText(exchange, 500, "read error: " + e.getMessage());
                return;
            }
            sendJson(exchange, 200, "{\"scope\":" + WebStats.jsonStr(scope)
                    + ",\"readonly\":" + ConfigEditor.isReadOnly(scope)
                    + ",\"content\":" + WebStats.jsonStr(text) + "}");
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (ConfigEditor.isReadOnly(scope)) {
                sendJson(exchange, 403, "{\"error\":\"scope is read-only\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String error = editor.apply(scope, body, remoteHost(exchange));
            if (error != null) sendJson(exchange, 400, "{\"error\":" + WebStats.jsonStr(error) + "}");
            else sendJson(exchange, 200, "{\"ok\":true,\"log\":" + logJson() + "}");
            return;
        }

        sendText(exchange, 405, "method not allowed");
    }

    // ------------------------------------------------------------------- SSE

    /** Returns true once the stream is live and owns the exchange; false when it was refused. */
    private boolean sse(HttpExchange exchange) throws IOException {
        if (clients.size() >= MAX_STREAMS) {
            // Nothing has been written yet, so this is still an ordinary response. Refusing is the
            // right failure: the alternative is growing threads again, which is what got us here.
            Log.warning("Web dashboard: refusing a new event stream, " + MAX_STREAMS + " already open."
                    + " If this persists, clients are wedged mid-write and their threads have not"
                    + " been released yet.");
            sendJson(exchange, 503, "{\"error\":\"too many open streams\"}");
            return false;
        }

        // Seed frames so the tab draws immediately instead of waiting for the next tick, and gets
        // the log without waiting for the next change to it. Built before the response starts, so
        // that a failure here is still an ordinary 500 and never leaves a half-owned exchange.
        byte[] snapshot = frame("data: " + stats.snapshot() + "\n\n");
        byte[] logFrame = frame("event: log\ndata: " + logJson() + "\n\n");
        byte[] debugFrame = frame("event: debug\ndata: " + debugJson() + "\n\n");
        byte[] pearlFrame = frame("event: antipearl\ndata: " + antiPearlJson() + "\n\n");
        byte[] dupeFrame = frame("event: dupewatch\ndata: " + dupeWatchJson() + "\n\n");

        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        SseClient client = new SseClient(exchange, clients::remove);
        clients.add(client);
        client.start();
        client.send(snapshot);
        client.send(logFrame);
        client.send(debugFrame);
        client.send(pearlFrame);
        client.send(dupeFrame);
        return true;
    }

    /**
     * Builds this second's frames once and offers them to every stream. Runs on the broadcaster
     * thread and must never block: {@link SseClient#send} only queues, and drops clients that
     * have stopped draining. The snapshot doubles as the heartbeat that reveals dead connections.
     */
    private void broadcast() {
        // Clients hang around after being given up on, until their writer thread really ends, so
        // "not empty" is not the same as "somebody is listening".
        if (clients.stream().allMatch(SseClient::isClosed)) return;
        try {
            byte[] snapshot = frame("data: " + stats.snapshot() + "\n\n");

            // The log rarely changes; pushing it every second was pure noise.
            byte[] logFrame = null;
            long version = log.version();
            if (version != lastLogVersion) {
                lastLogVersion = version;
                logFrame = frame("event: log\ndata: " + logJson() + "\n\n");
            }

            // Same treatment for the debug tab: it only moves when something actually failed or
            // a report came in, and its payload is the biggest of the three.
            byte[] debugFrame = null;
            DebugModule module = debug();
            long debugVersion = module == null ? 0 : module.version();
            if (debugVersion != lastDebugVersion) {
                lastDebugVersion = debugVersion;
                debugFrame = frame("event: debug\ndata: " + debugJson() + "\n\n");
            }

            // Anti-pearl only moves when a pearl is actually corrected, which is rare.
            byte[] pearlFrame = null;
            AntiPearlGlitchModule pearl = antiPearl();
            long pearlVersion = pearl == null ? 0 : pearl.version();
            if (pearlVersion != lastPearlVersion) {
                lastPearlVersion = pearlVersion;
                pearlFrame = frame("event: antipearl\ndata: " + antiPearlJson() + "\n\n");
            }

            // Anti-dupe moves only when an anomaly is found, which on a healthy server is
            // never — so the panel is fed the same way, off its own version counter.
            byte[] dupeFrame = null;
            DupeWatchModule dupe = dupeWatch();
            long dupeVersion = dupe == null ? 0 : dupe.version();
            if (dupeVersion != lastDupeVersion) {
                lastDupeVersion = dupeVersion;
                dupeFrame = frame("event: dupewatch\ndata: " + dupeWatchJson() + "\n\n");
            }

            for (SseClient client : clients) {
                client.send(snapshot);
                if (logFrame != null) client.send(logFrame);
                if (debugFrame != null) client.send(debugFrame);
                if (pearlFrame != null) client.send(pearlFrame);
                if (dupeFrame != null) client.send(dupeFrame);
            }
        } catch (Exception e) {
            // scheduleAtFixedRate drops the task on the first throw, and the dashboard would go
            // quiet with no explanation. Log it and keep ticking.
            Log.warning("Web dashboard: stream broadcast failed: " + e);
        }
    }

    private static byte[] frame(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- plumbing

    /** Runs {@code work} on the server thread and waits for its result. */
    private <T> T onMainThread(Callable<T> work) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return work.call();
            } catch (Exception e) {
                return null;
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(work.call());
            } catch (Exception e) {
                future.complete(null);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private void serveResource(HttpExchange exchange, String asset) throws IOException {
        try (InputStream in = plugin.getResource(ASSET_PREFIX + asset)) {
            if (in == null) {
                sendText(exchange, 404, "missing asset " + asset);
                return;
            }
            send(exchange, 200, ASSETS.get(asset), in.readAllBytes());
        }
    }

    private void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendHtml(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int code, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String logJson() {
        return jsonArray(log.recent(), WebStats::jsonStr);
    }

    private String scopesJson() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, Boolean> entry : editor.scopeFlags().entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"scope\":").append(WebStats.jsonStr(entry.getKey()))
                    .append(",\"readonly\":").append(entry.getValue()).append('}');
        }
        return json.append(']').toString();
    }

    private static <T> String jsonArray(List<T> values, java.util.function.Function<T, String> encoder) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append(encoder.apply(values.get(i)));
        }
        return json.append(']').toString();
    }

    /** Minimal self-contained page so a browser hitting the port sees a prompt, not a blank 401. */
    private static String loginPage() {
        return """
                <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Venrix Panel &mdash; Accedi</title><style>
                :root{color-scheme:dark;--line:#33264a;--brand:#8b00ff;--accent:#b57bff}
                *{box-sizing:border-box;margin:0}
                body{min-height:100vh;display:grid;place-items:center;padding:20px;background:#140f1c;color:#f0eaf8;
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;font-size:14px;
                background-image:radial-gradient(900px 520px at 20% -10%,#3a1163 0,transparent 62%),
                radial-gradient(760px 430px at 100% 10%,#2a1247 0,transparent 58%)}
                form{background:#1c1526;border:1px solid var(--line);border-radius:20px;padding:32px;
                width:min(94vw,390px);box-shadow:0 16px 48px #00000094}
                .mark{display:grid;place-items:center;width:40px;height:40px;border-radius:12px;
                background:linear-gradient(140deg,var(--brand),#e07bff);margin-bottom:16px;
                box-shadow:0 6px 22px #8b00ff45}
                .mark svg{width:22px;height:22px;fill:none;stroke:#fff;stroke-width:2.4;
                stroke-linecap:round;stroke-linejoin:round}
                h1{font-size:18px;font-weight:700;letter-spacing:-.3px}
                h1 b{color:var(--accent);font-weight:700}
                p{margin:6px 0 22px;color:#a99cbd;font-size:13px;line-height:1.55}
                code{font-family:ui-monospace,monospace;color:#f0eaf8;background:#2e2240;
                padding:1px 5px;border-radius:5px;font-size:12px}
                label{display:block;font-size:11.5px;font-weight:600;letter-spacing:.05em;
                text-transform:uppercase;color:#a99cbd;margin-bottom:6px}
                input{width:100%;padding:10px 12px;border-radius:9px;border:1px solid var(--line);
                background:#120d19;color:#f0eaf8;font-family:ui-monospace,monospace;font-size:13px;outline:none}
                input:focus{border-color:var(--accent);box-shadow:0 0 0 3px #b57bff24}
                button{width:100%;margin-top:14px;padding:11px;border:0;border-radius:9px;
                background:linear-gradient(160deg,var(--brand),#a259ff);
                color:#fff;font-weight:700;font-size:14px;cursor:pointer}
                button:hover{filter:brightness(1.12)}
                </style></head><body>
                <form onsubmit="location.search='?token='+encodeURIComponent(this.t.value.trim());return false">
                <div class="mark"><svg viewBox="0 0 24 24"><path d="M5 5.5 12 19l7-13.5"/></svg></div>
                <h1>Venrix<b>Panel</b></h1>
                <p>Incolla il token del pannello, o apri il link di <code>/smpl webedit</code> in gioco.</p>
                <label for="t">Token di accesso</label>
                <input id="t" name="t" autofocus placeholder="incollalo qui" autocomplete="off" spellcheck="false">
                <button type="submit">Entra</button>
                </form></body></html>
                """;
    }
}
