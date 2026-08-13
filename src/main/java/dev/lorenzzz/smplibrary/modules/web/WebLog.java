package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.Bukkit;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-memory log + console echo for web dashboard actions.
 * ponytail: 100-entry ring, no persistence, no file rotation — admin watches
 * the live console; the deque only feeds /api/log and the SSE feed.
 */
public final class WebLog {

    private static final int MAX = 100;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Deque<String> entries = new ConcurrentLinkedDeque<>();
    private final AtomicLong version = new AtomicLong();

    public void log(String message) {
        entries.addFirst("[LOG] " + LocalTime.now().format(FMT) + " " + message);
        while (entries.size() > MAX) entries.removeLast();
        version.incrementAndGet();

        Bukkit.getConsoleSender().sendMessage("§9[smplibrary] §7[LOG] §f" + message);

        if (Bukkit.isPrimaryThread()) {
            broadcastToAdmins(message);
        } else {
            Smp_library plugin = Smp_library.getInstance();
            if (plugin != null) Bukkit.getScheduler().runTask(plugin, () -> broadcastToAdmins(message));
        }
    }

    /** Bumped on every entry — the SSE feed uses it to skip pushing an unchanged log. */
    public long version() {
        return version.get();
    }

    public List<String> recent() {
        return new ArrayList<>(entries);
    }

    private static void broadcastToAdmins(String message) {
        String chat = "§9[smplibrary] §7[LOG] §f" + message;
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("smplibrary.admin")) player.sendMessage(chat);
        }
    }
}
