package dev.lorenzzz.smplibrary.modules.antipearl;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.web.WebStats;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// grazie only nel cihloing fosaifsifoasifsoadfgiasd9gisdaoigodasigo
/**
 * Stops ender pearls from landing a player inside a block.
 *
 * <p>Two moments, because a pearl can put you in a wall two different ways. The teleport itself
 * is checked in {@link #onPearlTeleport}: if the destination hitbox intersects something solid,
 * the landing spot is nudged to a free one, and when there is no free one the teleport is
 * refused outright. The second moment is the tick after: a pearl that landed you flush against
 * a block can still leave you clipped inside it, so {@link #onEntityInsideBlock} pushes you back
 * out — but only within {@code time-window-ms} of a pearl, so the handler ignores the ordinary
 * business of standing in water, ladders or crops.
 *
 * <p>{@code EntityInsideBlockEvent} fires for every entity in every non-full block on every
 * tick, so this class is careful about what it does before its early-outs: the settings live in
 * fields rather than behind {@code getConfig()} calls, and the first thing the handler checks is
 * whether anyone has thrown a pearl at all.
 *
 * <p>Every intervention is kept in a small ring for the dashboard's Anti-glitch tab.
 */
public final class AntiPearlGlitchModule extends AbstractModule implements Listener {

    private static final int MAX_HISTORY = 40;

    /** Half-width of the tested column, well inside the player's own 0.3. */
    private static final double CORE_HALF_WIDTH = 0.1;
    private static final double PLAYER_HEIGHT = 1.8;
    /** Kept off the floor and the ceiling: standing on a slab is touching it, not being in it. */
    private static final double SKIN = 0.02;

