package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Syntax;
import co.aikar.commands.annotation.Single;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.pay.PayModule;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * /pay <player> <amount> — transfers "pistacchi" from the sender
 * to another online player. Shares economy with the shop module (zEssentials).
 */
@CommandAlias("pistacchipay|pagapers")
@CommandPermission("smplibrary.pay.use")
@Description("Trasferisci pistacchi a un altro player online.")
public final class PayCommand extends BaseCommand {

    @Default
    @CommandCompletion("@players @nothing")
    @Syntax("<player> <amount>")
    public void onPay(CommandSender sender, @Single String targetName, @Single String amountRaw) {
        if (!(sender instanceof Player player)) {
            C.send(sender, "&cOnly players.");
            return;
        }

        Smp_library plugin = Smp_library.getInstance();
        Module m = plugin.getManagers().modules().module("pay");
        if (!(m instanceof PayModule mod) || !plugin.getManagers().modules().isRunning("pay")) {
            C.send(sender, "&cPay module is disabled.");
            return;
        }

        if (mod.shop() == null) {
            C.send(sender, "&cShop module (economy) is not enabled. Cannot transfer pistacchi.");
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            mod.send(sender, "target-offline", Map.of("%player%", targetName));
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId()) && !mod.selfPayAllowed()) {
            mod.send(sender, "self-pay-disabled", Map.of());
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(amountRaw.replace(',', '.'));
        } catch (NumberFormatException e) {
            mod.send(sender, "invalid-amount", Map.of("%input%", amountRaw));
            return;
        }

        if (amount <= 0) {
            mod.send(sender, "invalid-amount", Map.of("%input%", amountRaw));
            return;
        }
        if (amount < mod.minimumAmount()) {
            Map<String, Object> ph = new HashMap<>();
            ph.put("%amount%", mod.formatAmount(mod.minimumAmount()));
            mod.send(sender, "below-minimum", ph);
            return;
        }
        double max = mod.maximumAmount();
        if (max > 0 && amount > max) {
            Map<String, Object> ph = new HashMap<>();
            ph.put("%amount%", mod.formatAmount(max));
            mod.send(sender, "above-maximum", ph);
            return;
        }

        double formattedAmount = mod.currencyDecimals() <= 0
                ? Math.floor(amount)
                : amount;
        String amountStr = mod.formatAmount(formattedAmount);

        double balance = mod.getBalance(player);
        if (balance < formattedAmount) {
            Map<String, Object> ph = new HashMap<>();
            ph.put("%balance%", mod.formatAmount(balance));
            ph.put("%amount%", amountStr);
            mod.send(sender, "insufficient-funds", ph);
            return;
        }

        if (!mod.takeMoney(player, formattedAmount)) {
            mod.send(sender, "error", Map.of());
            return;
        }
        if (!mod.giveMoney(target, formattedAmount)) {
            // ponytail: refund the sender if the deposit to the target failed
            mod.giveMoney(player, formattedAmount);
            mod.send(sender, "error", Map.of());
            return;
        }

        Map<String, Object> senderPh = new HashMap<>();
        senderPh.put("%player%", target.getName());
        senderPh.put("%amount%", amountStr);
        senderPh.put("%balance%", mod.formatAmount(mod.getBalance(player)));
        mod.send(player, "sent", senderPh);

        Map<String, Object> targetPh = new HashMap<>();
        targetPh.put("%player%", player.getName());
        targetPh.put("%amount%", amountStr);
        targetPh.put("%balance%", mod.formatAmount(mod.getBalance(target)));
        mod.send(target, "received", targetPh);
    }
}
