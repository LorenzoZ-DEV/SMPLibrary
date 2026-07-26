package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;

/** Reads messages.yml and resolves paths through {@link C} (color + placeholders). */
public final class MessagesManager implements Manager {

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
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    public String raw(String path) {
        return yaml.getString(path, "");
    }

    /** Static + colored, no PAPI. */
    public String get(String path, Map<String, ?> placeholders) {
        return C.color(C.replace(raw(path), placeholders));
    }

    /** Static + player PAPI + colored. */
    public Component component(String path, Player player, Map<String, ?> placeholders) {
        return C.parse(raw(path), player, placeholders);
    }

    public void send(CommandSender target, String path, Map<String, ?> placeholders) {
        String msg = raw(path);
        if (msg.isEmpty()) return;
        Player p = target instanceof Player pl ? pl : null;
        target.sendMessage(C.parse(msg, p, placeholders));
    }
}