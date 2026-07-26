package dev.lorenzzz.smplibrary.modules.trivella;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Trivella — an unbreakable pickaxe that on break clears the 3x3x3 cube (configurable
 * {@code radius}) around the block. No usage budget (unlike {@code amethyst-tool}). The
 * 26 neighbor blocks are removed via {@link Block#breakNaturally(ItemStack)} so fortune /
 * silk on the held tool still apply; air, liquids, bedrock and tile-state blocks (chests,
 * spawners, …) are skipped. A thread-local guard stops the neighbor breaks from re-entering
 * this listener. Admin give via {@code /smplibrary trivella give [player]}.
 */
public final class TrivellaModule implements Module, Listener {

    private static final String TOOL_ID = "trivella";

    // ponytail: recursion guard — breakNaturally may fire events on some Paper builds; the
    // neighbors we clear must not re-enter the handler and fan out further.
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    private final JavaPlugin plugin;
    private MessagesManager messages;

    private final NamespacedKey K_TOOL;

    public TrivellaModule(JavaPlugin plugin) {
        this.plugin = plugin;
        K_TOOL = new NamespacedKey(plugin, "trivella_tool");
    }

    @Override public String id() { return "trivella"; }

    @Override public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("MODULES.trivella");
    }

    /** Build a fresh Trivella, or {@code null} if the material is unset/unknown. */
    public ItemStack buildTrivella() {
        ConfigurationSection root = cfg();
        if (root == null) return null;
        Material material = Material.matchMaterial(root.getString("material", "NETHERITE_PICKAXE"));
        if (material == null || material.isAir()) { C.warning("trivella: bad material."); return null; }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(C.component(root.getString("name", "&d&lTrivella")));
        meta.lore(ToolMeta.lore(root.getStringList("lore"), Map.of()));
        ToolMeta.applyEnchantments(meta, root.getConfigurationSection("enchantments"));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(K_TOOL, PersistentDataType.STRING, TOOL_ID);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isTrivella(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(K_TOOL, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (INSIDE.get()) return;                 // neighbor break we triggered — no re-entry
        Player p = event.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isTrivella(tool)) return;

        ConfigurationSection root = cfg();
        int radius = root == null ? 1 : Math.min(Math.max(root.getInt("radius", 1), 0), 3); // tuned & capped

        Block center = event.getBlock();
        INSIDE.set(true);
        try {
            for (int dx = -radius; dx <= radius; dx++)
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;     // center already handled by the event
                        Block b = center.getRelative(dx, dy, dz);
                        if (b.getType().isAir() || b.isLiquid() || b.getType() == Material.BEDROCK) continue;
                        if (b.getState() instanceof TileState) continue; // don't nuke chests/spawners/signs
                        b.breakNaturally(tool);                           // respects fortune/silk on tool
                    }
        } finally {
            INSIDE.remove();
        }
    }

    @Override public Module storage(StorageManager storage) { return this; }
    @Override public Module commands(CommandManager commands) { return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }

    @Override public void onDisable() {}
}