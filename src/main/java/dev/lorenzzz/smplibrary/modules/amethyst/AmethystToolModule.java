package dev.lorenzzz.smplibrary.modules.amethyst;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Tools-list with custom durability tracked as "uses". Per {@code MODULES.amethyst-tool}: a
 * {@code durability-uses} budget shared across every tool in {@code tools-list}; the held tool
 * decrements once per block broken (pickaxe/axe/shovel/hoe) or per mob hit (sword) and breaks at
 * zero. Items are unbreakable + flags hidden so vanilla durability never drifts from the
 * {@code %uses%} lore. Admin give via {@code /smplibrary amethyst give <tool> [player]}.
 */
public final class AmethystToolModule implements Module, Listener {

    private final JavaPlugin plugin;
    private MessagesManager messages;

    private final NamespacedKey K_USES;
    private final NamespacedKey K_MAX;
    private final NamespacedKey K_TOOL;

    public AmethystToolModule(JavaPlugin plugin) {
        this.plugin = plugin;
        K_USES = new NamespacedKey(plugin, "amethyst_uses");
        K_MAX  = new NamespacedKey(plugin, "amethyst_max");
        K_TOOL = new NamespacedKey(plugin, "amethyst_tool");
    }

    @Override public String id() { return "amethyst-tool"; }

    @Override public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("MODULES.amethyst-tool");
    }

    /** Build a fresh amethyst tool, or {@code null} if the tool key / material is unknown. */
    public ItemStack buildAmethyst(String toolKey) {
        ConfigurationSection root = cfg();
        if (root == null) return null;
        ConfigurationSection tool = root.getConfigurationSection("tools-list." + toolKey);
        if (tool == null) return null;

        Material material = Material.matchMaterial(tool.getString("material", ""));
        if (material == null || material.isAir()) { C.warning("amethyst-tool: bad material for " + toolKey); return null; }
        int max = Math.max(1, root.getInt("durability-uses", 500));

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(C.component(tool.getString("name", "")));
        meta.lore(ToolMeta.lore(tool.getStringList("lore"), Map.of("%uses%", String.valueOf(max))));

        ToolMeta.applyEnchantments(meta, tool.getConfigurationSection("enchantments"));
        ToolMeta.applyAttributes(meta, tool.getConfigurationSection("attributes"), plugin, "amethyst");
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(K_USES, PersistentDataType.INTEGER, max);
        pdc.set(K_MAX,  PersistentDataType.INTEGER, max);
        pdc.set(K_TOOL, PersistentDataType.STRING,  toolKey);

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        ItemStack item = p.getInventory().getItemInMainHand();
        if (!hasTool(item)) return;
        if (item.getType().name().endsWith("_SWORD")) return;   // sword counts on attack, not break
        useOnce(p, item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player p)) return;
        ItemStack item = p.getInventory().getItemInMainHand();
        if (!hasTool(item)) return;
        if (!item.getType().name().endsWith("_SWORD")) return;  // only the sword decrements on hit
        useOnce(p, item);
    }

    private boolean hasTool(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(K_TOOL, PersistentDataType.STRING);
    }

    private String toolKeyOf(ItemMeta meta) {
        return meta.getPersistentDataContainer().get(K_TOOL, PersistentDataType.STRING);
    }

    private void useOnce(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Integer uses = meta.getPersistentDataContainer().get(K_USES, PersistentDataType.INTEGER);
        if (uses == null) return;
        int rem = uses - 1;

        if (rem <= 0) {
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1f);
            if (messages != null) messages.sendBoth(player, "amethyst-tool.broken", Map.of());
            return;
        }
        meta.getPersistentDataContainer().set(K_USES, PersistentDataType.INTEGER, rem);

        // Rewrite the %uses% line from the live config template so a reload retunes the format.
        ConfigurationSection root = cfg();
        if (root != null) {
            ConfigurationSection tool = root.getConfigurationSection("tools-list." + toolKeyOf(meta));
            if (tool != null) meta.lore(ToolMeta.lore(tool.getStringList("lore"), Map.of("%uses%", String.valueOf(rem))));
        }
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
    }

    @Override public Module storage(StorageManager storage) { return this; }
    @Override public Module commands(CommandManager commands) { return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }

    @Override public void onDisable() {}
}
