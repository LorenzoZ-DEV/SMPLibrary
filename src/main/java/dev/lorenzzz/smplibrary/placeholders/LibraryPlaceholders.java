package dev.lorenzzz.smplibrary.placeholders;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.ManagerService;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.modules.keyall.KeyAllModule;
import dev.lorenzzz.smplibrary.util.Ticks;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Exposes library placeholders to PlaceholderAPI.
 * Registered by CommandManager only if PlaceholderAPI is present.
 *
 *   %smplibrary_keyall_formatted%   -> countdown to the next keyall ("1h 5m", "now" if due)
 *   %smplibrary_keyall_seconds%      -> whole seconds remaining to the next keyall
 *   %smplibrary_keyall_crate%        -> configured crate name
 *   %smplibrary_keyall_amount%       -> configured amount
 *   %smplibrary_modules_active%      -> count of enabled modules
 */
public final class LibraryPlaceholders extends PlaceholderExpansion {

    private static final String KEYALL_PREFIX = "keyall_";

    @Override public @NotNull String getIdentifier() { return "smplibrary"; }
    @Override public @NotNull String getAuthor() { return "lorenzzz"; }
    @Override public @NotNull String getVersion() { return "1.0-SNAPSHOT"; }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        ModuleManager modules = modules();
        if (modules == null) return "";

        String key = params.toLowerCase(Locale.ROOT);
        if (key.startsWith(KEYALL_PREFIX)) return keyall(modules, key);
        if (key.equals("modules_active")) return String.valueOf(countEnabled(modules));
        return null;
    }

    private String keyall(ModuleManager modules, String key) {
        KeyAllModule module = modules.module("keyall", KeyAllModule.class);
        if (module == null) return "";

        ConfigurationSection cfg = Smp_library.getInstance().getConfig()
                .getConfigurationSection("MODULES.keyall");
        if (cfg == null) return "";

        long remaining = module.nextRunEpoch() - System.currentTimeMillis();
        return switch (key) {
            case "keyall_formatted" -> Ticks.countdown(remaining);
            case "keyall_seconds" -> String.valueOf(Math.max(0, remaining / 1000L));
            case "keyall_crate" -> cfg.getString("crate-name", "common_crate");
            case "keyall_amount" -> String.valueOf(cfg.getInt("amount", 1));
            default -> null;
        };
    }

    private static int countEnabled(ModuleManager modules) {
        return (int) modules.active().stream().filter(m -> modules.isEnabled(m.id())).count();
    }

    private static ModuleManager modules() {
        Smp_library plugin = Smp_library.getInstance();
        if (plugin == null) return null;
        ManagerService managers = plugin.getManagers();
        return managers == null ? null : managers.modules();
    }
}
