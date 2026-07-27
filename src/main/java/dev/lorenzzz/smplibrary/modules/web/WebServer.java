package dev.lorenzzz.smplibrary.modules.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lorenzzz.smplibrary.Smp_library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;

public final class WebServer {

    private final Smp_library plugin;
    private final WebAuth auth;
    private final WebStats stats;
    private final ConfigEditor editor;
    private final WebLog log;
    private HttpServer server;
    private final Set<HttpExchange> openStreams = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
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
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::dispatch);
        server.start();
        org.bukkit.Bukkit.getConsoleSender().sendMessage("§9[WEB] §7listening on §f" + host + ":" + port);
    }

    public String boundHost() { return boundHost; }
    public int boundPort() { return boundPort; }

    public void stop() {
        for (HttpExchange ex : openStreams) try { ex.close(); } catch (Exception ignored) {}
        openStreams.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!auth.check(ex)) { sendText(ex, 401, "unauthorized"); return; }

            String method = ex.getRequestMethod();

            if (path.equals("/") || path.equals("/index.html")) {
                serveResource(ex, "/web/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/style.css")) {
                serveResource(ex, "/web/style.css", "text/css; charset=utf-8");
            } else if (path.equals("/app.js")) {
                serveResource(ex, "/web/app.js", "application/javascript; charset=utf-8");
            } else if (path.equals("/api/stats")) {
                sendJson(ex, 200, stats.snapshot());
            } else if (path.equals("/api/log")) {
                sendJson(ex, 200, logJson());
            } else if (path.equals("/api/scopes")) {
                sendJson(ex, 200, scopesJson());
            } else if (path.equals("/api/stream")) {
                sse(ex);
            } else if (path.startsWith("/api/config/")) {
                configRoute(ex, path.substring("/api/config/".length()));
            } else if (path.startsWith("/api/module/") && path.endsWith("/toggle")) {
                moduleToggleRoute(ex, path);
            } else {
                sendText(ex, 404, "not found");
            }
        } catch (Exception e) {
            try { sendText(ex, 500, "error: " + e.getMessage()); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private void moduleToggleRoute(HttpExchange ex, String path) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "method not allowed");
            return;
        }
        // /api/module/<id>/toggle
        String moduleId = path.substring("/api/module/".length(), path.length() - "/toggle".length());
        String who = ex.getRemoteAddress() != null ? ex.getRemoteAddress().getHostString() : "";
        String err = editor.toggleModule(moduleId, who);
        if (err != null) {
            sendJson(ex, 400, "{\"error\":" + WebStats.jsonStr(err) + "}");
        } else {
            sendJson(ex, 200, "{\"ok\":true,\"stats\":" + stats.snapshot() + ",\"log\":" + logJson() + "}");
        }
    }

    private void configRoute(HttpExchange ex, String scope) throws IOException {
        if (!ConfigEditor.scopes().contains(scope)) { sendText(ex, 404, "unknown scope"); return; }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            String text;
            try { text = editor.read(scope); }
            catch (IOException e) { sendText(ex, 500, "read error: " + e.getMessage()); return; }
            sendJson(ex, 200, "{\"scope\":\"" + scope + "\",\"readonly\":"
                    + ConfigEditor.isReadOnly(scope) + ",\"content\":"
                    + WebStats.jsonStr(text) + "}");
        } else if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            if (ConfigEditor.isReadOnly(scope)) { sendText(ex, 403, "scope is read-only"); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String who = ex.getRemoteAddress().getHostString();
            String err = editor.apply(scope, body, who);
            if (err != null) sendJson(ex, 400, "{\"error\":" + WebStats.jsonStr(err) + "}");
            else sendJson(ex, 200, "{\"ok\":true,\"log\":" + logJson() + "}");
        } else {
            sendText(ex, 405, "method not allowed");
        }
    }

    private void sse(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        openStreams.add(ex);
        OutputStream os = ex.getResponseBody();

        int[] taskId = new int[] { -1 };
        taskId[0] = org.bukkit.Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            public void run() {
                if (!openStreams.contains(ex)) {
                    org.bukkit.Bukkit.getScheduler().cancelTask(taskId[0]);
                    return;
                }
                try {
                    os.write(("data: " + stats.snapshot() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.write(("event: log\ndata: " + logJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException e) {
                    openStreams.remove(ex);
                    org.bukkit.Bukkit.getScheduler().cancelTask(taskId[0]);
                    try { ex.close(); } catch (Exception ignored) {}
                }
            }
        }, 0L, 20L).getTaskId();
    }

    private void serveResource(HttpExchange ex, String resource, String mime) throws IOException {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) { sendText(ex, 404, "missing asset " + resource); return; }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    private void sendJson(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendText(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String logJson() {
        var recent = log.recent();
        StringBuilder b = new StringBuilder();
        b.append('[');
        boolean first = true;
        for (String l : recent) {
            if (!first) b.append(',');
            first = false;
            b.append(WebStats.jsonStr(l));
        }
        b.append(']');
        return b.toString();
    }

    private String scopesJson() {
        var flags = editor.scopeFlags();
        StringBuilder b = new StringBuilder();
        b.append('[');
        boolean first = true;
        for (var e : flags.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"scope\":").append(WebStats.jsonStr(e.getKey()))
             .append(",\"readonly\":").append(e.getValue()).append('}');
        }
        b.append(']');
        return b.toString();
    }

    @SuppressWarnings("unused")
    private static String guessMime(String name) {
        String m = URLConnection.guessContentTypeFromName(name);
        return m == null ? "application/octet-stream" : m;
    }
}
