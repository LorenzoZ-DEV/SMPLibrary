package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Single;
import co.aikar.commands.annotation.Syntax;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.pay.PayModule;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * {@code /pay <player> <amount>} — transfers "pistacchi" from the sender to another online
 * player. The money itself moves through {@link PayModule#transfer}, which refunds the sender
 * if the deposit fails; this class only validates the request and reports the outcome.
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

        PayModule pay = Smp_library.getInstance().getManagers().modules().module("pay", PayModule.class);
        if (pay == null) {
            C.send(sender, "&cPay module is disabled.");
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            pay.send(sender, "target-offline", Map.of("%player%", targetName));
            return;
        }
        if (target.equals(player) && !pay.selfPayAllowed()) {
            pay.send(sender, "self-pay-disabled", Map.of());
            return;
        }

        double amount = parseAmount(amountRaw);
        if (amount <= 0) {
            pay.send(sender, "invalid-amount", Map.of("%input%", amountRaw));
            return;
        }
        if (amount < pay.minimumAmount()) {
            pay.send(sender, "below-minimum", Map.of("%amount%", pay.formatAmount(pay.minimumAmount())));
            return;
        }
        double maximum = pay.maximumAmount();
        if (maximum > 0 && amount > maximum) {
            pay.send(sender, "above-maximum", Map.of("%amount%", pay.formatAmount(maximum)));
            return;
        }

        double total = pay.normalizeAmount(amount);
        String formatted = pay.formatAmount(total);

        double balance = pay.getBalance(player);
        if (balance < total) {
            pay.send(sender, "insufficient-funds", Map.of(
                    "%balance%", pay.formatAmount(balance),
                    "%amount%", formatted));
            return;
        }

        if (!pay.transfer(player, target, total)) {
            pay.send(sender, "error", Map.of());
            return;
        }

        pay.send(player, "sent", Map.of(
                "%player%", target.getName(),
                "%amount%", formatted,
                "%balance%", pay.formatAmount(pay.getBalance(player))));
        pay.send(target, "received", Map.of(
                "%player%", player.getName(),
                "%amount%", formatted,
                "%balance%", pay.formatAmount(pay.getBalance(target))));
    }

    /** Accepts both {@code 12.5} and the Italian {@code 12,5}; 0 marks anything unparsable. */
    private static double parseAmount(String raw) {
        try {
            double amount = Double.parseDouble(raw.replace(',', '.'));
            return Double.isFinite(amount) ? amount : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
