package dev.lorenzzz.smplibrary.modules.web;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WebAuth {

    private static final long TEMP_TOKEN_DURATION_MS = 300_000;

    private final String mainToken;
    private final Map<String, Long> tempTokens = new ConcurrentHashMap<>();

    public WebAuth(String configuredToken) {
        this.mainToken = (configuredToken == null || configuredToken.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : configuredToken.trim();
        if (configuredToken == null || configuredToken.isBlank()) {
            org.bukkit.Bukkit.getConsoleSender().sendMessage("§9[WEB] §7token: §f" + this.mainToken);
        }
    }

    public String token() { return mainToken; }

    public String generateTempToken() {
        String t = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        tempTokens.put(t, System.currentTimeMillis() + TEMP_TOKEN_DURATION_MS);
        return t;
    }

    public boolean check(HttpExchange exchange) {
        String q = exchange.getRequestURI().getQuery();
        String headerToken = exchange.getRequestHeaders().getFirst("X-Token");
        String queryToken = parseQueryToken(q);
        if (mainToken.equals(headerToken) || mainToken.equals(queryToken)) return true;
        if (queryToken != null && checkTempToken(queryToken)) return true;
        if (headerToken != null && checkTempToken(headerToken)) return true;
        return false;
    }

    private boolean checkTempToken(String token) {
        Long expiry = tempTokens.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            tempTokens.remove(token);
            return false;
        }
        tempTokens.remove(token);
        return true;
    }

    private static String parseQueryToken(String query) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if ("token".equals(part.substring(0, eq))) return part.substring(eq + 1);
        }
        return null;
    }

    public static boolean isLocalOnly(InetSocketAddress remote) {
        if (remote == null) return true;
        String host = remote.getHostString();
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }
}
