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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Static meta helpers shared by the tool modules — resolves enchantments, applies
 * attribute modifiers (1.21 NamespacedKey ctor) and builds colorized lore. Keeps the
 * non-trivial 1.21 API bits in one place so both modules avoid dup and surface errors here.
 */
public final class ToolMeta {

    private ToolMeta() {}

    /** Apply each {@code enchantments: NAME: level} entry. Unknown names are logged + skipped. */
    public static void applyEnchantments(ItemMeta meta, ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            int level = section.getInt(key, 0);
            if (level <= 0) continue;
            Enchantment ench = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
            if (ench == null) { C.warning("Unknown enchantment, skipping: " + key); continue; }
            meta.addEnchant(ench, level, true);
        }
    }

    /**
     * Apply each {@code attributes: GENERIC_ATTACK_DAMAGE: value} entry as a MAIN_HAND
     * ADD_NUMBER modifier. 1.21 AttributeModifier needs a stable NamespacedKey; we key per
     * attribute so a single meta never holds two equal-modifier keys. Unknown names skipped.
     */
    public static void applyAttributes(ItemMeta meta, ConfigurationSection section, JavaPlugin plugin, String tag) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            double value = section.getDouble(key, 0);
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
            if (attr == null) { C.warning("Unknown attribute, skipping: " + key); continue; }
            AttributeModifier mod = new AttributeModifier(
                    new NamespacedKey(plugin, tag + "_" + key.toLowerCase(Locale.ROOT)),
                    value, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
            meta.addAttributeModifier(attr, mod);
        }
    }

    /** template lines with placeholder map -> colorized Component list for {@link ItemMeta#lore}. */
    public static List<Component> lore(List<String> template, Map<String, String> ph) {
        List<Component> out = new ArrayList<>(template.size());
        for (String line : template) {
            String s = line;
            if (ph != null) for (var e : ph.entrySet()) s = s.replace(e.getKey(), e.getValue());
            out.add(C.component(s));
        }
        return out;
    }
}
