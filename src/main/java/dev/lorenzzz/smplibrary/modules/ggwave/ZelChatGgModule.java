package dev.lorenzzz.smplibrary.modules.ggwave;

import it.pino.zelchat.api.message.ChatMessage;
import it.pino.zelchat.api.message.state.MessageState;
import it.pino.zelchat.api.module.ChatModule;
import it.pino.zelchat.api.module.annotation.ChatModuleSettings;
import it.pino.zelchat.api.module.priority.ModulePriority;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * The gg wave as a ZelChat module: it runs after ZelChat has built the line it is about to
 * send and swaps the gg tokens inside it for their coloured version, leaving the format,
 * the prefix and the player's rank exactly as ZelChat wrote them.
 *
 * <p>{@link ModulePriority#HIGHEST} on purpose — last in the chain, so a message that the
 * filter, the mute or another addon decided to drop is already marked as such when it gets
 * here and no work is done on a line nobody will read.
 */
@ChatModuleSettings(pluginOwner = "smp-library", priority = ModulePriority.HIGHEST)
final class ZelChatGgModule implements ChatModule {

    private final GgWaveModule module;

    ZelChatGgModule(GgWaveModule module) {
        this.module = module;
    }

    @Override
    public void handleChatMessage(final @NotNull ChatMessage chatMessage) {
        if (!module.active()) return;

        MessageState state = chatMessage.getState();
        if (state == MessageState.CANCELLED || state == MessageState.FILTERED_CANCELLED) return;
        if (chatMessage.doNothing()) return;

        Component styled = module.decorate(
                chatMessage.getBukkitPlayer(), chatMessage.getRawMessage(), chatMessage.getMessage());
        if (styled != null) chatMessage.setMessage(styled);
    }
}
