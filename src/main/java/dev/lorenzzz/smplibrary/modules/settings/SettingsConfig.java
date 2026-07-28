package dev.lorenzzz.smplibrary.modules.settings;

import java.util.List;
import java.util.Map;

public record SettingsConfig(
        int size,
        String name,
        FillItemConfig fillItem,
        Map<String, SettingsItemConfig> items
) {}

record FillItemConfig(
        String material,
        String name
) {}

record SettingsItemConfig(
        int slot,
        ItemConfig item,
        List<String> commands,
        boolean close
) {}

record ItemConfig(
        String material,
        String name,
        List<String> lore,
        boolean glow
) {}