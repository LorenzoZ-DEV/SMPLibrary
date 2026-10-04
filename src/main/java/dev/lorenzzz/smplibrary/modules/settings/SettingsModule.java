package dev.lorenzzz.smplibrary.modules.settings;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.manager.ToggleManager;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.text.Chat;
import dev.lorenzzz.smplibrary.util.Log;
import dev.lorenzzz.smplibrary.util.SoundCue;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /settings: an inventory GUI built from {@code inventories/settings.yml}.
 *
 * <p>The ON/OFF state of every switch lives in the shared {@link ToggleManager}, not in this
 * module, precisely so other features can read it: {@code tpauto} is one of them, and its
 * button here and its own {@code /tpauto} command flip the very same stored boolean.
 */
public final class SettingsModule extends AbstractModule {

    private static final String COMMAND = "settings";

    private static final SoundCue DEFAULT_CLICK = SoundCue.of("ui.button.click", 0.6f, 1.4f);

    private MenuHandler menuHandler;
    private SettingsConfigLoader configLoader;
    private SoundCue clickSound = DEFAULT_CLICK;

    public SettingsModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return COMMAND; }

    @Override
    public void onEnable() {
        this.menuHandler = managers.menuHandler();
        this.configLoader = new SettingsConfigLoader(plugin);
        configLoader.load();

        this.clickSound = config() == null
                ? DEFAULT_CLICK
                : SoundCue.from(config().getConfigurationSection("sound"), DEFAULT_CLICK);

        registerCommand();

        SettingsConfig cfg = configLoader.get();
        Log.info("Settings module enabled (GUI) — " + (cfg == null ? 0 : cfg.items().size())
                + " voci, toggle salvati su " + (toggles() == null ? "?" : toggles().backend()) + ".");
    }

    private void registerCommand() {
        PluginCommand command = plugin.getCommand(COMMAND);
        if (command == null) {
            Log.warning("Command '" + COMMAND + "' not found in plugin.yml");
            return;
        }
        command.setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof Player player) {
                open(player);
            } else {
                Chat.send(sender, "&cOnly players.");
            }
            return true;
        });
    }

    /** Opens the GUI, waiting if needed for the player's stored toggles to be read. */
    public void open(Player player) {
        SettingsConfig cfg = configLoader == null ? null : configLoader.get();
        ToggleManager toggles = toggles();
        if (cfg == null || toggles == null) {
            Chat.send(player, "&cLe impostazioni non sono disponibili in questo momento.");
            return;
        }
        toggles.whenLoaded(player.getUniqueId(), () -> {
            if (player.isOnline()) new SettingsMenu(cfg, player, menuHandler, this).open();
        });
    }

    // ------------------------------------------------------------- menu hooks

    /** Current state of one switch; the entry's own default applies until the player flips it. */
    public boolean isOn(Player player, String key, SettingsItemConfig item) {
        ToggleManager toggles = toggles();
        return toggles != null && toggles.get(player.getUniqueId(), key, item.defaultState());
    }

    /** Flips a switch, stores it, then runs whatever commands the entry carries. */
    public void toggle(Player player, String key, SettingsItemConfig item) {
        ToggleManager toggles = toggles();
        if (toggles == null) return;

        boolean enabled = !toggles.get(player.getUniqueId(), key, item.defaultState());
        toggles.set(player.getUniqueId(), key, enabled);
        clickSound.play(player);
        runCommands(player, key, item.commands());
    }

    /** False for an entry whose {@code requires-module} is not running — it is then not drawn. */
    public boolean isAvailable(SettingsItemConfig item) {
        String required = item.requiresModule();
        if (required == null) return true;
        ModuleManager modules = managers == null ? null : managers.modules();
        return modules != null && modules.isRunning(required);
    }

    /** Runs an entry's commands as the player. One that throws must not break the menu. */
    public void runCommands(Player player, String key, List<String> commands) {
        if (commands == null || commands.isEmpty()) return;
        String name = player.getName();
        for (String command : commands) {
            if (command == null || command.isBlank()) continue;
            try {
                Bukkit.dispatchCommand(player, command.replace("%player%", name));
            } catch (Exception e) {
                Log.warning("settings: '" + key + "' — comando '" + command + "' fallito: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onShutdown() {
        configLoader = null;
        menuHandler = null;
    }
}
