package dev.lorenzzz.smplibrary.text;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Fills {@code %key%}-style placeholders and, when it is installed, PlaceholderAPI's.
 * Output is raw text — color it with {@link Text} afterwards.
 */
public final class Placeholders {

    private static volatile boolean papi;

    private Placeholders() {}

    /** Set once at boot by the CommandManager, depending on whether PlaceholderAPI is loaded. */
    public static void setPapiEnabled(boolean enabled) {
        papi = enabled;
    }

    public static boolean papiEnabled() {
        return papi;
    }

    /** Replaces each key with its value. Keys are literal ({@code "%amount%"}, {@code "{form}"}). */
    public static String apply(String text, Map<String, ?> placeholders) {
        if (text == null) return "";
        if (placeholders == null || placeholders.isEmpty() || text.isEmpty()) return text;
        String out = text;
        for (Map.Entry<String, ?> e : placeholders.entrySet()) {
            out = out.replace(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    /** {@link #apply(String, Map)}, then PlaceholderAPI for {@code player} if there is one. */
    public static String apply(String text, Player player, Map<String, ?> placeholders) {
        String out = apply(text, placeholders);
        if (papi && player != null && out.indexOf('%') >= 0) {
            out = PlaceholderAPI.setPlaceholders(player, out);
        }
        return out;
    }

    /**
     * Whether {@code text} still has something PlaceholderAPI would fill in per player. A leftover
     * {@code %} is the only signal available, so this errs on the side of {@code true}.
     */
    public static boolean needsPlayer(String text) {
        return papi && text != null && text.indexOf('%') >= 0;
    }
}
