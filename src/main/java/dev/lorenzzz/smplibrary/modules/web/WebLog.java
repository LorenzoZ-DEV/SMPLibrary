package dev.lorenzzz.smplibrary.modules.web;

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
        String line = "[WEB] " + LocalDateTime.now().format(FMT) + " " + message;
        entries.addFirst(line);
        while (entries.size() > MAX) entries.removeLast();
        Bukkit.getConsoleSender().sendMessage("§9[WEB] §7" + message);
    }

    public java.util.List<String> recent() {
        return new java.util.ArrayList<>(entries);
    }
}
