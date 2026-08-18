package dev.lorenzzz.smplibrary.modules.competitors;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * Server List Ping client — the same handshake a vanilla client performs when it draws the
 * multiplayer list, so a competitor's player count is read exactly as their own players see it.
 *
 * <p>Modern SLP only (1.7+, the JSON status response). Servers older than that answer the
 * legacy 0xFE ping instead, and are reported as unreachable rather than carrying a second
 * protocol around for them.
 *
 * <p>Everything here blocks on a socket: call it from a worker thread, never from the server
 * thread. {@link #ping} bounds itself with both a connect and a read timeout, so the worst case
 * per host is roughly {@code 2 x timeout}.
 */
public final class ServerPinger {

    /** "I am not telling you" — every implementation accepts it for a status ping. */
    private static final int PROTOCOL_UNKNOWN = -1;

    /** A status response is a few hundred bytes; anything this large is a server misbehaving. */
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static final int STATE_STATUS = 1;

    private ServerPinger() {}

    /** What a reachable server answered with. Counts are -1 when the response omitted them. */
    public record Status(int online, int max, String motd, String version, long latencyMs) {}

    /** A resolved endpoint: what SRV pointed at, or the literal host and port. */
    public record Endpoint(String host, int port) {}

    /**
     * Pings {@code host:port} and returns what it answered.
     *
     * @param resolveSrv look up {@code _minecraft._tcp.<host>} first, as a client does when
     *                   the player typed no port. Pass false when the port was explicit.
     * @throws IOException when the host is unreachable, times out, or speaks something else
     */
    public static Status ping(String host, int port, int timeoutMs, boolean resolveSrv) throws IOException {
        Endpoint target = resolveSrv ? srvOrSelf(host, port) : new Endpoint(host, port);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);

            long start = System.nanoTime();
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Handshake. The address and port sent here are the ones the *player* would have
            // typed, not what SRV resolved to — that is what proxies key their routing on.
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(handshake);
            body.writeByte(0x00);
            writeVarInt(body, PROTOCOL_UNKNOWN);
            writeString(body, host);
            body.writeShort(port);
            writeVarInt(body, STATE_STATUS);
            writePacket(out, handshake.toByteArray());

            writePacket(out, new byte[] { 0x00 });   // status request, no payload
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > MAX_RESPONSE_BYTES) {
                throw new IOException("not a Minecraft status response");
            }
            int packetId = readVarInt(in);
            if (packetId != 0x00) throw new IOException("unexpected packet 0x" + Integer.toHexString(packetId));

            String json = readString(in, MAX_RESPONSE_BYTES);
            long latency = (System.nanoTime() - start) / 1_000_000L;

            return new Status(
                    Json.intIn(json, "players", "online", -1),
                    Json.intIn(json, "players", "max", -1),
                    Json.motd(json),
                    Json.stringIn(json, "version", "name"),
                    latency);
        }
    }

    /**
     * The endpoint a client would really connect to. A missing or broken SRV record is the
     * normal case, not an error: fall back to the literal host and port.
     */
    private static Endpoint srvOrSelf(String host, int port) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext context = new InitialDirContext(env);
            try {
                Attributes attributes = context.getAttributes("_minecraft._tcp." + host, new String[] { "SRV" });
                Attribute srv = attributes.get("SRV");
                if (srv == null || srv.size() == 0) return new Endpoint(host, port);

                // "priority weight port target"
                String[] parts = String.valueOf(srv.get(0)).split(" ");
                if (parts.length < 4) return new Endpoint(host, port);

                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                return new Endpoint(target, Integer.parseInt(parts[2]));
            } finally {
                context.close();
            }
        } catch (Exception ignored) {
            return new Endpoint(host, port);
        }
    }

    // ------------------------------------------------------------- protocol

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int remaining = value;
        do {
            int part = remaining & 0x7F;
            remaining >>>= 7;
            out.writeByte(remaining == 0 ? part : part | 0x80);
        } while (remaining != 0);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int read = in.readByte();
            result |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) return result;
        }
        throw new IOException("varint too long");
    }

    private static String readString(DataInputStream in, int limit) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > limit) throw new IOException("string length out of range: " + length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Just enough JSON reading for the four fields the dashboard shows.
     *
     * <p>The plugin ships no JSON parser and this is the only place that needs one, so rather
     * than shade a library for it these walk the text directly. They are deliberately tolerant:
     * a field that is missing, of the wrong shape, or in a response that is not JSON at all
     * comes back as the fallback instead of throwing — a rival's server is not our code, and
     * a weird MOTD must never cost us the player count sitting next to it.
     */
    private static final class Json {

        /** {@code {"players":{"online":12}}} -> 12. */
        static int intIn(String json, String object, String field, int fallback) {
            int at = valueOf(json, keyIndex(json, object, 0), field);
            if (at < 0) return fallback;

            int end = at;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            try {
                return Integer.parseInt(json.substring(at, end));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** {@code {"version":{"name":"Paper 1.21"}}} -> "Paper 1.21". */
        static String stringIn(String json, String object, String field) {
            int at = valueOf(json, keyIndex(json, object, 0), field);
            return at >= 0 && json.charAt(at) == '"' ? readString(json, at) : null;
        }

        /**
         * The MOTD as plain text. It is either a bare string or a chat component, in which
         * case the visible text is every {@code "text"} field in document order.
         */
        static String motd(String json) {
            int key = keyIndex(json, "description", 0);
            if (key < 0) return null;

            int at = afterColon(json, key);
            if (at < 0) return null;
            if (json.charAt(at) == '"') return clean(readString(json, at));

            StringBuilder text = new StringBuilder();
            int end = objectEnd(json, at);
            for (int i = at; i >= 0 && i < end; ) {
                int textKey = keyIndex(json, "text", i);
                if (textKey < 0 || textKey >= end) break;
                int value = afterColon(json, textKey);
                if (value < 0 || json.charAt(value) != '"') break;
                text.append(readString(json, value));
                i = skipString(json, value);
            }
            return text.isEmpty() ? null : clean(text.toString());
        }

        /** Index of {@code "key"} at or after {@code from}, or -1. */
        private static int keyIndex(String json, String key, int from) {
            return from < 0 ? -1 : json.indexOf('"' + key + '"', from);
        }

        /** Start of {@code field}'s value inside the object named at {@code objectKey}. */
        private static int valueOf(String json, int objectKey, String field) {
            if (objectKey < 0) return -1;
            int fieldKey = keyIndex(json, field, objectKey);
            return fieldKey < 0 ? -1 : afterColon(json, fieldKey);
        }

        /** First non-space character after the colon that follows a key, or -1. */
        private static int afterColon(String json, int keyIndex) {
            int colon = json.indexOf(':', keyIndex);
            if (colon < 0) return -1;
            int at = colon + 1;
            while (at < json.length() && Character.isWhitespace(json.charAt(at))) at++;
            return at < json.length() ? at : -1;
        }

        /** End of the object or array starting at {@code start}, quote-aware. */
        private static int objectEnd(String json, int start) {
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (c == '\\') i++;
                    else if (c == '"') inString = false;
                } else if (c == '"') inString = true;
                else if (c == '{' || c == '[') depth++;
                else if ((c == '}' || c == ']') && --depth <= 0) return i + 1;
            }
            return json.length();
        }

        /** Reads the JSON string whose opening quote is at {@code start}. */
        private static String readString(String json, int start) {
            StringBuilder out = new StringBuilder();
            for (int i = start + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') break;
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (++i >= json.length()) break;
                char escape = json.charAt(i);
                switch (escape) {
                    case 'n', 'r', 't' -> out.append(' ');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            } catch (NumberFormatException ignored) {
                                // leave it out rather than pasting the escape in
                            }
                            i += 4;
                        }
                    }
                    default -> out.append(escape);
                }
            }
            return out.toString();
        }

        /** Index just past the JSON string that opens at {@code start}. */
        private static int skipString(String json, int start) {
            for (int i = start + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\') i++;
                else if (c == '"') return i + 1;
            }
            return json.length();
        }

        /** Strips legacy colour codes and collapses whitespace — this ends up in a table cell. */
        private static String clean(String motd) {
            StringBuilder out = new StringBuilder(motd.length());
            for (int i = 0; i < motd.length(); i++) {
                char c = motd.charAt(i);
                if (c == '§') {
                    i++;                        // drop the code that follows too
                } else if (Character.isWhitespace(c)) {
                    if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') out.append(' ');
                } else {
                    out.append(c);
                }
            }
            return out.toString().trim();
        }
    }
}
