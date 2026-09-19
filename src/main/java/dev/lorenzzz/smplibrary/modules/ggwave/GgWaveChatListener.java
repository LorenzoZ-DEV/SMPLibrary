package dev.lorenzzz.smplibrary.modules.ggwave;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * The gg wave's chat path when it could not go through ZelChat.
 *
 * <p>Only {@code event.message()} is touched, never the renderer: the message component is
 * the part the player typed, so whatever else on the server decorates chat — a format
 * plugin, a Discord relay — still sees a normal event and still does its job around it.
 *
 * <p>{@link EventPriority#LOWEST}, and deliberately <em>not</em> {@code ignoreCancelled}.
 * This ran at {@code HIGH, ignoreCancelled = true} and on a server with a chat plugin it
 * never fired once: a chat plugin consumes the event early — formats the line, sends it
 * itself, cancels the original — so by {@code HIGH} the event is cancelled and a handler
 * that ignores cancelled events is skipped. Running first instead means the message is
 * already recoloured when the chat plugin picks it up. A message that something later
 * cancels is checked by hand below, so a muted player's gg is not counted.
 *
 * <p>Registered by {@link GgWaveModule} only when {@link ZelChatHook} came back empty, so a
 * message is never recoloured twice.
 */
final class GgWaveChatListener implements Listener {

    private final GgWaveModule module;

    GgWaveChatListener(GgWaveModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()) return;
        if (!module.active()) return; // the usual case: no allocation, no serialisation

        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component styled = module.decorate(event.getPlayer(), raw, event.message());
        if (styled != null) event.message(styled);
    }
}
