package dev.lorenzzz.smplibrary.manager;

import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Merges keys added by a new build into the config.yml already sitting on the server.
 *
 * <p>{@code saveDefaultConfig()} writes the bundled file only when none exists, so on every
 * server that has ever run this plugin the on-disk config is frozen at whatever release created
 * it. Options added later are invisible: {@code getString("ai.endpoint", "")} quietly returns the
 * fallback, and the admin never sees the setting documented in the jar. Before this class the
 * only fix was to delete config.yml and lose every local value.
 *
 * <p>The merge is <strong>additive and non-destructive</strong>: a key that already exists on
 * disk keeps its value, whatever that value is. Only genuinely absent keys are written, together
 * with the comments that document them — this config carries its documentation inline, and a
 * merge that dropped the comments would strip that documentation off every new option.
 *
 * <p>Keys the jar no longer defines are left alone rather than deleted. A stale key costs
 * nothing (nothing reads it), while deleting one would throw away a value the admin may still
 * want when the option comes back.
 *
 * <p>{@link #merge} is deliberately free of Bukkit plugin state — it takes a file and a parsed
 * default, touches no singletons and logs nothing — so the merge can be exercised on its own.
 * {@link #update} is the thin server-side wrapper that reads the jar, logs, and reloads.
 */
public final class ConfigUpdater {

    /** Where the AI model id lives, for the one value migration below. */
    static final String MODEL_PATH = "MODULES.debug.ai.model";

    /** The pre-merge copy, overwritten on each merge that actually changes something. */
    static final String BACKUP_NAME = "config.yml.bak";

    private ConfigUpdater() {}

    /** What a merge did, so the caller can report it. */
    public record Result(List<String> added, String migratedModelFrom, String migratedModelTo) {

        public boolean migratedModel() {
            return migratedModelFrom != null;
        }

        public boolean changed() {
            return !added.isEmpty() || migratedModel();
        }
    }

    /**
     * Brings {@code config.yml} up to date with the bundled one.
     *
     * <p>Call before the modules read their settings — after this returns, {@code getConfig()}
     * reflects the merged file.
     */
    public static void update(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        // A fresh install was just written from the jar and is current by construction.
        if (!file.isFile()) return;

        InputStream stream = plugin.getResource("config.yml");
        if (stream == null) return;

        YamlConfiguration bundled;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            bundled = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            Log.warning("config.yml: impossibile leggere quello del jar (" + e.getMessage() + ")");
            return;
        }

        Result result;
        try {
            result = merge(file, bundled);
        } catch (IOException e) {
            Log.warning("config.yml: aggiornamento non riuscito (" + e.getMessage()
                    + "). Le impostazioni nuove restano ai valori di default.");
            return;
        }
        if (!result.changed()) return;

        // getConfig() may already be cached from before the merge.
        plugin.reloadConfig();

        if (result.migratedModel()) {
            Log.warning("config.yml: " + MODEL_PATH + " era \"" + result.migratedModelFrom()
                    + "\", un modello a consumo sul provider attuale. Impostato su \""
                    + result.migratedModelTo() + "\" (gratuito). Rimettilo a mano se volevi "
                    + "davvero quello a pagamento.");
        }
        List<String> added = result.added();
        if (!added.isEmpty()) {
            List<String> shown = added.size() > 6 ? added.subList(0, 6) : added;
            Log.info("config.yml aggiornato: " + added.size()
                    + (added.size() == 1 ? " impostazione nuova" : " impostazioni nuove")
                    + " (" + String.join(", ", shown) + (added.size() > 6 ? ", …" : "")
                    + "). Copia del precedente in " + BACKUP_NAME + ".");
        }
    }

    /**
     * Adds every key {@code bundled} has and {@code file} lacks, then saves if anything changed.
     *
     * @return what was added and whether the model was repointed; the file is untouched on disk
     *         when nothing changed
     */
    public static Result merge(File file, YamlConfiguration bundled) throws IOException {
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);

        List<String> added = new ArrayList<>();
        // getKeys(true) walks parents before children, so a section always exists by the time
        // its leaves are written.
        for (String path : bundled.getKeys(true)) {
            // contains(path, true) ignores defaults and asks only what is really in the file.
            if (current.contains(path, true)) continue;

            if (bundled.isConfigurationSection(path)) {
                current.createSection(path);
            } else {
                current.set(path, bundled.get(path));
            }
            current.setComments(path, bundled.getComments(path));
            added.add(path);
        }

        String was = current.getString(MODEL_PATH, "");
        String now = null;
        // The diagnosis feature used to talk to Anthropic, then OpenCode Zen, and now defaults
        // to DeepSeek. Old ids fail loudly on the new default (404) or bill on the wrong
        // provider, so a leftover value is repointed to the bundled default instead of kept.
        // This is the one place an existing value is rewritten rather than kept.
        if (was.startsWith("claude-") || was.startsWith("muse-spark-")
                || was.equals("deepseek-chat") || was.equals("deepseek-reasoner")) {
            now = bundled.getString(MODEL_PATH, "deepseek-v4-flash");
            current.set(MODEL_PATH, now);
        }

        Result result = new Result(added, now == null ? null : was, now);
        if (result.changed()) {
            // save() re-serialises the whole document rather than patching the lines that
            // changed: the root indentation is normalised and quoted scalars lose their quotes.
            // Values, comments and blank lines all survive that round trip — but it is still a
            // full rewrite of a file the admin hand-edited, so keep the previous one next to it.
            Files.copy(file.toPath(), file.toPath().resolveSibling(BACKUP_NAME),
                    StandardCopyOption.REPLACE_EXISTING);
            current.save(file);
        }
        return result;
    }
}
