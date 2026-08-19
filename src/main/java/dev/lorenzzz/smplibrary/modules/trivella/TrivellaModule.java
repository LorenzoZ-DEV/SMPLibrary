package dev.lorenzzz.smplibrary.modules.trivella;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import org.bukkit.GameMode;
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

import java.util.Map;

/**
 * Trivella — an unbreakable pickaxe that on break clears the cube of the configured
 * {@code radius} around the block. No usage budget (unlike {@code amethyst-tool}). The
 * neighbor blocks are removed via {@link Block#breakNaturally(ItemStack, boolean, boolean)} so
 * fortune / silk on the held tool still apply and ores still drop their experience; air, liquids,
 * bedrock and tile-state blocks (chests, spawners, …) are skipped. A thread-local guard stops the
 * neighbor breaks from re-entering this listener. Admin give via {@code /smplibrary trivella give
 * [player]}.
 *
 * <p>Each neighbor is put through a {@link BlockBreakEvent} of its own before it is touched, and
 * skipped when something cancels it. {@code breakNaturally} fires no event on its own, so without
 * this the tool dug straight through claims and WorldGuard regions — 26 blocks per swing that no
 * protection plugin ever saw.
 *
 * <p>Sneaking suppresses the area break entirely ({@code disable-on-sneak}), which is the only way
 * to dig a 1x1 tunnel while holding the tool.
 */
public final class TrivellaModule extends AbstractModule implements Listener {

    private static final String TOOL_ID = "trivella";
    private static final int MAX_RADIUS = 3;

    // ponytail: recursion guard — breakNaturally may fire events on some Paper builds; the
    // neighbors we clear must not re-enter the handler and fan out further.
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    private final NamespacedKey toolKey;

    public TrivellaModule(Smp_library plugin) {
        super(plugin);
        this.toolKey = new NamespacedKey(plugin, "trivella_tool");
    }

    @Override public String id() { return TOOL_ID; }

    @Override
    public void onEnable() {
        listen(this);
    }

    /** Build a fresh Trivella, or {@code null} if the material is unset/unknown. */
    public ItemStack buildTrivella() {
        ConfigurationSection cfg = config();
        if (cfg == null) return null;

        Material material = Material.matchMaterial(cfg.getString("material", "NETHERITE_PICKAXE"));
        if (material == null || material.isAir()) {
            C.warning("trivella: bad material.");
            return null;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(C.component(cfg.getString("name", "&d&lTrivella")));
        meta.lore(ToolMeta.lore(cfg.getStringList("lore"), Map.of()));
        ToolMeta.applyEnchantments(meta, cfg.getConfigurationSection("enchantments"));
        ToolMeta.seal(meta, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, TOOL_ID);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (INSIDE.get()) return;                 // neighbor break we triggered — no re-entry

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!ToolMeta.hasTag(tool, toolKey)) return;

        ConfigurationSection cfg = config();
        // Sneak = dig one block, like a normal pickaxe. Without it there is no way to cut a 1x1
        // tunnel, place a torch niche or mine next to a build while the tool is in hand.
        if (player.isSneaking() && (cfg == null || cfg.getBoolean("disable-on-sneak", true))) return;

        int radius = cfg == null ? 1 : Math.clamp(cfg.getInt("radius", 1), 0, MAX_RADIUS);
        if (radius == 0) return;

        breakCube(player, event.getBlock(), radius, tool);
    }

    private void breakCube(Player player, Block center, int radius, ItemStack tool) {
        INSIDE.set(true);
        try {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue; // centre handled by the event
                        Block block = center.getRelative(dx, dy, dz);
                        if (isProtected(block)) continue;
                        breakOne(player, block, tool);
                    }
                }
            }
        } finally {
            INSIDE.remove();
        }
    }

    /**
     * Breaks one neighbor as if the player had mined it: the event goes out first so protection
     * plugins get their veto, and a listener that only suppressed the drops is honoured too.
     */
    private void breakOne(Player player, Block block, ItemStack tool) {
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        if (!event.callEvent()) return;

        if (event.isDropItems()) {
            block.breakNaturally(tool, true, true);   // respects fortune/silk, drops the ore xp
        } else {
            block.setType(Material.AIR);
        }
    }

    private static boolean isProtected(Block block) {
        return block.getType().isAir()
                || block.isLiquid()
                || block.getType() == Material.BEDROCK
                || block.getState() instanceof TileState; // don't nuke chests/spawners/signs
    }
}
