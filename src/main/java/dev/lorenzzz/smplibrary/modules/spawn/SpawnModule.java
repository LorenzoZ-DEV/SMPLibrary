package dev.lorenzzz.smplibrary.modules.spawn;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.commands.SpawnCommands;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.combat.CombatModule;
import dev.lorenzzz.smplibrary.util.Mini;
import dev.lorenzzz.smplibrary.util.SoundCue;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /spawn} + {@code /setspawn} ported from venrix-utils.
 *
 * <p>Delayed teleport with a countdown action bar, cancelled when the player moves,
 * plus teleport sounds and first-join teleport. The spawn point lives in
 * {@code plugins/SmpLibrary/spawn.yml} (world + world-uuid + xyz + yaw/pitch), saved
 * async on {@code /setspawn} and flushed on shutdown. Configured under
 * {@code MODULES.spawn}; texts under {@code spawn.*} in messages.yml (MiniMessage).
 */
public final class SpawnModule extends AbstractModule implements Listener {

    public static final String BYPASS_PERMISSION = "smplibrary.spawn.bypass";
    public static final String SET_PERMISSION = "smplibrary.spawn.set";

    private static final String KEY = "spawn";

    private record Countdown(int remaining, Location start, Location target) {}

    /**
     * Spawn point as pure data — no Bukkit {@link World} reference. Worlds don't exist yet
     * when plugins enable (Paper boots plugins before creating worlds), so resolving the
     * world at {@code onEnable} always fails after a restart. The world is looked up lazily
     * in {@link #getSpawn()}, when it is actually needed.
     */
    private record SpawnPoint(String worldName, UUID worldUuid,
                              double x, double y, double z, float yaw, float pitch) {}

    private final Map<UUID, Countdown> pending = new ConcurrentHashMap<>();
    private volatile SpawnPoint spawn;

    /**
     * One shared ticker for every countdown, started on demand and stopped when the last one
     * finishes. There used to be a repeating task per teleporting player — the scheduler
     * carried one entry per {@code /spawn} in flight, for a job that is the same second tick
     * for all of them.
     */
    private int tickerId = -1;

    private int delay;
    private boolean cancelOnMove;
    private double maxMoveSq;
    private boolean teleportNewPlayers;
    private boolean respawnAtSpawn;
    private SoundCue teleportSound = SoundCue.SILENT;
    private SoundCue countdownSound = SoundCue.SILENT;
    private SoundCue cancelSound = SoundCue.SILENT;

    private SpawnCommands command;

