package dev.lorenzzz.smplibrary.modules;

import dev.lorenzzz.smplibrary.C;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Static meta helpers shared by the tool modules — resolves enchantments, applies
 * attribute modifiers (1.21 NamespacedKey ctor) and builds colorized lore. Keeps the
 * non-trivial 1.21 API bits in one place so the modules avoid dup and surface errors here.
 */
public final class ToolMeta {

    private ToolMeta() {}

    /** Apply each {@code enchantments: NAME: level} entry. Unknown names are logged + skipped. */
    public static void applyEnchantments(ItemMeta meta, ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            int level = section.getInt(key, 0);
            if (level <= 0) continue;
            Enchantment enchantment = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
            if (enchantment == null) {
                C.warning("Unknown enchantment, skipping: " + key);
                continue;
            }
            meta.addEnchant(enchantment, level, true);
        }
    }

    /**
     * Apply each {@code attributes: attack_damage: value} entry as a MAIN_HAND ADD_NUMBER
     * modifier. 1.21 AttributeModifier needs a stable NamespacedKey; we key per attribute so
     * a single meta never holds two equal-modifier keys. Unknown names are skipped.
     */
    public static void applyAttributes(ItemMeta meta, ConfigurationSection section, JavaPlugin plugin, String tag) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String name = key.toLowerCase(Locale.ROOT);
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(name));
            if (attribute == null) {
                C.warning("Unknown attribute, skipping: " + key);
                continue;
            }
            meta.addAttributeModifier(attribute, new AttributeModifier(
                    new NamespacedKey(plugin, tag + "_" + name),
                    section.getDouble(key, 0),
                    AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.MAINHAND));
        }
    }

    /** Marks the item unbreakable and hides the given detail flags — every custom tool does this. */
    public static void seal(ItemMeta meta, ItemFlag... flags) {
        meta.setUnbreakable(true);
        meta.addItemFlags(flags);
    }

    /** True when the item carries the module's PDC marker key. */
    public static boolean hasTag(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** True when the meta's PDC marker key holds exactly {@code expected}. */
    public static boolean hasTag(ItemMeta meta, NamespacedKey key, String expected) {
        return meta != null && expected.equals(meta.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    /** Template lines with a placeholder map -> colorized Component list for {@link ItemMeta#lore}. */
    public static List<Component> lore(List<String> template, Map<String, String> placeholders) {
        List<Component> out = new ArrayList<>(template.size());
        for (String line : template) {
            String resolved = line;
            if (placeholders != null) {
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    resolved = resolved.replace(entry.getKey(), entry.getValue());
                }
            }
            out.add(C.component(resolved));
        }
        return out;
    }
}
