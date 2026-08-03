package dev.lorenzzz.smplibrary.modules.web;

import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.Bukkit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded in-memory log + console echo for web dashboard actions.
 * ponytail: 100-entry ring, no persistence, no file rotation — admin watches
 * the live console; the deque only feeds /api/log and the SSE feed.
 */
public final class WebLog {

    private static final int MAX = 100;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Deque<String> entries = new ConcurrentLinkedDeque<>();

    public void log(String message) {
        String time = LocalDateTime.now().format(FMT);
        String line = "[LOG] " + time + " " + message;
        entries.addFirst(line);
        while (entries.size() > MAX) entries.removeLast();
        String console = "§9[smplibrary] §7[LOG] §f" + message;
        Bukkit.getConsoleSender().sendMessage(console);

        if (Bukkit.isPrimaryThread()) {
            broadcastToOps(message);
        } else {
            Bukkit.getScheduler().runTask(Smp_library.getInstance(), () -> broadcastToOps(message));
        }
    }

    private static void broadcastToOps(String message) {
        String chat = "§9[smplibrary] §7[LOG] §f" + message;
        for (var p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("smplibrary.admin")) p.sendMessage(chat);
        }
    }

    public java.util.List<String> recent() {
        return new java.util.ArrayList<>(entries);
    }
}
