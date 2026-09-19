package dev.lorenzzz.smplibrary.modules.info;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.commands.InfoCommands;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Mini;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Info links ported from venrix-utils: {@code /discord}, {@code /vote} and
 * {@code /store} (alias {@code /buy}).
 *
 * <p>Each answer is one MiniMessage line from messages.yml ({@code discord}, {@code vote},
 * {@code store}) so links keep click/hover events. The module itself only owns the
 * {@code MODULES.info.enabled} flag — the texts live in messages.yml.
 */
public final class InfoModule extends AbstractModule {

    private InfoCommands command;

    public InfoModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "info"; }

    @Override
    public void onEnable() {
        command = new InfoCommands();
        if (commands != null) commands.register(command);
    }

    @Override
    protected void onShutdown() {
        if (commands != null && command != null) commands.unregister(command);
        command = null;
    }

    /** Sends a raw MiniMessage info line — for the command class. */
    public void tell(CommandSender target, String key) {
        Mini.send(target, messages, key, Map.of());
    }
}
