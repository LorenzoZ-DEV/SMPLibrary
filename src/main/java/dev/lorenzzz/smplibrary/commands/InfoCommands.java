package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.Description;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.info.InfoModule;
import org.bukkit.command.CommandSender;

/**
 * {@code /discord}, {@code /vote}, {@code /store} — no plugin.yml entries needed, ACF only.
 */
public final class InfoCommands extends BaseCommand {

    private InfoModule module() {
        return Smp_library.getInstance().getManagers().modules().module("info", InfoModule.class);
    }

    @CommandAlias("discord")
    @Description("Mostra il link Discord del server.")
    public void onDiscord(CommandSender sender) {
        InfoModule info = module();
        if (info == null) {
            C.send(sender, "&cInfo module is disabled.");
            return;
        }
        info.tell(sender, "discord");
    }

    @CommandAlias("vote")
    @Description("Mostra dove votare il server.")
    public void onVote(CommandSender sender) {
        InfoModule info = module();
        if (info == null) {
            C.send(sender, "&cInfo module is disabled.");
            return;
        }
        info.tell(sender, "vote");
    }

    @CommandAlias("store|buy")
    @Description("Mostra lo store del server.")
    public void onStore(CommandSender sender) {
        InfoModule info = module();
        if (info == null) {
            C.send(sender, "&cInfo module is disabled.");
            return;
        }
        info.tell(sender, "store");
    }
}
