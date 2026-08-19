package dev.lorenzzz.smplibrary.modules.amethyst;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import dev.lorenzzz.smplibrary.modules.UseBatcher;
import org.bukkit.GameMode;
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
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;

/**
 * Tools-list with custom durability tracked as "uses". Per {@code MODULES.amethyst-tool}: a
 * {@code durability-uses} budget shared across every tool in {@code tools-list}; the held tool
 * decrements once per block broken (pickaxe/axe/shovel/hoe) or per mob hit (sword) and breaks at
 * zero. Items are unbreakable + flags hidden so vanilla durability never drifts from the
 * {@code %uses%} lore. Admin give via {@code /smplibrary amethyst give <tool> [player]}.
 */
public final class AmethystToolModule extends AbstractModule implements Listener {

    private static final String SWORD_SUFFIX = "_SWORD";

    private final NamespacedKey usesKey;
    private final NamespacedKey maxKey;
    private final NamespacedKey toolKey;

    // Breaks are batched before they reach the item: rewriting the held stack resets the client's
    // block-breaking progress, which on a fast pickaxe stalls mining outright. See UseBatcher.
    private final UseBatcher uses = new UseBatcher(1000L);

    public AmethystToolModule(Smp_library plugin) {
        super(plugin);
        this.usesKey = new NamespacedKey(plugin, "amethyst_uses");
        this.maxKey = new NamespacedKey(plugin, "amethyst_max");
        this.toolKey = new NamespacedKey(plugin, "amethyst_tool");
    }

    @Override public String id() { return "amethyst-tool"; }

    @Override
    public void onEnable() {
        listen(this);
    }

    @Override
    protected void onShutdown() {
        uses.clear();
    }

    /** Build a fresh amethyst tool, or {@code null} if the tool key / material is unknown. */
    public ItemStack buildAmethyst(String toolId) {
        ConfigurationSection tool = toolSection(toolId);
        if (tool == null) return null;

        Material material = Material.matchMaterial(tool.getString("material", ""));
        if (material == null || material.isAir()) {
            C.warning("amethyst-tool: bad material for " + toolId);
            return null;
        }
        int max = maxUses();

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(C.component(tool.getString("name", "")));
        meta.lore(ToolMeta.lore(tool.getStringList("lore"), Map.of("%uses%", String.valueOf(max))));
        ToolMeta.applyEnchantments(meta, tool.getConfigurationSection("enchantments"));
        ToolMeta.applyAttributes(meta, tool.getConfigurationSection("attributes"), plugin, "amethyst");
        ToolMeta.seal(meta, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(usesKey, PersistentDataType.INTEGER, max);
        pdc.set(maxKey, PersistentDataType.INTEGER, max);
        pdc.set(toolKey, PersistentDataType.STRING, toolId);

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!ToolMeta.hasTag(item, toolKey)) return;
        if (isSword(item)) return; // sword counts on attack, not break
        useOnce(player, item, player.getInventory().getHeldItemSlot(), false);
    }

    /** Uses booked while mining belong to that stack — write them before it leaves the hand. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(PlayerItemHeldEvent event) {
        flushBooked(event.getPlayer(), event.getPreviousSlot());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        flushBooked(event.getPlayer(), event.getPlayer().getInventory().getHeldItemSlot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!ToolMeta.hasTag(item, toolKey)) return;
        if (!isSword(item)) return; // only the sword decrements on hit
        useOnce(player, item, player.getInventory().getHeldItemSlot(), true);
    }

    private static boolean isSword(ItemStack item) {
        return item.getType().name().endsWith(SWORD_SUFFIX);
    }

    /**
     * Books one use against the tool in {@code slot}, writing it onto the item when the batcher
     * lets it through.
     *
     * @param immediate write now, taking any booked uses along (hits, slot changes)
     */
    private void useOnce(Player player, ItemStack item, int slot, boolean immediate) {
        UUID id = player.getUniqueId();
        Integer written = item.getItemMeta().getPersistentDataContainer().get(usesKey, PersistentDataType.INTEGER);
        if (written == null) return;

        int spent = immediate ? 1 + uses.drain(id) : uses.spend(id, written);
        if (spent > 0) applyUses(player, item, slot, spent);
    }

    /** Writes out uses booked earlier, if the tool that earned them is still in {@code slot}. */
    private void flushBooked(Player player, int slot) {
        UUID id = player.getUniqueId();
        if (uses.peek(id) == 0) return;

        ItemStack item = player.getInventory().getItem(slot);
        int booked = uses.drain(id);
        // Not the tool any more: the booked uses go with it rather than onto a stack that never
        // earned them. A batch is worth a second of mining, well inside the durability budget.
        if (!ToolMeta.hasTag(item, toolKey)) return;

        applyUses(player, item, slot, booked);
    }

    /** Subtracts {@code spent} uses from the tool in {@code slot}; breaks it at zero. */
    private void applyUses(Player player, ItemStack item, int slot, int spent) {
        ItemMeta meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();

        Integer written = pdc.get(usesKey, PersistentDataType.INTEGER);
        if (written == null) return;

        int remaining = written - spent;
        if (remaining <= 0) {
            uses.forget(player.getUniqueId());
            player.getInventory().setItem(slot, null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1f);
            if (messages != null) messages.sendBoth(player, "amethyst-tool.broken", Map.of());
            return;
        }

        pdc.set(usesKey, PersistentDataType.INTEGER, remaining);

        // Rewrite the %uses% line from the live config template so a reload retunes the format.
        ConfigurationSection tool = toolSection(pdc.get(toolKey, PersistentDataType.STRING));
        if (tool != null) {
            meta.lore(ToolMeta.lore(tool.getStringList("lore"), Map.of("%uses%", String.valueOf(remaining))));
        }
        item.setItemMeta(meta);
        player.getInventory().setItem(slot, item);
    }

    private int maxUses() {
        ConfigurationSection cfg = config();
        return cfg == null ? 500 : Math.max(1, cfg.getInt("durability-uses", 500));
    }

    private ConfigurationSection toolSection(String toolId) {
        ConfigurationSection cfg = config();
        return cfg == null || toolId == null ? null : cfg.getConfigurationSection("tools-list." + toolId);
    }
}
