package dev.lorenzzz.smplibrary.text;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Sends text to players and the console. Blank messages are skipped, so empty config keys stay silent. */
public final class Chat {

    private Chat() {}

    /** Sends a legacy-coded ({@code &a}, {@code &#RRGGBB}) line. */
    public static void send(CommandSender target, String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) return;
        target.sendMessage(Text.legacy(legacyText));
    }

    /** Sends the same component to chat and to the action bar. */
    public static void sendBoth(Player player, Component message) {
        player.sendMessage(message);
        player.sendActionBar(message);
    }

    /** Sends a MiniMessage line to chat and to the action bar. */
    public static void sendBoth(Player player, String miniText) {
        if (miniText == null || miniText.isEmpty()) return;
        sendBoth(player, Text.mini(miniText));
    }
}
