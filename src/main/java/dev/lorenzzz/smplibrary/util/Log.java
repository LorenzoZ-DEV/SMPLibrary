package dev.lorenzzz.smplibrary.util;

import dev.lorenzzz.smplibrary.text.Text;
import org.bukkit.Bukkit;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Console logging for the whole plugin.
 *
 * <p>Warnings and errors go through the plugin's own {@link Logger} — not {@code Bukkit.getLogger()},
 * the server-wide one — so they carry the {@code [SMPLibrary]} prefix and land in log filters set
 * for this plugin. Info lines go to the console sender instead, because that is the only path that
 * keeps {@code &a} colors.
 */
public final class Log {

    private static final String INFO_PREFIX = "&9[SMPLibrary] &7";

    /** Replaced with the plugin logger in {@code onLoad}; this one only covers code run before that. */
    private static volatile Logger logger = Logger.getLogger("SMPLibrary");

    private Log() {}

    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    /** Info line; {@code &a} and {@code &#RRGGBB} codes are allowed. */
    public static void info(String message) {
        console(INFO_PREFIX + message);
    }

    public static void warning(String message) {
        logger.warning(message);
    }

    /** Warning with the full stack trace, for failures that need more than {@code e.getMessage()}. */
    public static void warning(String message, Throwable cause) {
        logger.log(Level.WARNING, message, cause);
    }

    public static void error(String message) {
        logger.severe(message);
    }

    public static void error(String message, Throwable cause) {
        logger.log(Level.SEVERE, message, cause);
    }

    /** Colored console output, without prefix. Falls back to the plain logger off-server. */
    static void console(String message) {
        if (Bukkit.getServer() == null) {
            logger.info(Text.strip(message));
            return;
        }
        Bukkit.getConsoleSender().sendMessage(Text.legacy(message));
    }
}