    public SpawnModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "spawn"; }

    @Override
    public void onEnable() {
        loadCache(config());
        loadSpawn();
        listen(this);
        command = new SpawnCommands();
        if (commands != null) commands.register(command);
        SpawnPoint point = spawn;
        C.info("Spawn: " + (point == null ? "punto non impostato (/setspawn)"
                : "punto caricato (" + point.worldName() + " " + point.x() + ", " + point.y() + ", " + point.z() + ")"));
    }

    @Override
    protected void onShutdown() {
        stopTicker();
        pending.clear();
        if (commands != null && command != null) commands.unregister(command);
        command = null;
        flushSpawn();
    }

    // ------------------------------------------------------------------ config

    private void loadCache(ConfigurationSection cfg) {
        if (cfg == null) return;
        delay = Math.max(0, cfg.getInt("delay", 5));
        cancelOnMove = cfg.getBoolean("cancel-on-move", true);
        double maxMove = Math.max(0.0, cfg.getDouble("max-move-distance", 0.5));
        maxMoveSq = maxMove * maxMove;
        teleportNewPlayers = cfg.getBoolean("teleport-new-players", true);
        respawnAtSpawn = cfg.getBoolean("respawn-at-spawn", true);
        teleportSound = SoundCue.from(cfg.getConfigurationSection("sounds.teleport"),
                SoundCue.of("entity.enderman.teleport", 1f, 1f));
        countdownSound = SoundCue.from(cfg.getConfigurationSection("sounds.countdown-tick"),
                SoundCue.of("block.note_block.pling", 1f, 1f));
        cancelSound = SoundCue.from(cfg.getConfigurationSection("sounds.teleport-canceled"),
                SoundCue.of("entity.villager.no", 1f, 1f));
    }

    private File spawnFile() {
        return new File(plugin.getDataFolder(), "spawn.yml");
    }

    // ------------------------------------------------------------------ storage

    /** Reads only data — no world lookup, worlds don't exist yet at enable time. */
    private void loadSpawn() {
        File file = spawnFile();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!yaml.contains(KEY + ".world")) return;

        UUID worldUuid = null;
        String rawUuid = yaml.getString(KEY + ".world-uuid");
        if (rawUuid != null) {
            try {
                worldUuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException ignored) {
            }
        }
        spawn = new SpawnPoint(yaml.getString(KEY + ".world"), worldUuid,
                yaml.getDouble(KEY + ".x"), yaml.getDouble(KEY + ".y"), yaml.getDouble(KEY + ".z"),
                (float) yaml.getDouble(KEY + ".yaw"), (float) yaml.getDouble(KEY + ".pitch"));
    }

    /**
     * Snapshot + async save, reporting the outcome to {@code player}.
     *
     * <p>The reply is posted back on the main thread: the write runs on the common pool, and
     * so did the old {@code thenRun} that answered the player from it.
     */
    public void setSpawn(Player player, Location location) {
        World world = location.getWorld();
        SpawnPoint point = new SpawnPoint(
                world == null ? null : world.getName(),
                world == null ? null : world.getUID(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        spawn = point;
        CompletableFuture.runAsync(() -> writeSpawn(point))
                .whenComplete((ignored, error) -> onMain(() -> {
                    if (error != null) C.warning("Spawn: " + error.getMessage());
                    tell(player, error == null ? "spawn.set" : "spawn.set-failed", Map.of());
                }));
    }

    /** Runs on the main thread; dropped if the plugin went away in the meantime. */
    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private void writeSpawn(SpawnPoint point) {
        File file = spawnFile();
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new RuntimeException("impossibile creare la cartella dati");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(KEY + ".world", point.worldName());
        yaml.set(KEY + ".world-uuid", point.worldUuid() == null ? null : point.worldUuid().toString());
        yaml.set(KEY + ".x", point.x());
        yaml.set(KEY + ".y", point.y());
        yaml.set(KEY + ".z", point.z());
        yaml.set(KEY + ".yaw", point.yaw());
        yaml.set(KEY + ".pitch", point.pitch());
        try {
            yaml.save(file);
        } catch (Exception e) {
            throw new RuntimeException("salvataggio spawn.yml fallito", e);
        }
    }

    private void flushSpawn() {
        SpawnPoint current = spawn;
        if (current == null) return;
        try {
            writeSpawn(current);
        } catch (RuntimeException e) {
            C.warning("Spawn: flush all'arresto fallito (" + e.getMessage() + ")");
        }
    }

    /** True when a spawn point was ever set (even if its world isn't loaded right now). */
    public boolean hasSpawn() {
        return spawn != null;
    }

    /**
     * Resolves the spawn to a live location. Null when never set <em>or</em> when the
     * world isn't loaded — callers distinguish with {@link #hasSpawn()}.
     */
    public Location getSpawn() {
        SpawnPoint point = spawn;
        if (point == null) return null;
        World world = point.worldUuid() == null ? null : Bukkit.getWorld(point.worldUuid());
        if (world == null && point.worldName() != null) world = Bukkit.getWorld(point.worldName());
        if (world == null) return null;
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    public int delayFor(Player player) {
        if (player.hasPermission(BYPASS_PERMISSION)) return 0;
        return delay;
    }

    /** Sends a {@code spawn.*} MiniMessage text — for the command class. */
    public void tell(Player player, String key, Map<String, String> placeholders) {
        Mini.send(player, messages, key, placeholders);
    }

    // ------------------------------------------------------------------ teleport

    public void teleportToSpawn(Player player) {
        Location target = getSpawn();
        if (target == null) {
            Mini.send(player, messages, hasSpawn() ? "spawn.world-missing" : "spawn.not-set", Map.of());
            return;
        }
        teleportWithDelay(player, target, delayFor(player));
    }

    private void teleportWithDelay(Player player, Location target, int seconds) {
        UUID playerId = player.getUniqueId();
        if (pending.containsKey(playerId)) {
            Mini.send(player, messages, "spawn.already-teleporting", Map.of());
            return;
        }
        if (seconds <= 0) {
            teleportNow(player, target);
            return;
        }
        pending.put(playerId, new Countdown(seconds, player.getLocation().clone(), target));
        announce(player, seconds);
        startTicker();
    }

    private void startTicker() {
        if (tickerId != -1) return;
        tickerId = repeat(this::tick, 20L, 20L);
    }

    private void stopTicker() {
        if (tickerId == -1) return;
        cancelTask(tickerId);
        tickerId = -1;
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Countdown>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Countdown> entry = it.next();
            Countdown state = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                it.remove();
                continue;
            }
            if (hasMoved(player, state.start()) || taggedInCombat(player)) {
                it.remove();
                Mini.send(player, messages, "spawn.teleport-canceled", Map.of());
                cancelSound.play(player);
                continue;
            }
            int remaining = state.remaining() - 1;
            if (remaining <= 0) {
                it.remove();
                teleportNow(player, state.target());
                continue;
            }
            entry.setValue(new Countdown(remaining, state.start(), state.target()));
            announce(player, remaining);
        }
        if (pending.isEmpty()) stopTicker();
    }

    private void announce(Player player, int remaining) {
        Mini.actionBar(player, messages, "spawn.delay-actionbar", Map.of("time", String.valueOf(remaining)));
        countdownSound.play(player);
    }

    /**
     * A countdown started before the fight was a free escape: {@code /spawn} is blocked while
     * tagged, but a teleport already in flight kept running, so the move was to type it a
     * moment before engaging. Looked up per tick because a module that isn't running yet (or
     * was reloaded away) must read as "no combat", not as a stale reference.
     */
    private boolean taggedInCombat(Player player) {
        if (managers == null || player.hasPermission(BYPASS_PERMISSION)) return false;
        CombatModule combat = managers.modules().module("combat", CombatModule.class);
        return combat != null && combat.isTagged(player);
    }

    private boolean hasMoved(Player player, Location start) {
        if (!cancelOnMove) return false;
        if (player.hasPermission(BYPASS_PERMISSION)) return false;
        Location current = player.getLocation();
        if (current.getWorld() == null || !current.getWorld().equals(start.getWorld())) return true;
        double dx = current.getX() - start.getX();
        double dy = current.getY() - start.getY();
        double dz = current.getZ() - start.getZ();
        return dx * dx + dy * dy + dz * dz > maxMoveSq;
    }

    /** Middle of the block rather than its corner, so nobody lands clipped into a wall. */
    private static Location centered(Location target) {
        Location centered = target.clone();
        centered.setX(centered.getBlockX() + 0.5);
        centered.setZ(centered.getBlockZ() + 0.5);
        return centered;
    }

    private void teleportNow(Player player, Location target) {
        player.teleportAsync(centered(target)).thenAccept(success -> {
            if (!Boolean.TRUE.equals(success)) return;
            player.setFallDistance(0f);
            teleportSound.play(player);
            // send, not a bare render: this was the one place that skipped the action bar
            // companion, so the configured spawn.teleported-actionbar never showed up.
            Mini.send(player, messages, "spawn.teleported", Map.of());
        });
    }

    // ------------------------------------------------------------------ respawn

    /**
     * Sends the dead back to the configured spawn instead of the vanilla world spawn.
     *
     * <p>Nothing was listening for respawns, so the server did what Minecraft does on its own:
     * drop the player at the world's spawn point — those coordinates out in the overworld that
     * have nothing to do with {@code /setspawn}. A bed or a charged respawn anchor still wins,
     * which is the whole point of having one: the flags tell us the location Minecraft picked
     * actually came from one, and a bed that has been destroyed or blocked does not set them,
     * so that case correctly falls through to spawn.
     *
     * <p>Priority NORMAL on purpose — a plugin that really owns respawns (an arena, a jail)
     * runs at HIGH/HIGHEST and overrides this rather than fighting it.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!respawnAtSpawn) return;
        Set<PlayerRespawnEvent.RespawnFlag> flags = event.getRespawnFlags();
        if (flags.contains(PlayerRespawnEvent.RespawnFlag.BED_SPAWN)
                || flags.contains(PlayerRespawnEvent.RespawnFlag.ANCHOR_SPAWN)) return;
        Location target = getSpawn();
        // No spawn set, or its world isn't loaded: leave vanilla alone rather than guess.
        if (target == null) return;
        event.setRespawnLocation(centered(target));
    }

    // ------------------------------------------------------------------ first join

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!teleportNewPlayers) return;
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        Location target = getSpawn();
        if (target == null) return;
        // Through later(), so a reload mid-join cancels it instead of firing against a
        // module that is no longer running.
        later(() -> {
            if (player.isOnline()) teleportNow(player, target);
        }, 1L);
    }
}
