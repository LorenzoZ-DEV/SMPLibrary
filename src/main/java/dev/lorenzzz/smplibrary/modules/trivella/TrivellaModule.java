package dev.lorenzzz.smplibrary.modules.trivella;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import dev.lorenzzz.smplibrary.text.Text;
import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

import java.util.Locale;
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
 * <p>The cube is anchored {@code radius} blocks along the direction the player digs
 * ({@code dig-forward}), not on the broken block. Centred on the block, the nearest layer is the
 * air the player is standing or looking through, so {@code isProtected} drops it and a 3x3x3 takes
 * out 3x3x2 of actual rock. Anchored forward all three layers land in material, which is what a
 * drill is expected to do; {@code dig-forward: false} restores the centred cube.
 *
 * <p>Sneaking suppresses the area break entirely ({@code disable-on-sneak}), which is the only way
 * to dig a 1x1 tunnel while holding the tool.
 *
 * <h2>What the hot path costs</h2>
 *
 * <p>27 blocks per swing is nothing; 27 <em>snapshots</em> per swing is not. The filter used to
 * call {@code block.getState()} on every candidate, which copies the block entity into a fresh
 * BlockState object — for a stone wall that is 26 objects allocated and thrown away per swing,
 * per player, and it was the last check in the chain so it ran even on plain stone.
 *
 * <p>The cube itself is unchanged. What changed is the work done per block:
 *
 * <ul>
 *   <li>the 27 relative offsets are computed once per radius at class load, not rebuilt by three
 *       nested loops on every break;
 *   <li>the first question asked is {@link Block#getType()}, answered against a {@code boolean[]}
 *       indexed by {@link Material#ordinal()} — air, liquids and unbreakable blocks never get
 *       further than an array read;
 *   <li>whether a material carries a block entity is asked once per material and cached in
 *       {@link #TILE_STATE}, so {@code getState(false)} runs a few dozen times over the life of
 *       the server instead of 26 times per swing. {@code false} skips the snapshot copy;
 *   <li>the config section is read on enable, not on every {@link BlockBreakEvent} the server
 *       fires;
 *   <li>nothing is allocated in the loop — no lists, no Locations, no intermediate collections.
 * </ul>
 *
 * <h2>Why the per-block event stays</h2>
 *
 * <p>{@code breakNaturally} fires no event of its own, so without a {@link BlockBreakEvent} per
 * neighbor the tool digs straight through claims, WorldGuard regions and everything else that
 * decides per block — and CoreProtect logs nothing. That is a correctness requirement, not an
 * optimisation target, so {@link Mode#COMPATIBILITY} keeps it and the savings above come from the
 * work around it. {@link Mode#FAST} drops the per-block event for servers that genuinely have no
 * protection plugin; it is not the default, and the warning on enable says why.
 */
public final class TrivellaModule extends AbstractModule implements Listener {

    private static final String TOOL_ID = "trivella";
    private static final int MAX_RADIUS = 3;

    /** Pitch past which the player is digging straight up/down instead of along a compass face. */
    private static final float VERTICAL_PITCH = 45f;

    // ponytail: recursion guard — breakNaturally may fire events on some Paper builds; the
    // neighbors we clear must not re-enter the handler and fan out further.
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    /**
     * How much of the server the area break is willing to pay for.
     *
     * <p>{@link #COMPATIBILITY} puts every neighbor through its own {@link BlockBreakEvent}, so
     * protections, CoreProtect, quests and anything else listening see each block exactly as if
     * the player had mined it by hand. {@link #FAST} breaks the neighbors directly: one swing
     * then costs one event instead of 27, at the price of every per-block veto on the server.
     */
    private enum Mode { COMPATIBILITY, FAST }

    // ---------------------------------------------------- precomputed lookups

    /**
     * The relative offsets of each cube, flattened as {@code x, y, z} triples and indexed by
     * radius. Same 27 positions the nested loops produced, resolved once at class load.
     */
    private static final int[][] OFFSETS = new int[MAX_RADIUS + 1][];

    /**
     * Per-{@link Material} verdict of the cheap filter: air, liquids, non-blocks and anything
     * unbreakable. One array read replaces {@code isAir() || isLiquid() || == BEDROCK}.
     */
    private static final boolean[] SKIPPED = new boolean[Material.values().length];

    /** Tri-state cache of "does this material carry a block entity": 0 unknown, 1 yes, 2 no. */
    private static final byte[] TILE_STATE = new byte[Material.values().length];

    private static final byte UNKNOWN = 0;
    private static final byte IS_TILE = 1;
    private static final byte NOT_TILE = 2;

    static {
        for (int radius = 0; radius <= MAX_RADIUS; radius++) {
            int span = radius * 2 + 1;
            int[] offsets = new int[span * span * span * 3];
            int i = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        offsets[i++] = dx;
                        offsets[i++] = dy;
                        offsets[i++] = dz;
                    }
                }
            }
            OFFSETS[radius] = offsets;
        }

        for (Material material : Material.values()) {
            SKIPPED[material.ordinal()] = isAlwaysSkipped(material);
        }
    }

    /**
     * The part of the filter that depends only on the material, so it can be answered before the
     * server is even running. Negative hardness is how the game marks the blocks no tool may
     * break — bedrock, barriers, command blocks, end portal frames — which is a rule rather than
     * the hand-written list it used to be.
     */
    private static boolean isAlwaysSkipped(Material material) {
        if (material.isLegacy() || !material.isBlock()) return true;
        if (material.isAir()) return true;
        if (material == Material.WATER || material == Material.LAVA
                || material == Material.BUBBLE_COLUMN) {
            return true;
        }
        try {
            return material.getHardness() < 0;
        } catch (Exception e) {
            // A material this build refuses to describe is one we should not be breaking.
            return true;
        }
    }

    // ------------------------------------------------------------ cached config

    private final NamespacedKey toolKey;

    // Read once on enable. A module is stopped and restarted by /smpl reload, so these are never
    // staler than the config itself — and the alternative was three config lookups per break.
    private int radius = 1;
    private boolean digForward = true;
    private boolean disableOnSneak = true;
    private Mode mode = Mode.COMPATIBILITY;

    public TrivellaModule(Smp_library plugin) {
        super(plugin);
        this.toolKey = new NamespacedKey(plugin, "trivella_tool");
    }

    @Override public String id() { return TOOL_ID; }

    @Override
    public void onEnable() {
        readSettings();
        listen(this);
    }

    private void readSettings() {
        ConfigurationSection cfg = config();
        radius = cfg == null ? 1 : Math.clamp(cfg.getInt("radius", 1), 0, MAX_RADIUS);
        digForward = cfg == null || cfg.getBoolean("dig-forward", true);
        disableOnSneak = cfg == null || cfg.getBoolean("disable-on-sneak", true);
        mode = parseMode(cfg == null ? null : cfg.getString("mode"));

        if (mode == Mode.FAST) {
            Log.warning("trivella: mode FAST — the neighbor blocks are broken without a "
                    + "BlockBreakEvent. Claims, WorldGuard regions and CoreProtect will not see "
                    + "them. Use COMPATIBILITY unless this server has no protection plugin.");
        }
    }

    private static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) return Mode.COMPATIBILITY;
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Log.warning("trivella: unknown mode '" + raw + "' — using COMPATIBILITY.");
            return Mode.COMPATIBILITY;
        }
    }

    /** Build a fresh Trivella, or {@code null} if the material is unset/unknown. */
    public ItemStack buildTrivella() {
        ConfigurationSection cfg = config();
        if (cfg == null) return null;

        Material material = Material.matchMaterial(cfg.getString("material", "NETHERITE_PICKAXE"));
        if (material == null || material.isAir()) {
            Log.warning("trivella: bad material.");
            return null;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.legacy(cfg.getString("name", "&d&lTrivella")));
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
        if (radius == 0) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!ToolMeta.hasTag(tool, toolKey)) return;

        // Sneak = dig one block, like a normal pickaxe. Without it there is no way to cut a 1x1
        // tunnel, place a torch niche or mine next to a build while the tool is in hand.
        if (disableOnSneak && player.isSneaking()) return;

        breakCube(player, event.getBlock(), tool);
    }

    /**
     * The whole area break, as one pass over the precomputed offsets.
     *
     * <p>Filter and break are interleaved rather than split into two phases: each block's verdict
     * depends on nothing but itself, so collecting the survivors first would buy nothing and cost
     * a list per swing.
     */
    private void breakCube(Player player, Block origin, ItemStack tool) {
        // Pushed forward by exactly the radius, so the broken block sits on the near face of the
        // cube and every layer behind it is still solid.
        Block anchor = digForward ? shift(origin, digFace(player), radius) : origin;

        World world = origin.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();

        int anchorX = anchor.getX();
        int anchorY = anchor.getY();
        int anchorZ = anchor.getZ();

        int[] offsets = OFFSETS[radius];
        boolean fireEvents = mode == Mode.COMPATIBILITY;

        INSIDE.set(true);
        try {
            for (int i = 0; i < offsets.length; i += 3) {
                // The forward anchor can push the cube under bedrock or past the build limit;
                // those columns are not ours. Checked on the int before a Block is resolved.
                int y = anchorY + offsets[i + 1];
                if (y < minY || y >= maxY) continue;

                int x = anchorX + offsets[i];
                int z = anchorZ + offsets[i + 2];
                if (x == originX && y == originY && z == originZ) continue; // centre: the event's

                Block block = world.getBlockAt(x, y, z);
                Material type = block.getType();
                if (SKIPPED[type.ordinal()]) continue;
                if (hasBlockEntity(block, type)) continue;  // don't nuke chests/spawners/signs

                breakOne(player, block, tool, fireEvents);
            }
        } finally {
            INSIDE.remove();
        }
    }

    /**
     * Whether this material carries a block entity, asked of the server once per material.
     *
     * <p>Having a block entity is a property of the block type, not of the individual block, so
     * the answer is cached for the life of the server. {@code getState(false)} rather than
     * {@code getState()}: the snapshot copy is the expensive half and we only want the type.
     */
    private static boolean hasBlockEntity(Block block, Material type) {
        int ordinal = type.ordinal();
        byte cached = TILE_STATE[ordinal];
        if (cached != UNKNOWN) return cached == IS_TILE;

        boolean tile = block.getState(false) instanceof TileState;
        TILE_STATE[ordinal] = tile ? IS_TILE : NOT_TILE;
        return tile;
    }

    /**
     * The face the player is digging into. {@link Player#getFacing()} only ever answers with a
     * compass direction, so a steep pitch is read as up/down first — otherwise mining the floor
     * under your feet would push the cube sideways into whatever you happen to be facing.
     */
    private static BlockFace digFace(Player player) {
        float pitch = player.getLocation().getPitch();
        if (pitch >= VERTICAL_PITCH) return BlockFace.DOWN;
        if (pitch <= -VERTICAL_PITCH) return BlockFace.UP;
        return player.getFacing();
    }

    private static Block shift(Block block, BlockFace face, int distance) {
        return block.getRelative(face.getModX() * distance,
                face.getModY() * distance,
                face.getModZ() * distance);
    }

    /**
     * Breaks one neighbor as if the player had mined it: under {@link Mode#COMPATIBILITY} the
     * event goes out first so protection plugins get their veto, and a listener that only
     * suppressed the drops is honoured too.
     */
    private void breakOne(Player player, Block block, ItemStack tool, boolean fireEvent) {
        boolean dropItems = true;

        if (fireEvent) {
            BlockBreakEvent event = new BlockBreakEvent(block, player);
            if (!event.callEvent()) return;
            dropItems = event.isDropItems();
        }

        if (dropItems) {
            block.breakNaturally(tool, true, true);   // respects fortune/silk, drops the ore xp
        } else {
            block.setType(Material.AIR);
        }
    }
}
