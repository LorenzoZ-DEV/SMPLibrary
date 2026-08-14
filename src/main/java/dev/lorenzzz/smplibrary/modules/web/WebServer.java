package dev.lorenzzz.smplibrary.modules.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGameType;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final long SSE_PERIOD_TICKS = 20L;      // one snapshot per second
    private static final String ASSET_PREFIX = "web/";

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

    private final Set<HttpExchange> openStreams = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private ExecutorService executor;
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

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
        C.info("&7Web dashboard listening on &f" + host + ":" + port);
    }

    public String boundHost() { return boundHost; }

    public int boundPort() { return boundPort; }

    public void stop() {
        for (HttpExchange exchange : openStreams) {
            try { exchange.close(); } catch (Exception ignored) { /* already gone */ }
        }
        openStreams.clear();

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
            case "/api/chatgames/start" -> chatGamesStartRoute(exchange);
            case "/api/stream" -> {
                sse(exchange);
                return true;
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

    private void sse(HttpExchange exchange) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        openStreams.add(exchange);
        OutputStream out = exchange.getResponseBody();

        int[] taskId = { -1 };
        long[] lastLogVersion = { -1L };
        taskId[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!openStreams.contains(exchange)) {
                Bukkit.getScheduler().cancelTask(taskId[0]);
                return;
            }
            try {
                write(out, "data: " + stats.snapshot() + "\n\n");
                // The log rarely changes; pushing it every second was pure noise.
                long version = log.version();
                if (version != lastLogVersion[0]) {
                    lastLogVersion[0] = version;
                    write(out, "event: log\ndata: " + logJson() + "\n\n");
                }
                out.flush();
            } catch (IOException disconnected) {
                closeStream(exchange, taskId[0]);
            }
        }, 0L, SSE_PERIOD_TICKS).getTaskId();
    }

    private void closeStream(HttpExchange exchange, int taskId) {
        openStreams.remove(exchange);
        Bukkit.getScheduler().cancelTask(taskId);
        try { exchange.close(); } catch (Exception ignored) { /* already gone */ }
    }

    private static void write(OutputStream out, String payload) throws IOException {
        out.write(payload.getBytes(StandardCharsets.UTF_8));
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
                <title>SMP Library — Sign in</title><style>
                :root{color-scheme:dark;--line:#222c3b;--accent:#58a6ff}
                *{box-sizing:border-box;margin:0}
                body{min-height:100vh;display:grid;place-items:center;padding:20px;background:#0a0e15;color:#e7edf5;
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;font-size:14px;
                background-image:radial-gradient(900px 500px at 20% -10%,#1b2a45 0,transparent 60%),
                radial-gradient(700px 400px at 100% 10%,#1d2140 0,transparent 55%)}
                form{background:#10151e;border:1px solid var(--line);border-radius:16px;padding:32px;
                width:min(94vw,390px);box-shadow:0 12px 40px #0000008c}
                .mark{display:grid;place-items:center;width:38px;height:38px;border-radius:11px;font-size:19px;
                background:linear-gradient(140deg,var(--accent),#7c8cff);margin-bottom:16px}
                h1{font-size:17px;font-weight:650;letter-spacing:-.2px}
                p{margin:6px 0 22px;color:#93a1b5;font-size:13px;line-height:1.55}
                code{font-family:ui-monospace,monospace;color:#e7edf5;background:#1d2634;
                padding:1px 5px;border-radius:5px;font-size:12px}
                label{display:block;font-size:11.5px;font-weight:600;letter-spacing:.05em;
                text-transform:uppercase;color:#93a1b5;margin-bottom:6px}
                input{width:100%;padding:10px 12px;border-radius:8px;border:1px solid var(--line);
                background:#080c12;color:#e7edf5;font-family:ui-monospace,monospace;font-size:13px;outline:none}
                input:focus{border-color:var(--accent);box-shadow:0 0 0 3px #58a6ff1f}
                button{width:100%;margin-top:14px;padding:11px;border:0;border-radius:8px;
                background:linear-gradient(160deg,var(--accent),#4a86e8);
                color:#06101f;font-weight:650;font-size:14px;cursor:pointer}
                button:hover{filter:brightness(1.09)}
                </style></head><body>
                <form onsubmit="location.search='?token='+encodeURIComponent(this.t.value.trim());return false">
                <div class="mark">&#128218;</div>
                <h1>SMP Library Dashboard</h1>
                <p>Paste the dashboard token, or open the link from <code>/smpl webedit</code> in game.</p>
                <label for="t">Access token</label>
                <input id="t" name="t" autofocus placeholder="paste it here" autocomplete="off" spellcheck="false">
                <button type="submit">Sign in</button>
                </form></body></html>
                """;
    }
}
