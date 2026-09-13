package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.modules.web.WebStats;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One player-submitted bug report. Immutable except for {@link #status()}, which an admin
 * flips from the dashboard.
 *
 * @param id        short unique key, also what the dashboard sends back to change the status
 * @param author    the player's name at submission time (names change; the uuid is the identity)
 * @param authorId  the player's uuid as a string
 * @param type      one of the configured categories (blocchi, redstone, mob, …)
 * @param title     the one-line summary typed into the dialog
 * @param body      the description written in the book
 * @param media     screenshot / video link, or empty when the player skipped it
 * @param context   where the player was and what the server was running, captured at submit time
 * @param createdAt epoch millis
 * @param status    {@code open} or {@code closed}
 */
public record BugReport(String id, String author, String authorId, String type, String title,
                        String body, String media, String context, long createdAt, String status) {

    public static final String OPEN = "open";
    public static final String CLOSED = "closed";

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    public BugReport withStatus(String newStatus) {
        return new BugReport(id, author, authorId, type, title, body, media, context, createdAt, newStatus);
    }

    public String formattedDate() {
        return CLOCK.format(Instant.ofEpochMilli(createdAt));
    }

    public String json() {
        return "{\"id\":" + WebStats.jsonStr(id)
                + ",\"author\":" + WebStats.jsonStr(author)
                + ",\"authorId\":" + WebStats.jsonStr(authorId)
                + ",\"type\":" + WebStats.jsonStr(type)
                + ",\"title\":" + WebStats.jsonStr(title)
                + ",\"body\":" + WebStats.jsonStr(body)
                + ",\"media\":" + WebStats.jsonStr(media)
                + ",\"context\":" + WebStats.jsonStr(context)
                + ",\"status\":" + WebStats.jsonStr(status)
                + ",\"date\":" + WebStats.jsonStr(formattedDate())
                + ",\"createdAt\":" + createdAt + "}";
    }

    /** The form the AI reads: no ids, no markup, just what a human wrote. */
    public String asEvidence() {
        return "Segnalazione di " + author + " (" + formattedDate() + ")\n"
                + "Tipo: " + type + "\n"
                + "Titolo: " + title + "\n"
                + "Descrizione: " + body + "\n"
                + (media.isBlank() ? "" : "Media: " + media + "\n")
                + (context.isBlank() ? "" : "Contesto: " + context + "\n");
    }
}
