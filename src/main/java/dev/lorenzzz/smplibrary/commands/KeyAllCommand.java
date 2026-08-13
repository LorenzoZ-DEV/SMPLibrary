package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.keyall.KeyAllModule;
import org.bukkit.command.CommandSender;

import java.util.Map;

/** Manual fire of the key-all action — runs the very same pass as the scheduled tick. */
@CommandAlias("keyall|keys")
@CommandPermission("smplibrary.keyall.use")
@Description("Distribuisce le chiavi a tutti i player online.")
public final class KeyAllCommand extends BaseCommand {

    @Default
    public void onRun(CommandSender sender) {
        Smp_library plugin = Smp_library.getInstance();
        KeyAllModule module = plugin.getManagers().modules().module("keyall", KeyAllModule.class);
        if (module == null) {
            C.send(sender, "&cKeyAll module is disabled.");
            return;
        }

        int served = module.run();
        plugin.getManagers().messages()
                .send(sender, "keyall.executed", Map.of("%players%", String.valueOf(served)));
    }
}
