package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.nightvision.NightVisionModule;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * {@code /nv [player]} — no plugin.yml entries needed, ACF only.
 *
 * <p>Two permissions, not one. The command shipped with none at all, which meant anyone
 * could hand themselves permanent night vision <em>and</em> reach across the server to flip
 * it on or off for any other player — the second half being the part that gets used to
 * blind someone mid-fight. Self-use stays open by default; targeting someone else does not.
 */
public final class NightVisionCommands extends BaseCommand {

    @CommandAlias("nightvision|nv")
    @CommandPermission(NightVisionModule.USE_PERMISSION)
    @CommandCompletion("@players")
    @Description("Attiva/disattiva la visione notturna.")
    public void onNightVision(Player player, @Optional Player target) {
        NightVisionModule module = Smp_library.getInstance().getManagers().modules()
                .module("nightvision", NightVisionModule.class);
        if (module == null) {
            C.send(player, "&cNight vision module is disabled.");
            return;
        }
        if (target != null && !target.equals(player)
                && !player.hasPermission(NightVisionModule.OTHERS_PERMISSION)) {
            module.tell(player, "night-vision.no-permission-other", Map.of());
            return;
        }
        module.toggle(player, target == null ? player : target);
    }
}
