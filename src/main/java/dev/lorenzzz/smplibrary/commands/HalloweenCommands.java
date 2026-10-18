package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.halloween.HalloweenModule;
import dev.lorenzzz.smplibrary.text.Chat;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * {@code /halloween} and {@code /dolcetto} — no plugin.yml entries needed, ACF only.
 *
 * <p>One class for both because they are two doors into the same module, and ACF registers a
 * root alias per method. {@code /halloween} carries its two extra actions as plain arguments
 * rather than as {@code @Subcommand}s, which a class with method-level root aliases cannot mix.
 */
public final class HalloweenCommands extends BaseCommand {

    private HalloweenModule module() {
        return Smp_library.getInstance().getManagers().modules()
                .module("halloween", HalloweenModule.class);
    }

    @CommandAlias("halloween|hw")
    @CommandPermission(HalloweenModule.USE_PERMISSION)
    @CommandCompletion("top|stats|forza candy-rain|soul-harvest|spooky-night|blood-moon|lucky-pumpkins")
    @Description("Stato dell'evento di Halloween, classifica e (per lo staff) evento del giorno.")
    public void onHalloween(CommandSender sender, @Optional String action, @Optional String value) {
        HalloweenModule halloween = module();
        if (halloween == null) {
            Chat.send(sender, "&cL'evento di Halloween non e' attivo.");
            return;
        }

        String request = action == null ? "" : action.toLowerCase(Locale.ROOT);
        switch (request) {
            case "" -> halloween.showStatus(sender);
            case "top", "classifica" -> halloween.showTop(sender, halloween.topLong());
            case "stats" -> {
                if (sender instanceof Player player) halloween.showStats(player);
                else Chat.send(sender, "&cSolo i giocatori hanno delle caramelle.");
            }
            case "forza", "force", "evento" -> {
                if (!sender.hasPermission(HalloweenModule.ADMIN_PERMISSION)) {
                    Chat.send(sender, "&cNon hai il permesso per cambiare l'evento del giorno.");
                    return;
                }
                halloween.pinEvent(sender, value);
            }
            default -> Chat.send(sender, "&7Uso: &f/halloween &7[&ftop&7|&fstats&7|&fforza <evento>&7]");
        }
    }

    @CommandAlias("dolcetto|dolcettoscherzetto|trickortreat")
    @CommandPermission(HalloweenModule.USE_PERMISSION)
    @Description("Dolcetto o scherzetto: una volta al giorno.")
    public void onTrickOrTreat(Player player) {
        HalloweenModule halloween = module();
        if (halloween == null) {
            Chat.send(player, "&cL'evento di Halloween non e' attivo.");
            return;
        }
        halloween.trickOrTreat(player);
    }
}
