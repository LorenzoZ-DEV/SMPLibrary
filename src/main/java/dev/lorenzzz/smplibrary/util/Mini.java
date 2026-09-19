package dev.lorenzzz.smplibrary.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends MiniMessage texts from messages.yml — the ported venrix features use MiniMessage
 * ({@code <red>}, {@code <click:open_url:...>}, ...) while the rest of the plugin speaks
 * legacy {@code &} codes through {@link MessagesManager#send}.
 *
 * <p>Placeholders are {@code <name>} tokens (venrix style): pass them without brackets.
 * {@code <prefix>} always resolves to the {@code venrix.prefix} message.
 *
 * <p>Values are handed to MiniMessage as <em>unparsed</em> tags, never pasted into the text.
 * Splicing them in first made every placeholder a markup injection point: any value that
 * happened to contain {@code <...>} was parsed as a tag, and the values here are names —
 * which are not always the plain {@code [A-Za-z0-9_]} a vanilla client is limited to once
 * Geyser/Floodgate prefixes or a proxy are in the picture. An unparsed tag renders its value
 * as literal text, so the worst a hostile name can do is look odd.
 */
public final class Mini {

    /**
     * Legacy hex runs ({@code &x&8&A&2&B&E&2}) as used by the venrix prefix — neither
     * {@link C#mm} nor MiniMessage itself understands them, and without this they leak
     * into chat as literal {@code &x...} garbage (also matching {@code &8} → dark_gray
     * mid-sequence). Converted to {@code <#RRGGBB>} before parsing.
     */
    private static final Pattern LEGACY_HEX = Pattern.compile("(?i)&x((?:&[0-9a-f]){6})");
    private static final Pattern SECTION_HEX = Pattern.compile("§x((?:§[0-9a-fA-F]){6})");

    /** Suffix of the optional short action-bar variant of a message key. */
    private static final String ACTION_BAR_SUFFIX = "-actionbar";

    /**
     * Prepared templates, keyed by message key: the YAML read, the {@code <prefix>} splice and
     * both hex passes, which used to run on every single send. Action bars make that a per
     * player, per second cost.
     */
    private static final Cache<String, String> TEMPLATES = Caffeine.newBuilder()
            .maximumSize(512)
            .build();

    /**
     * Whole components for keys sent without placeholders ({@code combat.enter},
     * {@code spawn.teleported}, ...) — those are constant, so MiniMessage only has to parse
     * them once. Components are immutable, sharing one across players is safe.
     */
    private static final Cache<String, Component> STATIC = Caffeine.newBuilder()
            .maximumSize(512)
            .build();

    /** Keys already reported missing, so a broken message logs one line and not one per send. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Mini() {}

    /** Drops the prepared texts; called by {@link MessagesManager#reload()}. */
    public static void invalidate() {
        TEMPLATES.invalidateAll();
        STATIC.invalidateAll();
        REPORTED.clear();
    }

    /**
     * The message, or an empty component when the key is missing or blank — the old behaviour
     * was to show players {@code missing message: combat.enter}, which is a console problem
     * spelled out in chat.
     */
    public static Component render(MessagesManager messages, String key, Map<String, String> placeholders) {
        Component rendered = renderOrNull(messages, key, placeholders);
        if (rendered == null) {
            reportMissing(key);
            return Component.empty();
        }
        return rendered;
    }

    /** As {@link #render}, but {@code null} rather than empty when there is nothing to send. */
    private static Component renderOrNull(MessagesManager messages, String key,
                                          Map<String, String> placeholders) {
        String template = template(messages, key);
        if (template.isEmpty()) return null;
        if (placeholders == null || placeholders.isEmpty()) {
            return STATIC.get(key, unused -> C.mm(template));
        }
        return C.mm(template, resolvers(placeholders));
    }

    private static String template(MessagesManager messages, String key) {
        return TEMPLATES.get(key, path -> {
            String raw = messages.raw(path);
            if (raw == null || raw.isBlank()) return "";
            return legacyHexToMini(raw.replace("<prefix>", messages.raw("venrix.prefix")));
        });
    }

    private static TagResolver resolvers(Map<String, String> placeholders) {
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.unparsed(entry.getKey(),
                    entry.getValue() == null ? "" : entry.getValue()));
        }
        return builder.build();
    }

    private static void reportMissing(String key) {
        if (REPORTED.add(key)) C.warning("messages.yml: chiave mancante o vuota '" + key + "'.");
    }

    private static String legacyHexToMini(String text) {
        Matcher legacy = LEGACY_HEX.matcher(text);
        StringBuffer out = new StringBuffer(text.length() + 16);
        while (legacy.find()) {
            legacy.appendReplacement(out,
                    Matcher.quoteReplacement("<#" + legacy.group(1).replace("&", "") + ">"));
        }
        legacy.appendTail(out);
        Matcher section = SECTION_HEX.matcher(out.toString());
        StringBuffer clean = new StringBuffer(out.length());
        while (section.find()) {
            section.appendReplacement(clean,
                    Matcher.quoteReplacement("<#" + section.group(1).replace("§", "") + ">"));
        }
        section.appendTail(clean);
        return clean.toString();
    }

    /**
     * Chat + action bar, following the plugin convention: the {@code <key>-actionbar}
     * companion goes over the hotbar, skipped when absent.
     *
     * <p>Absent used to mean "repeat the chat line over the hotbar unless the key is on a
     * hard-coded exception list" — which quietly sent a second copy of every new message and
     * needed that list extended each time. Now the companion key is the whole rule.
     */
    public static void send(CommandSender target, MessagesManager messages, String key,
                            Map<String, String> placeholders) {
        Component chat = renderOrNull(messages, key, placeholders);
        if (chat == null) {
            reportMissing(key);
            return;
        }
        target.sendMessage(chat);
        if (target instanceof Player player) {
            Component bar = renderOrNull(messages, key + ACTION_BAR_SUFFIX, placeholders);
            if (bar != null) player.sendActionBar(bar);
        }
    }

    public static void actionBar(Player player, MessagesManager messages, String key,
                                 Map<String, String> placeholders) {
        Component bar = renderOrNull(messages, key, placeholders);
        if (bar == null) {
            reportMissing(key);
            return;
        }
        player.sendActionBar(bar);
    }

    /** Rendered once and handed to everyone — the text is the same for all of them. */
    public static void broadcast(MessagesManager messages, String key, Map<String, String> placeholders) {
        Component chat = renderOrNull(messages, key, placeholders);
        if (chat == null) {
            reportMissing(key);
            return;
        }
        Bukkit.broadcast(chat);
        Component bar = renderOrNull(messages, key + ACTION_BAR_SUFFIX, placeholders);
        if (bar == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) player.sendActionBar(bar);
    }
}
