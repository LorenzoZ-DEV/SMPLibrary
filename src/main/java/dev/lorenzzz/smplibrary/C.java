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

import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface C

{
    static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    static String translate(String message){
        if (message == null) return "";
        Matcher matcher = HEX_COLOR_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while(matcher.find()){
            String hexColor = matcher.group(1);
            matcher.appendReplacement(buffer, convertHexToMinecraftFormat(hexColor));
        }
        matcher.appendTail(buffer);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
    static String convertHexToMinecraftFormat(String hex){
        StringBuilder output = new StringBuilder("§x");
        for(char c : hex.toCharArray()){
            output.append("§").append(c);
        }
        return output.toString();
    }

    static void info(String message){
        message = org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
        message = "&9[SMPLibrary] &7 " + message;
        org.bukkit.Bukkit.getConsoleSender().sendMessage(translate(message));
    }
    static void warning(String message){
        org.bukkit.Bukkit.getLogger().warning("[SMPLibrary] " + message);
    }
    static void error(String message){
        org.bukkit.Bukkit.getLogger().severe("[SMPLibrary] " + message);
    }
    static void line(){
        org.bukkit.Bukkit.getConsoleSender().sendMessage(translate("&7----------------------------------------"));
    }
    static void debug (String message){
        org.bukkit.Bukkit.getLogger().info("[DEBUG] " + message);
    }

    static void logstart(){
        String logmessage = """
                &r
                &b░██████╗███╗░░░███╗██████╗░██╗░░░░░██╗██████╗░
                &b██╔════╝████╗░████║██╔══██╗██║░░░░░██║██╔══██╗
                &b╚█████╗░██╔████╔██║██████╔╝██║░░░░░██║██████╦╝
                &b░╚═══██╗██║╚██╔╝██║██╔═══╝░██║░░░░░██║██╔══██╗
                &b██████╔╝██║░╚═╝░██║██║░░░░░███████╗██║██████╦╝
                &b╚═════╝░╚═╝░░░░░╚═╝╚═╝░░░░░╚══════╝╚═╝╚═════╝░
                &r
                &bThis plugin is running on &aSMP-Library &bby &aLorenzzz&b.
                &r
                &fStatus: &aEnabled
                """;
        Bukkit.getConsoleSender ( ).sendMessage ( C.translate ( logmessage ) );

    }
    static void logstop(){
        String logmessage = """
                &r
                &b░██████╗███╗░░░███╗██████╗░██╗░░░░░██╗██████╗░
                &b██╔════╝████╗░████║██╔══██╗██║░░░░░██║██╔══██╗
                &b╚█████╗░██╔████╔██║██████╔╝██║░░░░░██║██████╦╝
                &b░╚═══██╗██║╚██╔╝██║██╔═══╝░██║░░░░░██║██╔══██╗
                &b██████╔╝██║░╚═╝░██║██║░░░░░███████╗██║██████╦╝
                &b╚═════╝░╚═╝░░░░░╚═╝╚═╝░░░░░╚══════╝╚═╝╚═════╝░
                &r
                &bThis plugin is running on &aSMP-Library &bby &aLorenzzz&b.
                &r
                &fStatus: &cDisabled
                """;
        Bukkit.getConsoleSender ( ).sendMessage ( C.translate ( logmessage ) );

    }
    static void logload() {
        String logmessage = """
                &r
                &b░██████╗███╗░░░███╗██████╗░██╗░░░░░██╗██████╗░
                &b██╔════╝████╗░████║██╔══██╗██║░░░░░██║██╔══██╗
                &b╚█████╗░██╔████╔██║██████╔╝██║░░░░░██║██████╦╝
                &b░╚═══██╗██║╚██╔╝██║██╔═══╝░██║░░░░░██║██╔══██╗
                &b██████╔╝██║░╚═╝░██║██║░░░░░███████╗██║██████╦╝
                &b╚═════╝░╚═╝░░░░░╚═╝╚═╝░░░░░╚══════╝╚═╝╚═════╝░
                &r
                  
                  &7This Plugin is Developed by &4Lorenz-Development 
               
                  &7Every project with this Development is Reserved and has a Copyright, if you want to use this Project, contact me on discord: &4vanixy
                  &4&lWARNING: &fIf you use this Plugin without Permission, you will be reported to Authorites and your data will be used to track you down and prosecute you to the fullest extent of the law!
                 
                                &4&lPLUGIN IS CURRENTLY LOADING, PLEASE WAIT...
                """;
        Bukkit.getConsoleSender ( ).sendMessage ( C.translate ( logmessage ) );
    }

    static final Cache<String, String> COLOR_CACHE = Caffeine.newBuilder()
            .maximumSize(512)
            .build();
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    static final Pattern STRIP_PATTERN = Pattern.compile("(?i)§[0-9A-FK-ORX]");

    static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    static String hexToMiniMessage(String text) {
        if (text == null) return "";
        return HEX_COLOR_PATTERN.matcher(text).replaceAll("<#$1>");
    }

    static String legacyToMiniMessage(String text) {
        if (text == null || text.isEmpty()) return text;
        return text
                .replace("&0", "<black>").replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>").replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>").replace("&5", "<dark_purple>")
                .replace("&6", "<gold>").replace("&7", "<gray>")
                .replace("&8", "<dark_gray>").replace("&9", "<blue>")
                .replace("&a", "<green>").replace("&b", "<aqua>")
                .replace("&c", "<red>").replace("&d", "<light_purple>")
                .replace("&e", "<yellow>").replace("&f", "<white>")
                .replace("&k", "<obfuscated>").replace("&l", "<bold>")
                .replace("&m", "<strikethrough>").replace("&n", "<underline>")
                .replace("&o", "<italic>").replace("&r", "<reset>");
    }

    static Component mm(String text) {
        if (text == null) return Component.empty();
        return MINI_MESSAGE.deserialize(legacyToMiniMessage(hexToMiniMessage(text)));
    }

    // ponytail: interface fields are final, so keep the mutable papi flag in a 1-slot array.
    static final boolean[] PAPI = { false };

    /** Toggle PlaceholderAPI support — set once by the CommandManager at boot. */
    static void papi(boolean enabled){
        PAPI[0] = enabled;
    }

    static boolean papiEnabled(){
        return PAPI[0];
    }

    /** Cached color translation (delegates to {@link #translate}). */
    static String color(String message){
        if (message == null) return "";
        return COLOR_CACHE.get(message, C::translate);
    }

    /** Legacy + hex colorized input -> Adventure Component, ready to send. */
    static Component component(String message){
        return LEGACY.deserialize(color(message));
    }

    static void send(CommandSender target, String message){
        if (message == null || message.isEmpty()) return;
        target.sendMessage(component(message));
    }

    static void sendChatAndAction(Player player, Component component) {
        player.sendMessage(component);
        player.sendActionBar(component);
    }

    static void sendChatAndAction(Player player, String message) {
        if (message == null || message.isEmpty()) return;
        Component c = mm(message);
        player.sendMessage(c);
        player.sendActionBar(c);
    }

    static String strip(String message){
        if (message == null) return "";
        return STRIP_PATTERN.matcher(color(message)).replaceAll("");
    }

    /** Replace {@code %key%} -> value. Pure string ops, no colorize, no PAPI. */
    static String replace(String message, Map<String, ?> placeholders){
        if (message == null) return "";
        if (placeholders == null || placeholders.isEmpty()) return message;
        String out = message;
        for (Map.Entry<String, ?> e : placeholders.entrySet()){
            out = out.replace(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    /** Replace %key% then PlaceholderAPI placeholders for the player. Returns raw string (not colorized). */
    static String replace(String message, Player player, Map<String, ?> placeholders){
        String out = replace(message, placeholders);
        if (papiEnabled() && player != null){
            out = PlaceholderAPI.setPlaceholders(player, out);
        }
        return out;
    }

    /** Replace (with PAPI) + colorize + Component, ready to send. */
    static Component parse(String message, Player player, Map<String, ?> placeholders){
        return component(replace(message, player, placeholders));
    }


}
