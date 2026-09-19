package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.spawn.SpawnModule;
import org.bukkit.entity.Player;

/**
 * {@code /spawn} + {@code /setspawn} — no plugin.yml entries needed, ACF only.
 */
public final class SpawnCommands extends BaseCommand {

    private SpawnModule module() {
        return Smp_library.getInstance().getManagers().modules().module("spawn", SpawnModule.class);
    }

    @CommandAlias("spawn")
    @Description("Teletrasportati allo spawn.")
    public void onSpawn(Player player) {
        SpawnModule spawn = module();
        if (spawn == null) {
            player.sendMessage("Spawn module is disabled.");
            return;
        }
        spawn.teleportToSpawn(player);
    }

    @CommandAlias("setspawn")
    @CommandPermission(SpawnModule.SET_PERMISSION)
    @Description("Imposta lo spawn qui.")
    public void onSetSpawn(Player player) {
        SpawnModule spawn = module();
        if (spawn == null) {
            player.sendMessage("Spawn module is disabled.");
            return;
        }
        spawn.setSpawn(player, player.getLocation().clone());
    }
}
