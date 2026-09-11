package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * Reads messages.yml and resolves paths through {@link C} (color + placeholders).
 * Keys missing from the user's file fall back to the bundled defaults, so a new
 * message never shows up blank after an update.
 */
public final class MessagesManager implements Manager {

    /** Suffix of the optional short action-bar variant of a message key. */
    private static final String ACTION_BAR_SUFFIX = "-actionbar";

    private final Smp_library plugin;
    private File file;
    private YamlConfiguration yaml;

    public MessagesManager(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "messages"; }

    @Override
    public void enable() {
        file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    @Override public void disable() { yaml = null; }

    public void reload() {
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in));
                for (String key : defaults.getKeys(true)) {
                    if (!disk.contains(key)) disk.set(key, defaults.get(key));
                }
            }
        } catch (IOException e) {
            C.warning("Could not read the bundled messages.yml: " + e.getMessage());
        }
        yaml = disk;
    }

    // ------------------------------------------------------------------ reads

    public boolean has(String path) {
        return yaml != null && !raw(path).isEmpty();
    }

    public String raw(String path) {
        return yaml == null ? "" : yaml.getString(path, "");
    }

    /** Static + colored, no PAPI. */
    public String get(String path, Map<String, ?> placeholders) {
        return C.color(C.replace(raw(path), placeholders));
    }

    /** Static + player PAPI + colored. */
    public Component component(String path, Player player, Map<String, ?> placeholders) {
        return C.parse(raw(path), player, placeholders);
    }

    // ------------------------------------------------------------------ sends

    public void send(CommandSender target, String path, Map<String, ?> placeholders) {
        String message = raw(path);
        if (message.isEmpty()) return;
        target.sendMessage(C.parse(message, target instanceof Player p ? p : null, placeholders));
    }

    /** Sends the same text to chat and to the action bar. */
    public void sendBoth(Player target, String path, Map<String, ?> placeholders) {
        String message = raw(path);
        if (message.isEmpty()) return;
        C.sendChatAndAction(target, C.mm(C.replace(message, placeholders)));
    }

    /** Action bar only — silently skipped when the key is absent or blank. */
    public void actionBar(Player target, String path, Map<String, ?> placeholders) {
        String message = raw(path);
        if (message.isEmpty()) return;
        target.sendActionBar(C.mm(C.replace(message, target, placeholders)));
    }

    /**
     * Sends the message to the player, plus its optional {@code <path>-actionbar} companion.
     * Multi-line message blocks read badly on an action bar, hence the separate short key.
     */
    public void sendWithActionBar(Player target, String path, Map<String, ?> placeholders) {
        send(target, path, placeholders);
        actionBar(target, path + ACTION_BAR_SUFFIX, placeholders);
    }

    /**
     * {@link #sendWithActionBar} for every online player, rendered once when it can be.
     *
     * <p>Going through {@code sendWithActionBar} in a loop redid the whole pipeline per player:
     * two YAML reads, two {@code %key%} substitution passes and two deserializations — chat and
     * action bar — for a message that is usually identical for everyone. The cost is per player
     * and the message is not, which is the wrong way round.
     *
     * <p>So the shared work is done once: read the key, substitute the caller's placeholders,
     * and — unless what is left still holds a {@code %…%} that PlaceholderAPI would fill in
     * per player — deserialize a single {@link Component} and hand the same one to everybody.
     * A message that really is personal (a {@code %player_name%}, a balance) falls back to the
     * per-player path, and only for the half that needs it: a personal chat line with a static
     * action bar still renders the action bar once.
     */
    public void broadcast(String path, Map<String, ?> placeholders) {
        String chat = C.replace(raw(path), placeholders);
        String bar = C.replace(raw(path + ACTION_BAR_SUFFIX), placeholders);
        if (chat.isEmpty() && bar.isEmpty()) return;

        // Null means "this one has to be built per player".
        Component sharedChat = personal(chat) ? null : C.component(chat);
        Component sharedBar = personal(bar) ? null : C.mm(bar);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!chat.isEmpty()) {
                player.sendMessage(sharedChat != null ? sharedChat : C.parse(chat, player, null));
            }
            if (!bar.isEmpty()) {
                player.sendActionBar(sharedBar != null ? sharedBar : C.mm(C.replace(bar, player, null)));
            }
        }
    }

    /**
     * Whether the text still needs a player to be resolved. A leftover {@code %} is the only
     * signal PlaceholderAPI gives us, so this errs on the side of caution: a false positive costs
     * one broadcast the shared-component path, a false negative would send everyone the first
     * player's numbers.
     */
    private static boolean personal(String text) {
        return !text.isEmpty() && C.papiEnabled() && text.indexOf('%') >= 0;
    }
}
