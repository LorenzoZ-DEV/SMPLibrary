package dev.lorenzzz.smplibrary.text;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns config text into something Minecraft can show.
 *
 * <p>Two input dialects are accepted, and each has its own entry point:
 * <ul>
 *   <li>{@link #legacy(String)} — {@code &a} codes and {@code &#RRGGBB} hex. Used for lore and
 *       chat lines built from config.</li>
 *   <li>{@link #mini(String)} — MiniMessage tags, with legacy and hex codes still allowed so old
 *       config keeps working. Used for inventory titles, item names and action bars.</li>
 * </ul>
 *
 * <p>Results are cached as finished {@link Component}s (they are immutable, so sharing is safe).
 * The inputs are overwhelmingly the same handful of config lines — action bars come back once a
 * second, per player — and Caffeine's admission policy keeps those while dropping one-shot
 * strings such as PlaceholderAPI output.
 */
public final class Text {

    /** {@code &a}, {@code &#RRGGBB} — only real codes, so a plain {@code &} in a URL survives. */
    private static final Pattern AMPERSAND_CODE =
            Pattern.compile("&(?:#([0-9a-fA-F]{6})|([0-9a-fk-orxA-FK-ORX]))");

    /** Any legacy code in either notation: {@code &#RRGGBB}, {@code &x&R&R&G&G&B&B}, {@code &a}, {@code §a}. */
    private static final Pattern LEGACY_CODE =
            Pattern.compile("[&§](?:#([0-9a-fA-F]{6})|[xX]((?:[&§][0-9a-fA-F]){6})|([0-9a-fk-orA-FK-OR]))");

    private static final Pattern SECTION_CODE = Pattern.compile("(?i)§[0-9a-fk-orx]");

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final Cache<String, String> COLORED = Caffeine.newBuilder().maximumSize(512).build();
    private static final Cache<String, Component> LEGACY_COMPONENTS = Caffeine.newBuilder().maximumSize(512).build();
    private static final Cache<String, Component> MINI_COMPONENTS = Caffeine.newBuilder().maximumSize(1024).build();

    private Text() {}

    // ---------------------------------------------------------------- legacy

    /** {@code &a} + {@code &#RRGGBB} -> section-sign string, for APIs that still take a String. */
    public static String color(String text) {
        if (text == null || text.isEmpty()) return "";
        return COLORED.get(text, Text::translate);
    }

    /** Text with every color and format code removed, in either notation. */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        return SECTION_CODE.matcher(color(text)).replaceAll("");
    }

    /** {@code &a} + {@code &#RRGGBB} -> Component, ready to send. */
    public static Component legacy(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return LEGACY_COMPONENTS.get(text, t -> LEGACY.deserialize(color(t)));
    }

    // ----------------------------------------------------------- MiniMessage

    /** MiniMessage (legacy and hex codes accepted too) -> Component. */
    public static Component mini(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return MINI_COMPONENTS.get(text, t -> MINI_MESSAGE.deserialize(toMiniMessage(t)));
    }

    /**
     * {@link #mini(String)} with tag resolvers, so values reach MiniMessage as resolved tags
     * instead of being spliced into the text. Splicing a value in first means whatever it holds
     * is parsed as markup — a player name like {@code <red>} would recolor the line. Pass such
     * values with {@code Placeholder.unparsed}. Not cached: the resolver changes the output.
     */
    public static Component mini(String text, TagResolver resolver) {
        if (text == null || text.isEmpty()) return Component.empty();
        return MINI_MESSAGE.deserialize(toMiniMessage(text), resolver);
    }

    /** Drops every cached result; call after a config reload so old lines do not linger. */
    public static void clearCache() {
        COLORED.invalidateAll();
        LEGACY_COMPONENTS.invalidateAll();
        MINI_COMPONENTS.invalidateAll();
    }

    // -------------------------------------------------------------- internals

    private static String translate(String text) {
        Matcher m = AMPERSAND_CODE.matcher(text);
        if (!m.find()) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        do {
            String hex = m.group(1);
            m.appendReplacement(out, hex != null ? sectionHex(hex) : "§" + m.group(2));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    /** {@code ff8800} -> {@code §x§f§f§8§8§0§0}. */
    private static String sectionHex(String hex) {
        StringBuilder out = new StringBuilder(14).append("§x");
        for (int i = 0; i < hex.length(); i++) {
            out.append('§').append(hex.charAt(i));
        }
        return out.toString();
    }

    /** Every legacy code -> its MiniMessage tag, in one pass, case-insensitive. */
    private static String toMiniMessage(String text) {
        Matcher m = LEGACY_CODE.matcher(text);
        if (!m.find()) return text;
        StringBuilder out = new StringBuilder(text.length() + 32);
        do {
            String tag;
            if (m.group(1) != null) {
                tag = "<#" + m.group(1) + ">";
            } else if (m.group(2) != null) {
                tag = "<#" + m.group(2).replaceAll("[&§]", "") + ">";
            } else {
                tag = tag(Character.toLowerCase(m.group(3).charAt(0)));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(tag));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    private static String tag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> "<reset>";
        };
    }
}