    private static final BlockFace[] COMPASS =
            { BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST };

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault());

    /** What was done, for the dashboard. */
    public record Intervention(long at, String player, String world, int x, int y, int z, String action) {

        String json() {
            return "{\"at\":" + WebStats.jsonStr(CLOCK.format(Instant.ofEpochMilli(at)))
                    + ",\"epoch\":" + at
                    + ",\"player\":" + WebStats.jsonStr(player)
                    + ",\"world\":" + WebStats.jsonStr(world)
                    + ",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                    + ",\"action\":" + WebStats.jsonStr(action) + "}";
        }
    }

    private static final String MOVED = "spostato";
    private static final String CANCELLED = "annullato";
    private static final String PUSHED = "estratto dal blocco";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Long> recentPearls = new ConcurrentHashMap<>();
    private final Deque<Intervention> history = new ConcurrentLinkedDeque<>();
    private final AtomicInteger moved = new AtomicInteger();
    private final AtomicInteger cancelled = new AtomicInteger();
    private final AtomicInteger pushed = new AtomicInteger();
    private final AtomicLong version = new AtomicLong();

    // --- config cache, rebuilt on every enable ---
    private boolean adminBypass = true;
    private long timeWindowMillis = 1000L;
    private int searchDistance = 3;
    private boolean postLandingCheck = true;
    private String staffMessageRaw = "";
    private Component staffMessage;

    public AntiPearlGlitchModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "antipearlglitch"; }

    @Override
    public void onEnable() {
        loadConfigCache();
        listen(this);
        // The throw times are only consumed by a block collision that may never come; without
        // this sweep an entry sits there until the same player pearls again.
        repeat(this::pruneStale, 1200L, 1200L);

        C.info("Anti-pearl: finestra " + timeWindowMillis + "ms, ricerca " + searchDistance
                + " blocchi, bypass admin " + adminBypass);
    }

    private void loadConfigCache() {
        ConfigurationSection cfg = resolveConfig();
        if (cfg == null) return;

        adminBypass = cfg.getBoolean("admin-bypass", true);
        timeWindowMillis = Math.max(50L, cfg.getLong("time-window-ms", 1000L));
        searchDistance = Math.clamp(cfg.getInt("search-distance", 3), 1, 8);
        postLandingCheck = cfg.getBoolean("post-landing-check", true);
        staffMessageRaw = cfg.getString("staff-message", "");
        // Parsed once here rather than per notification: the template only changes on reload.
        staffMessage = staffMessageRaw.isBlank() ? null : miniMessage.deserialize(staffMessageRaw);
    }

    /**
     * {@code MODULES.antipearlglitch}, falling back to a root {@code anti-pearl-glitch:} block.
     * The standalone version of this feature read its settings from there, and a server that
     * already has them tuned should not silently go back to the defaults on upgrade.
     */
    private ConfigurationSection resolveConfig() {
        ConfigurationSection cfg = config();
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("anti-pearl-glitch");
        if (legacy == null) return cfg;
        if (cfg == null) return legacy;

        for (String key : legacy.getKeys(false)) {
            if (!cfg.contains(key)) cfg.set(key, legacy.get(key));
        }
        C.info("Anti-pearl: letti i valori dal vecchio blocco 'anti-pearl-glitch' alla radice. "
                + "Spostali sotto MODULES.antipearlglitch quando puoi.");
        return cfg;
    }

    @Override
    protected void onShutdown() {
        recentPearls.clear();
        history.clear();
        moved.set(0);
        cancelled.set(0);
        pushed.set(0);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;

        Player player = event.getPlayer();
        if (bypassed(player)) return;

        recentPearls.put(player.getUniqueId(), System.currentTimeMillis());
        // Scheduled for every pearl, including the ones this handler waves through: the whole
        // point of the landing check is to catch the routes the destination test cannot see.
        scheduleLandingCheck(player);

        Location to = event.getTo();
        if (!isUnsafe(to)) return;

        Location safe = safeNear(to);
        if (safe != null) {
            event.setTo(safe);
            record(player, to, MOVED);
            moved.incrementAndGet();
        } else {
            // Nowhere clear to put them: refusing the pearl beats dropping them into a wall.
            event.setCancelled(true);
            record(player, to, CANCELLED);
            cancelled.incrementAndGet();
        }
        claim(player);   // handled — the landing checks must not move them a second time
        notifyStaff(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityInsideBlock(EntityInsideBlockEvent event) {
        // Cheapest possible first check: on a server where nobody is pearling this handler does
        // one map-emptiness test per entity per tick and returns.
        if (recentPearls.isEmpty()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        UUID playerId = player.getUniqueId();
        Long thrownAt = recentPearls.get(playerId);
        if (thrownAt == null) return;

        if (System.currentTimeMillis() - thrownAt > timeWindowMillis) {
            recentPearls.remove(playerId);
            return;
        }
        if (bypassed(player)) {
            recentPearls.remove(playerId);
            return;
        }

        Location safe = pushOut(player.getLocation(), event.getBlock());
        // Nothing to do: keep the watch alive so the landing checks still get their look. The
        // event also fires for water, ladders and crops, which are not what we are here for.
        if (safe == null) return;
        if (!claim(player)) return;

        player.teleportAsync(safe);
        record(player, player.getLocation(), PUSHED);
        pushed.incrementAndGet();
        notifyStaff(player);
    }

    /**
     * Checks where the player actually ended up, one tick after the pearl and again once they
     * have settled.
     *
     * <p>The destination test can only judge the spot the pearl reports; this judges the
     * outcome, so it does not care which block made the glitch possible. Two samples because
     * the first tick is before gravity and the client's own correction have had their say.
     */
    private void scheduleLandingCheck(Player player) {
        if (!postLandingCheck) return;
        later(() -> verifyLanding(player), 1L);
        later(() -> verifyLanding(player), 5L);
    }

    private void verifyLanding(Player player) {
        if (!player.isOnline() || bypassed(player)) return;
        if (!recentPearls.containsKey(player.getUniqueId())) return;   // already handled, or stale

        Location where = player.getLocation();
        if (!intersects(player.getWorld(), hitbox(where))) return;

        Location safe = safeNear(where);
        if (safe == null) return;
        if (!claim(player)) return;

        player.teleportAsync(safe);
        record(player, where, PUSHED);
        pushed.incrementAndGet();
        notifyStaff(player);
    }

    /** A player who logs out mid-window would otherwise leave their entry behind. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        recentPearls.remove(event.getPlayer().getUniqueId());
    }

    private boolean bypassed(Player player) {
        return adminBypass && player.hasPermission("antipearlglitch.bypass");
    }

    /**
     * Takes the one rescue a pearl is allowed, or reports it already spent.
     *
     * <p>The destination check and both landing checks fire for the same throw. Without this a
     * landing flush against a wall was "fixed" three times over, and since each fix could fall
     * back to two blocks up, the player came out six blocks higher than the pearl landed.
     */
    private boolean claim(Player player) {
        return recentPearls.remove(player.getUniqueId()) != null;
    }

    private void pruneStale() {
        long cutoff = System.currentTimeMillis() - timeWindowMillis;
        recentPearls.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    // --------------------------------------------------------------- geometry

    /**
     * The column tested for collisions: the player's full height, but only the middle of their
     * width.
     *
     * <p>A player is 0.6 wide and a pearl thrown at a wall drops you flush against it, so the
     * real hitbox overlaps that wall by a fraction of a block on every single throw — normal
     * collision pushes you out on the next tick and nobody notices. Testing the full width read
     * all of those as "stuck in a wall" and rescued a player who was never in trouble. Only the
     * core counts: to put that inside a block you have to be genuinely clipped into it.
     */
    private static BoundingBox hitbox(Location loc) {
        return new BoundingBox(loc.getX() - CORE_HALF_WIDTH, loc.getY() + SKIN, loc.getZ() - CORE_HALF_WIDTH,
                loc.getX() + CORE_HALF_WIDTH, loc.getY() + PLAYER_HEIGHT - SKIN, loc.getZ() + CORE_HALF_WIDTH);
    }

    /** True when a player standing at {@code loc} would be somewhere they should not be. */
    private boolean isUnsafe(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        return intersects(loc.getWorld(), hitbox(loc));
    }

    /**
     * Whether {@code box} runs into anything in {@code world}.
     *
     * <p>The test is {@link Block#isPassable()} plus a real box overlap, not {@code isSolid()}.
     * Solidity is a property of the material, not of the space it occupies: a fence is solid but
     * only fills its middle, and a stair is solid but leaves half the block free.
     *
     * <p>Only real collision counts. Blocks you can stand inside — ladders, vines, scaffolding —
     * are legitimate places to be, and calling a landing in one unsafe only produced false
     * positives on players climbing normally.
     */
    private boolean intersects(World world, BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) continue;
                    if (block.isPassable()) continue;          // no collision: water, grass, …
                    if (block.getBoundingBox().overlaps(box)) return true;
                }
            }
        }
        return false;
    }

    /**
     * A clear spot near {@code loc}: on top of the block the feet are in, on top of the one the
     * head is in, or the nearest free pair of blocks around it.
     *
     * <p>Returns {@code null} when nothing works, which the caller turns into a cancelled
     * teleport. The original version fell back to {@code loc + 2 blocks up}, which inside a
     * cave roof is another block to be stuck in — an unchecked "safe" spot defeats the point.
     */
    private Location safeNear(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);

        if (isSolid(feet) && isFree(head)) return standOn(feet, loc);
        if (isFree(feet) && isSolid(head)) return standOn(head, loc);

        // Ring by ring so the player moves as little as possible, and behind them first: a pearl
        // that clips you is one you threw at the wall you are looking at, so forward is the one
        // direction that is guaranteed to be full. Searching it first is what walked players
        // into the wall and left the upward fallback as the only way out.
        BlockFace[] order = escapeOrder(facing(loc.getYaw()));
        for (int step = 1; step <= searchDistance; step++) {
            for (BlockFace face : order) {
                Block candidate = feet.getRelative(face, step);
                if (isFree(candidate) && isFree(candidate.getRelative(BlockFace.UP))) {
                    return centre(candidate, loc);
                }
            }
        }

        Block above = feet.getRelative(BlockFace.UP, 2);
        return isFree(above) && isFree(above.getRelative(BlockFace.UP)) ? centre(above, loc) : null;
    }

    /** Behind, then the two sides, then forward — the order rooms are likely to be free in. */
    private static BlockFace[] escapeOrder(BlockFace facing) {
        int i = 0;
        while (i < COMPASS.length && COMPASS[i] != facing) i++;
        if (i == COMPASS.length) i = 0;
        return new BlockFace[]{ COMPASS[(i + 2) % 4], COMPASS[(i + 1) % 4], COMPASS[(i + 3) % 4], facing };
    }

    /**
     * The nearest point just outside {@code block}, chosen along whichever axis the player is
     * already furthest out on — the shortest way out of the block they are clipped into.
     */
    private Location pushOut(Location playerLoc, Block block) {
        double blockX = block.getX() + 0.5;
        double blockY = block.getY() + 0.5;
        double blockZ = block.getZ() + 0.5;

        double dx = playerLoc.getX() - blockX;
        double dy = playerLoc.getY() - blockY;
        double dz = playerLoc.getZ() - blockZ;

        double absX = Math.abs(dx);
        double absY = Math.abs(dy);
        double absZ = Math.abs(dz);

        double safeX = blockX;
        double safeY = blockY;
        double safeZ = blockZ;

        if (absX > absY && absX > absZ) {
            safeX = blockX + (dx > 0 ? 0.6 : -0.6);
        } else if (absY > absZ) {
            safeY = blockY + (dy > 0 ? 1.0 : -1.0);
        } else {
            safeZ = blockZ + (dz > 0 ? 0.6 : -0.6);
        }

        Location safe = new Location(block.getWorld(), safeX, safeY, safeZ,
                playerLoc.getYaw(), playerLoc.getPitch());
        if (isFree(safe.getBlock())) return safe;

        Block top = block.getRelative(BlockFace.UP);
        return isFree(top) && isFree(top.getRelative(BlockFace.UP)) ? centre(top, playerLoc) : null;
    }

    /** Something to stand on: not whether the material is "solid", but whether it holds you up. */
    private static boolean isSolid(Block block) {
        return !block.getType().isAir() && !block.isPassable();
    }

    /** Somewhere a player can be: anything without collision, ladders and vines included. */
    private static boolean isFree(Block block) {
        return block.isPassable();
    }

    /** Standing on top of {@code block}, keeping the player looking where they were looking. */
    private static Location standOn(Block block, Location source) {
        return centre(block.getRelative(BlockFace.UP), source);
    }

    private static Location centre(Block block, Location source) {
        // Carrying yaw/pitch over matters: a Location built from a block has both at 0, so the
        // rescued player used to be spun round to face south with their head level.
        return new Location(block.getWorld(), block.getX() + 0.5, block.getY(), block.getZ() + 0.5,
                source.getYaw(), source.getPitch());
    }

    /**
     * The direction the player is facing.
     *
     * <p>Minecraft yaw runs 0 = south, 90 = west, 180 = north, 270 = east. The original mapping
     * had it a quarter turn out (0 read as north), so the forward search walked sideways.
     */
    private static BlockFace facing(float yaw) {
        float rotation = yaw % 360;
        if (rotation < 0) rotation += 360f;
        if (rotation >= 315 || rotation < 45) return BlockFace.SOUTH;
        if (rotation < 135) return BlockFace.WEST;
        if (rotation < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    // ---------------------------------------------------------------- reports

    private void record(Player player, Location where, String action) {
        history.addFirst(new Intervention(System.currentTimeMillis(), player.getName(),
                where.getWorld() == null ? "?" : where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ(), action));
        while (history.size() > MAX_HISTORY) history.removeLast();
        version.incrementAndGet();
    }

    private void notifyStaff(Player player) {
        if (staffMessage == null) return;

        Component message = staffMessage.replaceText(builder ->
                builder.matchLiteral("{player}").replacement(player.getName()));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("antipearlglitch.notify")) staff.sendMessage(message);
        }
    }

    // -------------------------------------------------------------------- web

    public long version() {
        return version.get();
    }

    public List<Intervention> recent() {
        return new ArrayList<>(history);
    }

    public String json() {
        StringBuilder events = new StringBuilder("[");
        List<Intervention> list = recent();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) events.append(',');
            events.append(list.get(i).json());
        }
        events.append(']');

        return "{\"running\":true"
                + ",\"total\":" + (moved.get() + cancelled.get() + pushed.get())
                + ",\"moved\":" + moved.get()
                + ",\"cancelled\":" + cancelled.get()
                + ",\"pushed\":" + pushed.get()
                + ",\"adminBypass\":" + adminBypass
                + ",\"timeWindowMs\":" + timeWindowMillis
                + ",\"searchDistance\":" + searchDistance
                + ",\"postLandingCheck\":" + postLandingCheck
                + ",\"staffMessage\":" + WebStats.jsonStr(staffMessageRaw)
                + ",\"watching\":" + recentPearls.size()
                + ",\"events\":" + events + "}";
    }
}
