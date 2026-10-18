package dev.lorenzzz.smplibrary.modules.halloween;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where the candies come from, and what the mobs look like while they are being collected.
 *
 * <p>Split out of {@link HalloweenModule} because these handlers are all the same shape —
 * check the world, ask the module what today allows, roll a die — while the module owns the
 * state, the config and everything the player can trigger on purpose.
 *
 * <p>Every handler returns early for a world the event does not cover, so a server running the
 * season in {@code world} only pays a world-name lookup for anything happening in the nether.
 *
 * <p>Pumpkins a player put down themselves never pay. Without that rule a single pumpkin and an
 * axe were an endless candy source — place it, break it, pick it up, place it again — which is
 * the hotbar-and-click spam that filled the daily basket in a couple of minutes. Placed pumpkins
 * are remembered in their chunk's persistent data, so the rule survives restarts and unloads.
 */
public final class HalloweenListener implements Listener {

    /** What counts as a pumpkin worth breaking open. */
    private static final Set<Material> PUMPKINS =
            Set.of(Material.PUMPKIN, Material.CARVED_PUMPKIN, Material.JACK_O_LANTERN);

    private final HalloweenModule module;

    /** Chunk data entry listing the squares that hold a player-placed pumpkin. */
    private final NamespacedKey placedKey;

    HalloweenListener(HalloweenModule module, NamespacedKey placedKey) {
        this.module = module;
        this.placedKey = placedKey;
    }

    /**
     * Candy from a hostile mob a player killed.
     *
     * <p>{@code Enemy} rather than a list of types: it is the interface the hostile mobs share,
     * so phantoms, slimes, hoglins and whatever a future version adds are all covered without
     * this file having to know about them. A villager, a cow or a mob that died of fall damage
     * with nobody to blame is not a kill and pays nothing.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim instanceof Enemy) || !module.isLive(victim.getWorld())) return;

        Player killer = victim.getKiller();
        if (killer == null) return;

        Location where = victim.getLocation();
        DailyEvent today = module.today();
        if (today != null && today.has(DailyEvent.Trait.SOUL_TRAIL)) module.soulTrail(where);

        // Before the roll, so a player who has filled their daily basket is not handed an item
        // the counter will refuse.
        if (module.capReached(killer)) return;
        if (ThreadLocalRandom.current().nextDouble() >= module.mobCandyChance()) return;
        if (module.creditCandy(killer, 1, where) <= 0) return;

        // Into the drop list rather than dropped by hand: looting, keepInventory and anything
        // else that reads the drops of this death then sees the candy too.
        ItemStack candy = module.candyItem();
        if (candy != null) event.getDrops().add(candy);
    }

    /** Candy from a broken pumpkin — the whole point of a lucky-pumpkins day. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!PUMPKINS.contains(block.getType())) return;
        // Ahead of every other check: the mark leaves with the block whatever this break pays,
        // or a creative-mode or off-season break would leave it behind on an empty square.
        if (forgetPlaced(block)) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (!module.isLive(block.getWorld())) return;
        if (module.capReached(player)) return;
        if (ThreadLocalRandom.current().nextDouble() >= module.pumpkinCandyChance()) return;

        Location where = block.getLocation().add(0.5, 0.5, 0.5);
        // Credited first and dropped after: the cap can trim a lucky handful down to what is
        // left of the basket, and the item on the ground has to match what the counter took.
        int credited = module.creditCandy(player, module.pumpkinCandies(), where);
        if (credited <= 0) return;

        ItemStack candy = module.candyItem();
        if (candy != null && where.getWorld() != null) {
            candy.setAmount(credited);
            where.getWorld().dropItemNaturally(where, candy);
        }
    }

    /** A pumpkin a player put down — from either hand — is theirs, not a find. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!event.canBuild()) return;
        Block block = event.getBlockPlaced();
        if (PUMPKINS.contains(block.getType())) setPlaced(block, true);
    }

    /**
     * A stem growing a pumpkin is the one way a fresh pumpkin appears outside world generation,
     * so whatever mark was left on that square (a pumpkin blown up or turned into a golem) goes.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (PUMPKINS.contains(event.getNewState().getType())) setPlaced(event.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        carryMarks(event, event.getBlocks(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        carryMarks(event, event.getBlocks(), false);
    }

    /** The pumpkin heads and the glow, on the days that have them. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;
        module.decorate(event.getEntity());
    }

    /** Eating one of ours is a snack with a small buff; a plain cookie stays a plain cookie. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!module.isCandy(event.getItem())) return;
        module.eatCandy(event.getPlayer());
    }

    // ------------------------------------------------------- placed pumpkins

    /**
     * Moves the marks along with the pumpkins a piston shifts: a carved pumpkin or a
     * jack o'lantern pushed one block over would otherwise land on an unmarked square and pay.
     * (A plain pumpkin is broken by a piston, not moved, and is not in the list at all.)
     *
     * <p>The motion is worked out from the piston's own facing — forward on an extend, back
     * towards the piston on a retract — so it does not depend on what the event calls its
     * direction.
     */
    private void carryMarks(BlockPistonEvent event, List<Block> moved, boolean extending) {
        List<Block> pumpkins = new ArrayList<>();
        for (Block block : moved) {
            if (PUMPKINS.contains(block.getType())) pumpkins.add(block);
        }
        if (pumpkins.isEmpty()) return;
        if (!(event.getBlock().getBlockData() instanceof Directional piston)) return;
        BlockFace motion = extending ? piston.getFacing() : piston.getFacing().getOppositeFace();

        // Two passes: in a row of pumpkins each one moves into the square the next is leaving.
        boolean[] placed = new boolean[pumpkins.size()];
        for (int i = 0; i < placed.length; i++) placed[i] = forgetPlaced(pumpkins.get(i));
        for (int i = 0; i < placed.length; i++) setPlaced(pumpkins.get(i).getRelative(motion), placed[i]);
    }

    /** Clears this square's mark and says whether it had one. */
    private boolean forgetPlaced(Block block) {
        return setPlaced(block, false);
    }

    /**
     * Marks or clears one square and returns whether it was marked before.
     *
     * <p>Every caller runs alongside a block change in the same chunk, so the chunk is saved
     * with the new list without having to be flagged here.
     */
    private boolean setPlaced(Block block, boolean placed) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        long[] squares = data.get(placedKey, PersistentDataType.LONG_ARRAY);
        long square = square(block);
        int at = indexOf(squares, square);
        boolean was = at >= 0;
        if (placed == was) return was;

        if (placed) {
            long[] grown = squares == null ? new long[1] : Arrays.copyOf(squares, squares.length + 1);
            grown[grown.length - 1] = square;
            data.set(placedKey, PersistentDataType.LONG_ARRAY, grown);
        } else if (squares.length == 1) {
            data.remove(placedKey);
        } else {
            long[] shrunk = new long[squares.length - 1];
            System.arraycopy(squares, 0, shrunk, 0, at);
            System.arraycopy(squares, at + 1, shrunk, at, squares.length - at - 1);
            data.set(placedKey, PersistentDataType.LONG_ARRAY, shrunk);
        }
        return was;
    }

    /** A block's position inside its chunk as one number: y in the high bits, then x, then z. */
    private static long square(Block block) {
        return ((long) block.getY() << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private static int indexOf(long[] squares, long square) {
        if (squares == null) return -1;
        for (int i = 0; i < squares.length; i++) {
            if (squares[i] == square) return i;
        }
        return -1;
    }
}
