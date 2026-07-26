package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.HelpCommand;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Optional;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.adaptive.AdaptiveToolModule;
import dev.lorenzzz.smplibrary.modules.amethyst.AmethystToolModule;
import dev.lorenzzz.smplibrary.modules.trivella.TrivellaModule;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin root command. Alias/permission tunable in config.yml COMMANDS.smplibrary.
 * ACF picks the @CommandAlias up when the CommandManager registers this class.
 */
@CommandAlias("smplibrary|smpl|sl")
@CommandPermission("smplibrary.admin")
public final class SmplCommand extends BaseCommand {

    @Default
    @HelpCommand
    public void onHelp(CommandSender sender, CommandHelp help) {
        C.send(sender, "&d&lSMP-Library &7» &f/smplibrary <reload|help|modules>");
        C.send(sender, "&7  amethyst give <tool> [player]   |   adaptive give [player]   |   trivella give [player]");
        help.showHelp();
    }

    @Subcommand("reload")
    @CommandPermission("smplibrary.admin")
    public void onReload(CommandSender sender) {
        Smp_library plugin = Smp_library.getInstance();
        plugin.reloadConfig();
        plugin.getManagers().messages().reload();
        C.send(sender, "&aSMP-Library reloaded.");
    }

    @Subcommand("modules")
    @CommandPermission("smplibrary.admin")
    public void onModules(CommandSender sender) {
        C.send(sender, "&d&lModules:");
        plugin.modules().active().forEach(m ->
                C.send(sender, "&7- &f" + m.id() + " &a" + (plugin.modules().isEnabled(m.id()) ? "enabled" : "disabled")));
    }

    @Subcommand("amethyst give")
    @CommandPermission("smplibrary.admin")
    public void onAmethystGive(CommandSender sender, String tool, @Optional String targetName) {
        Player target = resolveTarget(sender, targetName);
        if (target == null) return;
        Module m = plugin.getManagers().modules().module("amethyst-tool");
        if (!(m instanceof AmethystToolModule mod)) { C.send(sender, "&camethyst-tool module not enabled."); return; }
        ItemStack item = mod.buildAmethyst(tool);
        if (item == null) {
            C.send(sender, plugin.getManagers().messages().get("amethyst-tool.unknown", Map.of("%tool%", tool)));
            return;
        }
        give(target, item);
        C.send(sender, plugin.getManagers().messages().get("amethyst-tool.given", Map.of("%tool%", tool, "%player%", target.getName())));
    }

    @Subcommand("adaptive give")
    @CommandPermission("smplibrary.admin")
    public void onAdaptiveGive(CommandSender sender, @Optional String targetName) {
        Player target = resolveTarget(sender, targetName);
        if (target == null) return;
        Module m = plugin.getManagers().modules().module("adaptive-tool");
        if (!(m instanceof AdaptiveToolModule mod)) { C.send(sender, "&cadaptive-tool module not enabled."); return; }
        ItemStack item = mod.buildAdaptive();
        if (item == null) { C.send(sender, "&cAdaptive tool not configured."); return; }
        give(target, item);
        C.send(sender, "&dGave the &5Adaptive Tool &dto &f" + target.getName() + "&d.");
    }

    @Subcommand("trivella give")
    @CommandPermission("smplibrary.admin")
    public void onTrivellaGive(CommandSender sender, @Optional String targetName) {
        Player target = resolveTarget(sender, targetName);
        if (target == null) return;
        Module m = plugin.getManagers().modules().module("trivella");
        if (!(m instanceof TrivellaModule mod)) { C.send(sender, "&ctrivella module not enabled."); return; }
        ItemStack item = mod.buildTrivella();
        if (item == null) { C.send(sender, "&cTrivella not configured."); return; }
        give(target, item);
        C.send(sender, "&dGave the &dTrivella &dto &f" + target.getName() + "&d.");
    }

    /** Resolve a give target — self if omitted (sender must be a player), else by exact name. */
    private Player resolveTarget(CommandSender sender, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            if (!(sender instanceof Player p)) { C.send(sender, "&cSpecify a player: ... give <tool> <player>"); return null; }
            return p;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) C.send(sender, "&cPlayer not found: " + targetName);
        return target;
    }

    /** Add to inventory, drop any overflow at the player's feet so nothing is lost. */
    private static void give(Player target, ItemStack item) {
        HashMap<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        if (!overflow.isEmpty()) overflow.values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
    }

    private static Smp_library plugin = Smp_library.getInstance();

}