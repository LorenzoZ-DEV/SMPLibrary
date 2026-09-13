package dev.lorenzzz.smplibrary.modules.gui;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.util.SoundCue;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code inventories/gui.yml} — copying the bundled one out on first run, and topping up
 * a server's own copy with keys added since it was written.
 *
 * <p>The same shape as {@link dev.lorenzzz.smplibrary.modules.settings.SettingsConfigLoader},
 * for the same reason: the dashboard writes this file, so it has to survive being edited by
 * hand and re-read without a restart.
 */
final class GuiConfigLoader {

    private static final String RESOURCE = "inventories/gui.yml";
    private static final int MAX_ROWS = 6;
    private static final int COLUMNS = 9;

    private static final SoundCue DEFAULT_OPEN = SoundCue.of("block.chest.open", 0.5f, 1.4f);
    private static final SoundCue DEFAULT_PAGE = SoundCue.of("ui.button.click", 0.6f, 1.6f);
    private static final SoundCue DEFAULT_TELEPORT = SoundCue.of("entity.enderman.teleport", 0.5f, 1.6f);
    private static final SoundCue DEFAULT_EMPTY = SoundCue.of("block.note_block.bass", 0.6f, 0.8f);

    private final Smp_library plugin;
    private GuiConfig config;

    GuiConfigLoader(Smp_library plugin) {
        this.plugin = plugin;
    }

    GuiConfig get() { return config; }

    GuiConfig reload() {
        load();
        return config;
    }

    void load() {
        File dir = new File(plugin.getDataFolder(), "inventories");
        dir.mkdirs();

        File file = new File(dir, "gui.yml");
        if (!file.exists()) {
            try (InputStream in = plugin.getResource(RESOURCE)) {
                if (in == null) {
                    C.warning("gui.yml not found in jar!");
                    return;
                }
                Files.copy(in, file.toPath());
                C.info("Saved default gui.yml");
            } catch (IOException e) {
                C.warning("Could not save gui.yml: " + e.getMessage());
                return;
            }
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        mergeDefaults(yaml, file);
        config = parse(yaml);
    }

    /**
     * Adds the keys the bundled file has and the server's copy doesn't, then writes it back.
     * Values already there are never touched, so edited titles, layouts and lore survive.
     */
    private void mergeDefaults(YamlConfiguration yaml, File file) {
        try (InputStream in = plugin.getResource(RESOURCE)) {
            if (in == null) return;

            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in));
            int added = 0;
            for (String key : bundled.getKeys(true)) {
                // Sections are created implicitly by their leaves; setting one wholesale would
                // store a live section object instead of plain values.
                if (bundled.isConfigurationSection(key) || yaml.contains(key)) continue;
                yaml.set(key, bundled.get(key));
                added++;
            }

            if (added == 0) return;
            yaml.save(file);
            C.info("gui.yml aggiornato: " + added + " chiavi nuove aggiunte dai default.");
        } catch (IOException e) {
            C.warning("Could not merge the bundled gui.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ parse

    private GuiConfig parse(YamlConfiguration yaml) {
        return new GuiConfig(
                screen(yaml.getConfigurationSection("homes"), "&#8b00ff&lLE TUE HOME", "home %name%", "LIME_BED"),
                screen(yaml.getConfigurationSection("warps"), "&#00b3ff&lWARP", "warp %name%", "ENDER_EYE"),
                icons(yaml.getConfigurationSection("items")),
                intercept(yaml.getConfigurationSection("intercept")),
                SoundCue.from(yaml.getConfigurationSection("sounds.open"), DEFAULT_OPEN),
                SoundCue.from(yaml.getConfigurationSection("sounds.page"), DEFAULT_PAGE),
                SoundCue.from(yaml.getConfigurationSection("sounds.teleport"), DEFAULT_TELEPORT),
                SoundCue.from(yaml.getConfigurationSection("sounds.empty"), DEFAULT_EMPTY));
    }

    private ScreenConfig screen(ConfigurationSection sec, String title, String command, String material) {
        if (sec == null) {
            return new ScreenConfig(true, title, defaultLayout(), command, "", "&a&lPUBBLICA", "&7&lPRIVATA",
                    false, new IconConfig(material, "&f%name%", List.of(), false, "", false));
        }
        return new ScreenConfig(
                sec.getBoolean("enabled", true),
                sec.getString("title", title),
                layout(sec.getStringList("layout")),
                sec.getString("command", command),
                sec.getString("no-description", ""),
                sec.getString("public", "&a&lPUBBLICA"),
                sec.getString("private", "&7&lPRIVATA"),
                sec.getBoolean("filter-by-permission", false),
                icon(sec.getConfigurationSection("item"), material));
    }

    /**
     * Squares the layout off: at most six rows, every row exactly nine characters.
     *
     * <p>A hand-written row one character short would otherwise silently shift every slot after
     * it, and a row too long would place buttons on the row below.
     */
    private List<String> layout(List<String> configured) {
        if (configured == null || configured.isEmpty()) return defaultLayout();

        List<String> rows = new ArrayList<>();
        for (String row : configured) {
            if (rows.size() == MAX_ROWS) {
                C.warning("gui.yml: il layout ha più di " + MAX_ROWS + " righe — le altre sono ignorate.");
                break;
            }
            String line = row == null ? "" : row;
            if (line.length() != COLUMNS) {
                C.warning("gui.yml: la riga '" + line + "' è lunga " + line.length()
                        + " caratteri invece di " + COLUMNS + " — la adatto.");
                line = line.length() > COLUMNS
                        ? line.substring(0, COLUMNS)
                        : line + " ".repeat(COLUMNS - line.length());
            }
            rows.add(line);
        }
        return rows.isEmpty() ? defaultLayout() : rows;
    }

    private static List<String> defaultLayout() {
        return List.of("XXXXXXXXX", "XOOOOOOOX", "XOOOOOOOX", "XOOOOOOOX", "X<XXXXX>X");
    }

    private Map<String, IconConfig> icons(ConfigurationSection sec) {
        Map<String, IconConfig> map = new LinkedHashMap<>();
        if (sec == null) return map;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection iconSec = sec.getConfigurationSection(key);
            if (iconSec == null) continue;
            map.put(key.toLowerCase(Locale.ROOT), icon(iconSec, "STONE"));
        }
        return map;
    }

    private IconConfig icon(ConfigurationSection sec, String material) {
        if (sec == null) return new IconConfig(material, "", List.of(), false, "", false);
        return new IconConfig(
                sec.getString("material", material),
                sec.getString("name", ""),
                sec.getStringList("lore"),
                sec.getBoolean("glow", false),
                sec.getString("command", ""),
                sec.getBoolean("close", true));
    }

    private InterceptConfig intercept(ConfigurationSection sec) {
        if (sec == null) return new InterceptConfig(true, List.of("homes"), List.of("warps"));
        return new InterceptConfig(
                sec.getBoolean("enabled", true),
                lowercase(sec.getStringList("homes")),
                lowercase(sec.getStringList("warps")));
    }

    private static List<String> lowercase(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            out.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
