package dev.lorenzzz.smplibrary.modules.settings;

import java.util.List;
import java.util.Map;

/**
 * The whole settings GUI as read from {@code inventories/settings.yml}.
 *
 * @param enabledLabel  what an ON toggle shows, substituted for {@code %state%}
 * @param disabledLabel what an OFF toggle shows
 */
public record SettingsConfig(
        int rows,
        String name,
        String enabledLabel,
        String disabledLabel,
        FillItemConfig fillItem,
        Map<String, SettingsItemConfig> items
) {}

record FillItemConfig(
        String material,
        String name
) {}

/**
 * One entry of the settings GUI.
 *
 * @param toggle        true for an ON/OFF switch (the state is stored per player), false for a
 *                      plain action button like "open the auction house options"
 * @param defaultState  what the switch reads as for a player who never touched it
 * @param requiresModule module id that has to be running for the entry to show at all, or
 *                      {@code null} when the entry is always there
 */
record SettingsItemConfig(
        int slot,
        ItemConfig item,
        List<String> commands,
        boolean close,
        boolean toggle,
        boolean defaultState,
        String requiresModule
) {}

record ItemConfig(
        String material,
        String name,
        List<String> lore,
        boolean glow
) {}
