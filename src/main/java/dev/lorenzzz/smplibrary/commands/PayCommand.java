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
import dev.lorenzzz.smplibrary.modules.pay.TransferResult;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * {@code /pistacchipay <player> <amount>} — transfers "pistacchi" from the sender to another
 * online player. Not to be confused with the server's own {@code /pay}, which belongs to
 * zEssentials and moves {@code money}; this command is declared entirely by the
 * {@link CommandAlias} above and adds nothing to plugin.yml.
 *
 * <p>The money itself moves through {@link PayModule#transfer}, which either completes the whole
 * transfer or charges nothing at all; this class only validates the request, mints the transfer
 * id and reports the outcome.
 *
 * <p>The checks below (minimum, maximum, balance) are there to give the player a useful message,
 * not to make the transfer safe — by the time the money moves they are already stale. The real
 * balance check happens inside the transfer, under the lock.
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

        UUID transferId = pay.newTransferId();
        TransferResult result = pay.transfer(transferId, player, target, total);
        if (!result.completed()) {
            report(pay, sender, player, result, formatted);
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

    /**
     * Turns a refused transfer into a message. Every branch here charged nothing, so all of them
     * are safe to retry — the wording just tells the player whether waiting will help.
     */
    private static void report(PayModule pay, CommandSender sender, Player player,
                               TransferResult result, String formatted) {
        switch (result) {
            case INSUFFICIENT_FUNDS -> pay.send(sender, "insufficient-funds", Map.of(
                    "%balance%", pay.formatAmount(pay.getBalance(player)),
                    "%amount%", formatted));
            case COOLDOWN -> pay.send(sender, "cooldown", Map.of(
                    "%seconds%", String.valueOf(Math.max(1, pay.cooldownRemaining(player) / 1000))));
            // Both mean "the same payment is already being handled". Nothing to add: telling the
            // player to slow down is exactly right, and it costs them nothing to send it again.
            case IN_PROGRESS, DUPLICATE -> pay.send(sender, "already-processing", Map.of());
            default -> pay.send(sender, "error", Map.of());
        }
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
