package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/** Manual fire of the key-all action. Shares config with KeyAllModule. */
@CommandAlias("keyall|keys")
@CommandPermission("smplibrary.keyall.use")
public final class KeyAllCommand extends BaseCommand {

    @Default
    public void onRun(CommandSender sender) {
        Smp_library plugin = Smp_library.getInstance();
        if (!plugin.getManagers().modules().isEnabled("keyall")) {
            C.send(sender, "&cKeyAll module is disabled.");
            return;
        }
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("MODULES.keyall");
        if (cfg == null) {
            C.send(sender, "&cKeyAll not configured.");
            return;
        }
        String command = cfg.getString("command", "");
        int amount = cfg.getInt("amount", 1);
        String crate = cfg.getString("crate-name", "common_crate");

        int count = 0;
        for (var p : Bukkit.getOnlinePlayers()) {
            String cmd = command
                    .replace("%player%", p.getName())
                    .replace("%crate-name%", crate)
                    .replace("%amount%", String.valueOf(amount));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            count++;
        }
        C.send(sender, cfg.getString("placeholder", "&dKeyAll &7» &frun for &f%players% &dplayers")
                .replace("%players%", String.valueOf(count)));
    }
}