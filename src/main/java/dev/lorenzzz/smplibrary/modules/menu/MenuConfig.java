package dev.lorenzzz.smplibrary.modules.menu;

import java.util.List;
import java.util.Map;

public record MenuConfig(
        String name,
        String title,
        int size,
        boolean enabled,
        MenuItemConfig fillItem,
        List<MenuItemConfig> items
) {}

record MenuItemConfig(
        String id,
        int slot,
        Map<String, Object> item,
        List<Map<String, String>> actions,
        List<String> commands,
        List<String> consoleCommands,
        Boolean close,
        ClickRequirement clickRequirement
) {}

record ClickRequirement(
        Map<String, Object> purchase
) {}

record PurchaseConfig(
        List<String> clicks,
        List<RequirementConfig> requirements,
        List<ActionConfig> success,
        List<ActionConfig> deny
) {}

record RequirementConfig(
        String type,
        String placeholder,
        String value,
        String action
) {}

record ActionConfig(
        String type,
        List<String> commands,
        List<String> messages,
        String inventory
) {}
