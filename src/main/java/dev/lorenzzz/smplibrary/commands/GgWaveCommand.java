package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.ggwave.GgWaveModule;
import dev.lorenzzz.smplibrary.util.Ticks;
import org.bukkit.command.CommandSender;

/**
 * {@code /ggwave [player] [secondi]} — starts a wave by hand; {@code /ggwave stop} ends the
 * one running and {@code /ggwave status} says how long it has left. ACF only, no plugin.yml
 * entry needed.
 *
 * <p>This exists so the wave can be tried without spending money in the store: the real
 * trigger is the MineStore purchase broadcast, which is awkward to rehearse.
 */
@CommandAlias("ggwave|ondagg")
@CommandPermission(GgWaveModule.ADMIN_PERMISSION)
public final class GgWaveCommand extends BaseCommand {

    private GgWaveModule module() {
        return Smp_library.getInstance().getManagers().modules().module("ggwave", GgWaveModule.class);
    }

    @Default
    @CommandCompletion("@players")
    @Description("Fa partire a mano l'onda di GG.")
    public void onGgWave(CommandSender sender, @Optional String player, @Optional Integer seconds) {
        GgWaveModule wave = module();
        if (wave == null) {
            C.send(sender, "&cGG wave module is disabled.");
            return;
        }

        String buyer = player == null || player.isBlank() ? sender.getName() : player;
        long ticks = seconds == null ? wave.defaultDurationTicks() : Math.max(1, seconds) * Ticks.SECOND;
        wave.start(buyer, ticks);
        C.send(sender, "&aOnda GG avviata per &f" + buyer + "&a (" + ticks / Ticks.SECOND + "s).");
    }

    @Subcommand("stop")
    @Description("Ferma subito l'onda di GG in corso.")
    public void onStop(CommandSender sender) {
        GgWaveModule wave = module();
        if (wave == null) {
            C.send(sender, "&cGG wave module is disabled.");
            return;
        }
        if (!wave.active()) {
            C.send(sender, "&7Nessuna onda in corso.");
            return;
        }
        wave.stop();
        C.send(sender, "&aOnda GG fermata.");
    }

    @Subcommand("status")
    @Description("Dice se c'e' un'onda di GG in corso e quanto manca.")
    public void onStatus(CommandSender sender) {
        GgWaveModule wave = module();
        if (wave == null) {
            C.send(sender, "&cGG wave module is disabled.");
            return;
        }
        C.send(sender, wave.active()
                ? "&aOnda GG in corso: &f" + wave.secondsLeft() + "s &arimanenti."
                : "&7Nessuna onda in corso.");
        // The two numbers that explain a wave nobody's gg showed up in.
        C.send(sender, "&7Chat gestita via &f" + wave.chatPath()
                + "&7, messaggi visti da questo modulo: &f" + wave.seenMessages() + "&7.");
    }
}
