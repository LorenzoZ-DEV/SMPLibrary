package dev.lorenzzz.smplibrary.modules.ggwave;

import dev.lorenzzz.smplibrary.C;
import it.pino.zelchat.api.ZelChatAPI;
import it.pino.zelchat.api.module.ModuleManager;
import org.bukkit.plugin.Plugin;

/**
 * Registers {@link ZelChatGgModule} with ZelChat. Only ever reached through
 * {@link ZelChatHook}, which has already confirmed the API is loadable.
 *
 * <p>ZelChat owns the chat on this server: it builds the final line — prefix, rank, name,
 * the lot — and sends it itself. Recolouring chat by cancelling {@code AsyncChatEvent} and
 * printing our own line would throw all of that away, so the wave goes in as a ZelChat
 * module and edits the message ZelChat is about to send.
 */
final class ZelChatBridge {

    private ZelChatBridge() {}

    /** @return the undo, to be run when the module shuts down or the plugin reloads */
    static Runnable attach(Plugin plugin, GgWaveModule module) {
        ZelChatAPI api = ZelChatAPI.get();
        ModuleManager modules = api.getModuleManager();
        ZelChatGgModule chatModule = new ZelChatGgModule(module);

        modules.register(plugin, chatModule);
        chatModule.load();

        return () -> {
            try {
                chatModule.unload();
                modules.unregister(plugin, chatModule);
            } catch (Throwable failed) {
                // ZelChat may already be gone if the whole server is shutting down.
                C.warning("ggwave: errore nel togliere il modulo da ZelChat: " + failed.getMessage());
            }
        };
    }
}
