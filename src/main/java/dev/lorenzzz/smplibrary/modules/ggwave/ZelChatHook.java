package dev.lorenzzz.smplibrary.modules.ggwave;

import dev.lorenzzz.smplibrary.C;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Decides whether the gg wave goes through ZelChat, and does not name a single ZelChat type
 * while deciding — {@link ZelChatBridge} and {@link ZelChatGgModule} hold every reference to
 * {@code it.pino.zelchat}, and neither is touched before the API is confirmed loadable.
 *
 * <p>Same shape as {@code SparkHook}, and for the same reason: {@code zelchat-api} is
 * {@code compileOnly}, so on a server without it those classes simply are not there and
 * loading one a moment too early is a {@code NoClassDefFoundError} at plugin enable.
 *
 * <p>The check is a classpath question and not only a plugin lookup, because "ZelChat is
 * installed" and "the 2.0 module API is available" are two different things: a server still
 * on ZelChat 1.x has the plugin and not the API.
 *
 * <p>And "the API is available" is a third thing again. {@code ZelChatProvider} is populated
 * by ZelChat while it starts, so asking for it too early throws
 * {@code "API is not set! Please specify in plugin.yml ZelChat as dependency."} even though
 * everything is installed correctly and the dependency <em>is</em> declared. That is a
 * <em>not yet</em>, not a no — {@link Attachment#retry()} says so, and the caller tries
 * again shortly rather than giving up on the first look.
 */
final class ZelChatHook {

    /** Entry point of the 2.0 module API; absent on 1.x and on servers without ZelChat. */
    private static final String API_CLASS = "it.pino.zelchat.api.ZelChatAPI";
    private static final String PLUGIN = "ZelChat";

    private ZelChatHook() {}

    /**
     * The outcome of one attempt.
     *
     * @param detach undoes the attachment; non-null only when it succeeded
     * @param retry  true when ZelChat is there but not ready — ask again in a moment
     */
    record Attachment(Runnable detach, boolean retry) {

        static final Attachment GIVE_UP = new Attachment(null, false);
        static final Attachment LATER = new Attachment(null, true);

        boolean attached() {
            return detach != null;
        }
    }

    /** One attempt at registering the wave as a ZelChat module. Never throws. */
    static Attachment install(Plugin plugin, GgWaveModule module) {
        Plugin zelchat = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (zelchat == null) return Attachment.GIVE_UP; // not installed: nothing to say

        if (!apiOnClasspath()) {
            C.warning("ggwave: ZelChat e' installato ma l'API dei moduli 2.0 non c'e' ("
                    + API_CLASS + " non e' sul classpath, di solito significa ZelChat 1.x)"
                    + " — la chat passa da AsyncChatEvent.");
            return Attachment.GIVE_UP;
        }
        // Loaded but not started: every plugin is registered before any is enabled, so this
        // is the normal state when the server happens to enable us first.
        if (!zelchat.isEnabled()) return Attachment.LATER;

        try {
            return new Attachment(ZelChatBridge.attach(plugin, module), false);
        } catch (Throwable failed) {
            // Throwable: a renamed or removed API class arrives as an Error, not an Exception.
            if (notReadyYet(failed)) return Attachment.LATER;
            C.warning("ggwave: aggancio a ZelChat non riuscito (" + describe(failed)
                    + ") — la chat passa da AsyncChatEvent.");
            return Attachment.GIVE_UP;
        }
    }

    /**
     * Whether the failure is ZelChat still starting rather than ZelChat being unusable.
     *
     * <p>{@code ZelChatProvider.getApi()} is a {@code Objects.requireNonNull} on a static
     * field ZelChat fills in as it boots, so "not published yet" surfaces as a plain
     * {@link NullPointerException}. {@link IllegalStateException} is covered too, in case a
     * later version says it more clearly.
     */
    private static boolean notReadyYet(Throwable failed) {
        return failed instanceof NullPointerException || failed instanceof IllegalStateException;
    }

    /** The reason to report once the retries are spent. */
    static String pendingReason() {
        Plugin zelchat = Bukkit.getPluginManager().getPlugin(PLUGIN);
        return zelchat != null && !zelchat.isEnabled()
                ? "ZelChat non si e' avviato"
                : "ZelChat non ha ancora pubblicato la sua API";
    }

    private static boolean apiOnClasspath() {
        try {
            Class.forName(API_CLASS, false, ZelChatHook.class.getClassLoader());
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
