package dev.lorenzzz.smplibrary.manager;

import co.aikar.commands.PaperCommandManager;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.commands.KeyAllCommand;
import dev.lorenzzz.smplibrary.commands.SmplCommand;
import dev.lorenzzz.smplibrary.placeholders.LibraryPlaceholders;
import org.bukkit.Bukkit;

import java.util.Locale;

/**
 * Owns Aikar Commands (ACF) and wires the PlaceholderAPI toggle into {@link C}.
 * Modules register their own command classes through {@link #acf()}.
 */
public final class CommandManager implements Manager {

    private final Smp_library plugin;
    private PaperCommandManager acf;

    public CommandManager(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "commands"; }

    @Override
    public void enable() {
        acf = new PaperCommandManager(plugin);
        // ponytail: brigadier unstable API skipped — it registers PaperBrigadierManager, whose
        // CommandRegisteredEvent listener is deprecated in Paper 1.21.11. ACF's legacy command
        // path works fine for an admin command; tab-completion still works via ACF's own handler.
        // Re-enable with acf.enableUnstableAPI("brigadier") if client-side brigadier trees are wanted.
        acf.enableUnstableAPI("help");   // @HelpCommand / CommandHelp is gated behind this flag

        acf.getLocales().setDefaultLocale(Locale.ITALIAN);

        C.papi(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null);
        if (!C.papiEnabled()) {
            plugin.getLogger().info("PlaceholderAPI not present — placeholders skipped.");
        } else {
            new LibraryPlaceholders().register();
        }

        // Register the plugin's own ACF commands.
        register(new SmplCommand());
        register(new KeyAllCommand());
    }

    public PaperCommandManager acf() { return acf; }

    public void register(co.aikar.commands.BaseCommand... commands) {
        if (acf != null) for (var c : commands) acf.registerCommand(c);
    }

    @Override
    public void disable() {
        if (acf != null) {
            acf.unregisterCommands();
            acf = null;
        }
    }
}