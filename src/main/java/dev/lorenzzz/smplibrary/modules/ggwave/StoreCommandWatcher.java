package dev.lorenzzz.smplibrary.modules.ggwave;

import dev.lorenzzz.smplibrary.C;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Watches the store's broadcast command wherever it is dispatched from.
 *
 * <p>{@code ServerCommandEvent} only fires for commands the <em>server</em> runs — the
 * console, RCON. A plugin that calls {@code Bukkit.dispatchCommand(consoleSender, …)} goes
 * straight to the command map and fires nothing at all, and that is exactly how a store
 * plugin delivers a purchase command. Without this the wave would only ever start when an
 * admin typed the command by hand.
 *
 * <p>So the named commands are wrapped in the command map: every call still reaches the
 * real command untouched, and the line is handed to the observer <em>after</em> it has run,
 * so the store's own announcement is in chat before the wave's is. The originals go back on
 * shutdown, and a command that is not registered (LiteBans absent, different name) is simply
 * skipped.
 */
final class StoreCommandWatcher {

    /** Map key -> the command that was there before we swapped it in. */
    private final Map<String, Command> replaced = new LinkedHashMap<>();
    private final List<String> hooked = new ArrayList<>();

    /**
     * Wraps every registered command named in {@code labels}.
     *
     * @return the labels actually wrapped, for the boot log
     */
    List<String> hook(List<String> labels, Consumer<String> observer) {
        if (labels.isEmpty()) return List.of();

        Map<String, Command> known = knownCommands();
        if (known == null) return List.of();

        for (String label : labels) {
            if (label == null || label.isBlank()) continue;
            String name = label.trim().toLowerCase(Locale.ROOT);
            try {
                hookOne(known, name, observer);
            } catch (Throwable failed) {
                // Never fatal: the caller has other ways to see the store announcement, and
                // this used to throw out of the boot task and take the chat wiring with it.
                C.warning("ggwave: impossibile osservare il comando '" + name + "' ("
                        + failed.getClass().getSimpleName() + ": " + failed.getMessage() + ").");
            }
        }
        return List.copyOf(hooked);
    }

    /**
     * Swaps in the wrapper for one command and every key that points at it — aliases and the
     * namespaced spelling ({@code litebans:lbans}) are separate entries for the same command,
     * and the store firing an alias would otherwise slip past.
     *
     * <p>The matching keys are collected <em>before</em> anything is written back. Editing the
     * map through the entries while iterating it threw {@code ConcurrentModificationException}
     * on Paper, whose known-commands map re-registers the command with the Brigadier
     * dispatcher on write — which is a structural modification of the very map being walked.
     */
    private void hookOne(Map<String, Command> known, String name, Consumer<String> observer) {
        Command target = Bukkit.getCommandMap().getCommand(name);
        if (target == null || target instanceof Watched) return;

        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Command> entry : List.copyOf(known.entrySet())) {
            if (entry.getValue() == target) keys.add(entry.getKey());
        }
        if (keys.isEmpty()) return;

        Watched wrapper = new Watched(target, observer);
        for (String key : keys) {
            replaced.putIfAbsent(key, target);
            known.put(key, wrapper);
        }
        hooked.add(name);
    }

    /** Puts the original commands back; anything replaced since is left alone. */
    void unhook() {
        try {
            Map<String, Command> known = knownCommands();
            if (known != null) {
                for (Map.Entry<String, Command> entry : replaced.entrySet()) {
                    if (known.get(entry.getKey()) instanceof Watched) {
                        known.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (Throwable failed) {
            C.warning("ggwave: comandi non ripristinati (" + failed.getMessage() + ").");
        }
        replaced.clear();
        hooked.clear();
    }

    /** {@code null} on a server whose command map is not the standard one. */
    private static Map<String, Command> knownCommands() {
        try {
            CommandMap map = Bukkit.getCommandMap();
            if (map instanceof SimpleCommandMap simple) return simple.getKnownCommands();
            C.warning("ggwave: command map inattesa (" + map.getClass().getName()
                    + ") — l'onda partira' solo da ServerCommandEvent o /ggwave.");
        } catch (Throwable failed) {
            C.warning("ggwave: command map non raggiungibile (" + failed.getMessage() + ").");
        }
        return null;
    }

    /** A pass-through command that tells the observer what went through it. */
    private static final class Watched extends Command {

        private final Command delegate;
        private final Consumer<String> observer;

        private Watched(Command delegate, Consumer<String> observer) {
            super(delegate.getName(), delegate.getDescription(), delegate.getUsage(), delegate.getAliases());
            this.delegate = delegate;
            this.observer = observer;
            setPermission(delegate.getPermission());
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            try {
                return delegate.execute(sender, label, args);
            } finally {
                // After, not before: the store's own broadcast should reach chat first.
                // And in a finally, because a command that threw still announced the purchase.
                try {
                    observer.accept(label + (args.length == 0 ? "" : " " + String.join(" ", args)));
                } catch (Exception failed) {
                    C.warning("ggwave: errore nel leggere il comando '" + label + "': " + failed.getMessage());
                }
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return delegate.tabComplete(sender, alias, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) {
            return delegate.tabComplete(sender, alias, args, location);
        }

        @Override
        public boolean testPermissionSilent(CommandSender target) {
            return delegate.testPermissionSilent(target);
        }
    }
}
