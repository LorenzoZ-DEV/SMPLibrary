package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.Smp_library;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the evidence bundle the AI diagnosis reads: the server's own configuration, the tail of
 * the log, and a live snapshot of the worlds.
 *
 * <p>Two halves on purpose. {@link #runtime(Smp_library)} touches the Bukkit API and must run on
 * the server thread; {@link #files(File, int)} only reads files and must not — it is the slow
 * half. The caller collects the first, hands it to its executor, and adds the second there.
 *
 * <p>Everything leaves the machine, so everything is redacted first: any line whose key looks
 * like a credential loses its value, and anything shaped like a provider API key is blanked
 * wherever it appears. Sizes are capped per file and overall — a 400 MB {@code latest.log} must
 * not become a 400 MB request.
 */
public final class ServerEvidence {

    private static final int MAX_FILE_CHARS = 40_000;
    private static final int MAX_LOG_BYTES = 400_000;
    private static final int MAX_TOTAL_CHARS = 180_000;

    /** Server files worth reading, relative to the server root. Missing ones are skipped. */
    private static final List<String> CONFIG_FILES = List.of(
            "server.properties",
            "bukkit.yml",
            "spigot.yml",
            "paper.yml",
            "config/paper-global.yml",
            "config/paper-world-defaults.yml",
            "purpur.yml",
            "pufferfish.yml");

    /** {@code key: value} / {@code key=value} where the key smells like a credential. */
    private static final Pattern SECRET_LINE = Pattern.compile(
            "(?im)^(\\s*[\\w.\\-]*(?:password|passwd|secret|token|api-?key|licence|license|webhook)[\\w.\\-]*\\s*[:=]).*$");
    /**
     * Provider key shapes, blanked wherever they appear rather than only on a {@code key:} line.
     * {@code LLM_…} is OpenCode Zen's legacy key still honoured as env fallback;
     * {@code sk-ant-…} stays because a server that used an older build — or that runs another
     * plugin with its own Anthropic key — can still have one sitting in a config or a log;
     * {@code sk-…} covers DeepSeek (and OpenAI-compatible) keys, which is the default today.
     */
    private static final Pattern OPENCODE_KEY = Pattern.compile("LLM_[A-Za-z0-9_\\-]{8,}");
    private static final Pattern ANTHROPIC_KEY = Pattern.compile("sk-ant-[A-Za-z0-9_\\-]+");
    private static final Pattern GENERIC_SK_KEY = Pattern.compile("sk-[A-Za-z0-9]{8,}");

    private ServerEvidence() {}

    // --------------------------------------------------------------- runtime

    /** Live server state. <strong>Server thread only.</strong> */
    public static String runtime(Smp_library plugin) {
        StringBuilder text = new StringBuilder();

        text.append("## Server\n")
                .append("versione: ").append(Bukkit.getVersion()).append('\n')
                .append("bukkit: ").append(Bukkit.getBukkitVersion()).append('\n')
                .append("plugin smp-library: ").append(plugin.getPluginMeta().getVersion()).append('\n')
                .append("online: ").append(Bukkit.getOnlinePlayers().size())
                .append('/').append(Bukkit.getMaxPlayers()).append('\n')
                .append("view-distance: ").append(Bukkit.getViewDistance())
                .append("  simulation-distance: ").append(Bukkit.getSimulationDistance()).append('\n')
                .append("tps (1m/5m/15m): ").append(formatTps()).append('\n')
                .append("mspt medio: ").append(String.format("%.2f", Bukkit.getAverageTickTime())).append('\n');

        text.append("\n## Mondi\n");
        for (World world : Bukkit.getWorlds()) {
            text.append("- ").append(world.getName())
                    .append(" [").append(world.getEnvironment()).append(']')
                    .append(" chunk caricati=").append(world.getLoadedChunks().length)
                    .append(" entita=").append(world.getEntities().size())
                    .append(" difficolta=").append(world.getDifficulty())
                    .append('\n');
            text.append("  gamerule: ").append(gameRules(world)).append('\n');
        }

        text.append("\n## Plugin installati\n");
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            text.append("- ").append(other.getName())
                    .append(' ').append(other.getPluginMeta().getVersion())
                    .append(other.isEnabled() ? " (attivo)" : " (spento)")
                    .append('\n');
        }

        text.append("\n## Moduli smp-library\n");
        if (plugin.modules() != null) {
            plugin.modules().active().forEach(module -> text.append("- ").append(module.id())
                    .append(plugin.modules().isRunning(module.id()) ? " (in esecuzione)" : " (fermo)")
                    .append('\n'));
        }
        return text.toString();
    }

    private static String formatTps() {
        double[] tps = Bukkit.getTPS();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < tps.length; i++) {
            if (i > 0) text.append(" / ");
            text.append(String.format("%.2f", tps[i]));
        }
        return text.toString();
    }

    /**
     * Every gamerule whose value differs from the vanilla default, which is the only part worth
     * sending: a full dump of 40-odd rules per world is noise the diagnosis has to wade through.
     */
    private static String gameRules(World world) {
        StringBuilder text = new StringBuilder();
        for (GameRule<?> rule : RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE)) {
            Object value = world.getGameRuleValue(rule);
            Object standard = world.getGameRuleDefault(rule);
            if (value == null || value.equals(standard)) continue;
            if (!text.isEmpty()) text.append(", ");
            text.append(rule.getKey().getKey()).append('=').append(value);
        }
        return text.isEmpty() ? "(tutte ai valori di default)" : text.toString();
    }

    // ----------------------------------------------------------------- files

    /** Configuration files plus the tail of the latest log. <strong>Never on the server thread.</strong> */
    public static String files(File serverRoot, int logLines) {
        StringBuilder text = new StringBuilder();

        for (String name : CONFIG_FILES) {
            File file = new File(serverRoot, name);
            if (!file.isFile()) continue;
            text.append("\n## File: ").append(name).append('\n')
                    .append(redact(readCapped(file))).append('\n');
        }

        File log = new File(serverRoot, "logs/latest.log");
        if (log.isFile()) {
            text.append("\n## Ultime ").append(logLines).append(" righe di logs/latest.log\n")
                    .append(redact(tail(log, logLines))).append('\n');
        }

        // Inventario dei config modificabili (solo nomi, niente contenuti): il planner
        // puo' proporre fix su qualsiasi plugin ma solo su file che esistono davvero.
        List<String> inventory = inventory(serverRoot);
        if (!inventory.isEmpty()) {
            text.append("\n## Config modificabili dal BugFixxer (solo nomi file)\n");
            for (String name : inventory) text.append("- ").append(name).append('\n');
        }
        return text.toString();
    }

    /** Nomi dei .yml/.properties sotto plugins/ e config/, max 60 voci, mai contenuti. */
    private static List<String> inventory(File serverRoot) {
        List<String> names = new ArrayList<>();
        collectConfigs(new File(serverRoot, "plugins"), "plugins/", names, 3);
        collectConfigs(new File(serverRoot, "config"), "config/", names, 2);
        if (names.size() > 60) names = names.subList(0, 60);
        return names;
    }

    private static void collectConfigs(File dir, String prefix, List<String> out, int depth) {
        if (dir == null || depth < 0 || out.size() >= 60) return;
        File[] entries;
        try {
            entries = dir.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (entries == null) return;
        for (File entry : entries) {
            if (out.size() >= 60) return;
            String name = entry.getName();
            if (name.startsWith(".")) continue;
            if (entry.isDirectory()) {
                collectConfigs(entry, prefix + name + "/", out, depth - 1);
            } else if (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")) {
                String relative = prefix + name;
                if (relative.length() <= 160) out.add(relative);
            }
        }
    }

    /** Joins the parts, keeping the whole bundle under the size cap. */
    public static String assemble(String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (text.length() + part.length() > MAX_TOTAL_CHARS) {
                int room = MAX_TOTAL_CHARS - text.length();
                if (room > 500) text.append(part, 0, room).append("\n… (troncato)\n");
                break;
            }
            text.append(part).append('\n');
        }
        return text.toString();
    }

    // --------------------------------------------------------------- reading

    private static String readCapped(File file) {
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return content.length() <= MAX_FILE_CHARS
                    ? content
                    : content.substring(0, MAX_FILE_CHARS) + "\n… (file troncato)";
        } catch (IOException | RuntimeException e) {
            return "(impossibile leggere: " + e.getMessage() + ")";
        }
    }

    /** Last {@code lines} lines, reading at most the final {@value #MAX_LOG_BYTES} bytes. */
    private static String tail(File file, int lines) {
        try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
            long length = access.length();
            long from = Math.max(0, length - MAX_LOG_BYTES);
            access.seek(from);
            byte[] buffer = new byte[(int) (length - from)];
            access.readFully(buffer);

            String[] all = new String(buffer, StandardCharsets.UTF_8).split("\\R");
            int start = Math.max(from == 0 ? 0 : 1, all.length - lines);   // drop the half line
            List<String> kept = new ArrayList<>(all.length - start);
            for (int i = start; i < all.length; i++) kept.add(all[i]);
            return String.join("\n", kept);
        } catch (IOException | RuntimeException e) {
            return "(impossibile leggere il log: " + e.getMessage() + ")";
        }
    }

    // -------------------------------------------------------------- redaction

    /** Blanks credentials before anything leaves the machine. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) return "";
        String clean = SECRET_LINE.matcher(text).replaceAll("$1 ***");
        clean = OPENCODE_KEY.matcher(clean).replaceAll("LLM_***");
        clean = ANTHROPIC_KEY.matcher(clean).replaceAll("sk-ant-***");
        return GENERIC_SK_KEY.matcher(clean).replaceAll("sk-***");
    }
}
