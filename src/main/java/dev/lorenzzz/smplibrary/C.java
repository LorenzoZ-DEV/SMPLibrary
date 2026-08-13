package dev.lorenzzz.smplibrary;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text pipeline for the whole plugin: legacy {@code &x} codes, {@code &#RRGGBB} hex,
 * MiniMessage, PlaceholderAPI and console logging.
 *
 * <p>Two serializers live here on purpose. {@link #component(String)} goes through the
 * legacy section-sign path (cached, cheap — used for lore and chat lines built from
 * config), while {@link #mm(String)} goes through MiniMessage (used for inventory titles
 * and action bars, where MiniMessage tags are allowed in config).
 */
public final class C {

    private C() {}

    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern STRIP_PATTERN = Pattern.compile("(?i)§[0-9A-FK-ORX]");

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final Cache<String, String> COLOR_CACHE = Caffeine.newBuilder()
            .maximumSize(512)
            .build();

    /** Legacy code -> MiniMessage tag, applied in one pass by {@link #legacyToMiniMessage}. */
    private static final String[][] LEGACY_TO_TAG = {
            {"&0", "<black>"},      {"&1", "<dark_blue>"},   {"&2", "<dark_green>"},
            {"&3", "<dark_aqua>"},  {"&4", "<dark_red>"},    {"&5", "<dark_purple>"},
            {"&6", "<gold>"},       {"&7", "<gray>"},        {"&8", "<dark_gray>"},
            {"&9", "<blue>"},       {"&a", "<green>"},       {"&b", "<aqua>"},
            {"&c", "<red>"},        {"&d", "<light_purple>"},{"&e", "<yellow>"},
            {"&f", "<white>"},      {"&k", "<obfuscated>"},  {"&l", "<bold>"},
            {"&m", "<strikethrough>"}, {"&n", "<underline>"},{"&o", "<italic>"},
            {"&r", "<reset>"},
    };

    private static volatile boolean papi;

    // ---------------------------------------------------------------- colors

    /** {@code &#RRGGBB} hex + {@code &x} legacy codes -> section-sign string. */
    public static String translate(String message) {
        if (message == null) return "";
        Matcher matcher = HEX_COLOR_PATTERN.matcher(message);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, convertHexToMinecraftFormat(matcher.group(1)));
        }
        matcher.appendTail(buffer);
        return buffer.toString().replace('&', '§');
    }

    private static String convertHexToMinecraftFormat(String hex) {
        StringBuilder output = new StringBuilder("§x");
        for (char c : hex.toCharArray()) {
            output.append('§').append(c);
        }
        return output.toString();
    }

    /** Cached {@link #translate} — the same config lines get colorized over and over. */
    public static String color(String message) {
        if (message == null) return "";
        return COLOR_CACHE.get(message, C::translate);
    }

    public static String strip(String message) {
        if (message == null) return "";
        return STRIP_PATTERN.matcher(color(message)).replaceAll("");
    }

    public static String hexToMiniMessage(String text) {
        if (text == null) return "";
        return HEX_COLOR_PATTERN.matcher(text).replaceAll("<#$1>");
    }

    public static String legacyToMiniMessage(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (String[] pair : LEGACY_TO_TAG) {
            out = out.replace(pair[0], pair[1]);
        }
        return out;
    }

    // ------------------------------------------------------------ components

    /** Legacy + hex colorized input -> Adventure Component, ready to send. */
    public static Component component(String message) {
        return LEGACY.deserialize(color(message));
    }

    /** MiniMessage input (legacy + hex codes accepted too) -> Adventure Component. */
    public static Component mm(String text) {
        if (text == null) return Component.empty();
        return MINI_MESSAGE.deserialize(legacyToMiniMessage(hexToMiniMessage(text)));
    }

    // ---------------------------------------------------------- placeholders

    /** Toggle PlaceholderAPI support — set once by the CommandManager at boot. */
    public static void papi(boolean enabled) {
        papi = enabled;
    }

    public static boolean papiEnabled() {
        return papi;
    }

    /** Replace {@code %key%} -> value. Pure string ops, no colorize, no PAPI. */
    public static String replace(String message, Map<String, ?> placeholders) {
        if (message == null) return "";
        if (placeholders == null || placeholders.isEmpty()) return message;
        String out = message;
        for (Map.Entry<String, ?> e : placeholders.entrySet()) {
            out = out.replace(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    /** Replace %key% then PlaceholderAPI placeholders for the player. Returns raw string (not colorized). */
    public static String replace(String message, Player player, Map<String, ?> placeholders) {
        String out = replace(message, placeholders);
        if (papiEnabled() && player != null) {
            out = PlaceholderAPI.setPlaceholders(player, out);
        }
        return out;
    }

    /** Replace (with PAPI) + colorize + Component, ready to send. */
    public static Component parse(String message, Player player, Map<String, ?> placeholders) {
        return component(replace(message, player, placeholders));
    }

    // ---------------------------------------------------------------- output

    public static void send(CommandSender target, String message) {
        if (message == null || message.isEmpty()) return;
        target.sendMessage(component(message));
    }

    public static void sendChatAndAction(Player player, Component component) {
        player.sendMessage(component);
        player.sendActionBar(component);
    }

    public static void sendChatAndAction(Player player, String message) {
        if (message == null || message.isEmpty()) return;
        sendChatAndAction(player, mm(message));
    }

    // --------------------------------------------------------------- logging

    public static void info(String message) {
        console("&9[SMPLibrary] &7 " + message.replace('&', '§'));
    }

    public static void warning(String message) {
        Bukkit.getLogger().warning("[SMPLibrary] " + message);
    }

    public static void error(String message) {
        Bukkit.getLogger().severe("[SMPLibrary] " + message);
    }

    public static void debug(String message) {
        Bukkit.getLogger().info("[DEBUG] " + message);
    }

    public static void line() {
        console("&7----------------------------------------");
    }

    private static void console(String message) {
        Bukkit.getConsoleSender().sendMessage(translate(message));
    }

    // ---------------------------------------------------------------- banner

    private static final String LOGO = """
            &r
            &b░██████╗███╗░░░███╗██████╗░██╗░░░░░██╗██████╗░
            &b██╔════╝████╗░████║██╔══██╗██║░░░░░██║██╔══██╗
            &b╚█████╗░██╔████╔██║██████╔╝██║░░░░░██║██████╦╝
            &b░╚═══██╗██║╚██╔╝██║██╔═══╝░██║░░░░░██║██╔══██╗
            &b██████╔╝██║░╚═╝░██║██║░░░░░███████╗██║██████╦╝
            &b╚═════╝░╚═╝░░░░░╚═╝╚═╝░░░░░╚══════╝╚═╝╚═════╝░
            &r""";

    public static void logload() {
        console(LOGO + """

                  &7This Plugin is Developed by &4Lorenz-Development

                  &7Every project with this Development is Reserved and has a Copyright, if you want to use this Project, contact me on discord: &4vanixy
                  &4&lWARNING: &fIf you use this Plugin without Permission, you will be reported to Authorites and your data will be used to track you down and prosecute you to the fullest extent of the law!

                                &4&lPLUGIN IS CURRENTLY LOADING, PLEASE WAIT...
                """);
    }

    public static void logstart() {
        console(status("&aEnabled"));
    }

    public static void logstop() {
        console(status("&cDisabled"));
    }

    private static String status(String state) {
        return LOGO + """

                &bThis plugin is running on &aSMP-Library &bby &aLorenzzz&b.
                &r
                &fStatus: """ + state + "\n";
    }
}
