package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.debug.DebugModule;
import dev.lorenzzz.smplibrary.text.Chat;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /bugreport} (alias {@code /segnalabug}) — starts the report wizard. Everything the
 * command does lives in {@link DebugModule}; this class only checks that there is a player and a
 * running module to hand it to.
 */
@CommandAlias("bugreport|segnalabug")
@CommandPermission("smplibrary.bugreport.use")
@Description("Segnala un bug allo staff.")
public final class BugReportCommand extends BaseCommand {

    @Default
    public void onReport(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&cSolo un player puo' aprire una segnalazione.");
            return;
        }

        DebugModule debug = Smp_library.getInstance().modules() == null
                ? null : Smp_library.getInstance().modules().module("debug", DebugModule.class);
        if (debug == null) {
            Chat.send(sender, "&cLe segnalazioni sono disattivate su questo server.");
            return;
        }
        debug.startReport(player);
    }
}
