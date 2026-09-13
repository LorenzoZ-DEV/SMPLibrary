package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.Bukkit;

import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Diverts this plugin's exceptions away from the console and into {@link ErrorStore}.
 *
 * <p>Two hooks are needed, because the two ways a plugin's exception reaches the log take
 * different routes through java.util.logging:
 *
 * <ul>
 *   <li>Anything the plugin logs itself goes through its own logger. A handler there sees it,
 *       and {@code setUseParentHandlers(false)} keeps the console from also printing it — so
 *       this class has to re-emit the ordinary, throwable-free lines itself.</li>
 *   <li>An exception thrown out of an event handler is logged by the <em>server</em> logger, not
 *       ours, and a parent logger's filter is never consulted for records logged by a child. A
 *       filter installed there catches those, and only those: a record is swallowed only when
 *       its stack trace actually mentions this plugin, so other plugins' errors are untouched.</li>
 * </ul>
 *
 * <p>Both hooks are removed on {@link #uninstall()}, restoring whatever was there before. If the
 * server logger's filter has been replaced by someone else in the meantime, ours is left alone
 * rather than clobbering theirs.
 */
final class ErrorCapture {

    private static final String PACKAGE = "dev.lorenzzz.smplibrary";

    private final Smp_library plugin;
    private final ErrorStore store;
    private final boolean quietConsole;

    private Handler pluginHandler;
    private boolean previousUseParents;
    private Filter installedFilter;
    private Filter previousFilter;

    ErrorCapture(Smp_library plugin, ErrorStore store, boolean quietConsole) {
        this.plugin = plugin;
        this.store = store;
        this.quietConsole = quietConsole;
    }

    void install() {
        Logger pluginLogger = plugin.getLogger();
        previousUseParents = pluginLogger.getUseParentHandlers();

        pluginHandler = new Handler() {
            @Override public void publish(LogRecord record) { handlePluginRecord(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        pluginLogger.addHandler(pluginHandler);
        if (quietConsole) pluginLogger.setUseParentHandlers(false);

        if (!quietConsole) return;

        Logger serverLogger = Bukkit.getLogger();
        previousFilter = serverLogger.getFilter();
        installedFilter = record -> {
            if (isOurs(record)) {
                store.record("server", record.getMessage(), record.getThrown());
                return false;    // swallowed: the dashboard has it now
            }
            return previousFilter == null || previousFilter.isLoggable(record);
        };
        serverLogger.setFilter(installedFilter);
    }

    void uninstall() {
        Logger pluginLogger = plugin.getLogger();
        if (pluginHandler != null) {
            pluginLogger.removeHandler(pluginHandler);
            pluginHandler = null;
        }
        pluginLogger.setUseParentHandlers(previousUseParents);

        if (installedFilter != null) {
            Logger serverLogger = Bukkit.getLogger();
            // Someone else may have installed their own filter after us; theirs wins.
            if (serverLogger.getFilter() == installedFilter) serverLogger.setFilter(previousFilter);
            installedFilter = null;
            previousFilter = null;
        }
    }

    // ------------------------------------------------------------------------

    private void handlePluginRecord(LogRecord record) {
        if (record.getThrown() != null) {
            ErrorStore.Entry entry = store.record("plugin", record.getMessage(), record.getThrown());
            if (quietConsole) {
                // One line, no stack: enough to know something happened and where to read it.
                Bukkit.getLogger().log(Level.WARNING, "[" + plugin.getName() + "] "
                        + entry.headline() + " — dettagli nella sezione Debug della dashboard");
            }
            return;
        }
        // Ordinary logging still has to reach the console, since we took the parent handlers away.
        if (quietConsole) {
            Bukkit.getLogger().log(record.getLevel(), "[" + plugin.getName() + "] " + record.getMessage());
        }
    }

    /** True when this record is an exception that has our own code somewhere in its trace. */
    private static boolean isOurs(LogRecord record) {
        Throwable thrown = record.getThrown();
        if (thrown == null) return false;

        for (Throwable current = thrown; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith(PACKAGE)) return true;
            }
            if (current.getCause() == current) break;   // self-referencing cause chain
        }
        return false;
    }
}
