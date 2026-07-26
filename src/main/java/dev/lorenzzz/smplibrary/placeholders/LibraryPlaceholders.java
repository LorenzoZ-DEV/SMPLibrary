package dev.lorenzzz.smplibrary.placeholders;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.keyall.KeyAllModule;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    @Override public @NotNull String getIdentifier() { return "smplibrary"; }
    @Override public @NotNull String getAuthor() { return "lorenzzz"; }
    @Override public @NotNull String getVersion() { return "1.0-SNAPSHOT"; }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        Smp_library plugin = Smp_library.getInstance();
        if (plugin == null) return "";
        String p = params.toLowerCase();

        if (p.startsWith("keyall_")) {
            ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("MODULES.keyall");
            if (cfg == null) return "";
            int amount = cfg.getInt("amount", 1);
            String crate = cfg.getString("crate-name", "common_crate");

            Module m = plugin.getManagers().modules() == null ? null : plugin.getManagers().modules().module("keyall");
            long next = (m instanceof KeyAllModule k) ? k.nextRunEpoch() : 0L;

            return switch (p) {
                case "keyall_formatted" -> formatCountdown(next);
                case "keyall_seconds"   -> String.valueOf(Math.max(0, (next - System.currentTimeMillis()) / 1000L));
                case "keyall_crate"     -> crate;
                case "keyall_amount"    -> String.valueOf(amount);
                default -> null;
            };
        }
        if (p.equals("modules_active")) {
            int n = 0;
            var mods = plugin.getConfig().getConfigurationSection("MODULES");
            if (mods != null) for (String id : mods.getKeys(false)) if (plugin.getManagers().modules().isEnabled(id)) n++;
            return String.valueOf(n);
        }
        return null;
    }

    /** "2d 3h 4m 5s" trimmed to the first non-zero pair, "now" once expired. */
    private static String formatCountdown(long nextRunEpoch) {
        long remaining = (nextRunEpoch - System.currentTimeMillis()) / 1000L;
        if (remaining <= 0) return "now";

        long days = remaining / 86_400L;   remaining %= 86_400L;
        long hrs  = remaining / 3_600L;    remaining %= 3_600L;
        long mins = remaining / 60L;       remaining %= 60L;
        long secs = remaining;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d');
        if (hrs  > 0 || sb.length() > 0) sb.append(sb.length() > 0 ? " " : "").append(hrs).append('h');
        if (mins > 0 || sb.length() > 0) sb.append(sb.length() > 0 ? " " : "").append(mins).append('m');
        sb.append(sb.length() > 0 ? " " : "").append(secs).append('s');
        return sb.toString();
    }
}