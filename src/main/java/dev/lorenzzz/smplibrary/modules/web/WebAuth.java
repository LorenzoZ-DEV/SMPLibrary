package dev.lorenzzz.smplibrary.modules.web;

import com.sun.net.httpserver.HttpExchange;
import dev.lorenzzz.smplibrary.C;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access control for the dashboard.
 *
 * <p>Two ways in: the long-lived token from {@code MODULES.web.token} (or a random one printed
 * to console at boot), and the 5-minute single-use tokens handed out by {@code /smpl webedit}.
 * Either one is exchanged <em>once</em> for a session cookie; every later request — including
 * the page assets and the event stream, which cannot carry a custom header — rides on that
 * cookie. Without this exchange the browser could only authenticate the very first request.
 */
public final class WebAuth {

    public static final String COOKIE_NAME = "smpl_session";

    private static final long TEMP_TOKEN_TTL_MS = 5 * 60_000L;
    private static final long SESSION_TTL_MS = 6 * 60 * 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String mainToken;
    private final Map<String, Long> tempTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public WebAuth(String configuredToken) {
        boolean generated = configuredToken == null || configuredToken.isBlank();
        this.mainToken = generated ? randomId(32) : configuredToken.trim();
        if (generated) {
            C.info("&7Web dashboard token: &f" + mainToken);
            C.info("&7Set &fMODULES.web.token &7in config.yml to keep it across restarts.");
        }
    }

    public String token() {
        return mainToken;
    }

    /** A single-use token for {@code /smpl webedit}, valid for 5 minutes. */
    public String generateTempToken() {
        purgeExpired();
        String token = randomId(12);
        tempTokens.put(token, System.currentTimeMillis() + TEMP_TOKEN_TTL_MS);
        return token;
    }

    // ----------------------------------------------------------------- checks

    /** True when the request carries a session cookie we issued and that hasn't expired. */
    public boolean hasSession(HttpExchange exchange) {
        String id = cookie(exchange, COOKIE_NAME);
        if (id == null) return false;

        Long expiry = sessions.get(id);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            sessions.remove(id);
            return false;
        }
        // Sliding window: an admin with the tab open stays logged in.
        sessions.put(id, System.currentTimeMillis() + SESSION_TTL_MS);
        return true;
    }

    /**
     * Validates a token from {@code ?token=} or the {@code X-Token} header and, on success,
     * returns a fresh session id. {@code null} means the caller is not authorised.
     */
    public String login(HttpExchange exchange) {
        purgeExpired();

        String candidate = queryParam(exchange, "token");
        if (candidate == null) candidate = exchange.getRequestHeaders().getFirst("X-Token");
        if (candidate == null || candidate.isEmpty()) return null;

        if (!constantTimeEquals(mainToken, candidate) && !consumeTempToken(candidate)) return null;

        String session = randomId(32);
        sessions.put(session, System.currentTimeMillis() + SESSION_TTL_MS);
        return session;
    }

    public void logout(HttpExchange exchange) {
        String id = cookie(exchange, COOKIE_NAME);
        if (id != null) sessions.remove(id);
    }

    public String sessionCookie(String session) {
        return COOKIE_NAME + "=" + session
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (SESSION_TTL_MS / 1000L);
    }

    /** An already-expired cookie of the same name, so the browser drops it on logout. */
    public String clearedCookie() {
        return COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    private boolean consumeTempToken(String token) {
        Long expiry = tempTokens.remove(token); // single use, whether or not it was still valid
        return expiry != null && System.currentTimeMillis() <= expiry;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        tempTokens.values().removeIf(expiry -> now > expiry);
        sessions.values().removeIf(expiry -> now > expiry);
    }

    // ---------------------------------------------------------------- parsing

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0 || !name.equals(part.substring(0, eq))) continue;
            return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String cookie(HttpExchange exchange, String name) {
        var headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String entry = part.trim();
                int eq = entry.indexOf('=');
                if (eq < 0 || !name.equals(entry.substring(0, eq))) continue;
                return entry.substring(eq + 1);
            }
        }
        return null;
    }

    /** Length-independent comparison so a wrong token leaks nothing through timing. */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomId(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
