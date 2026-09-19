package dev.lorenzzz.smplibrary.modules.combat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Damage attribution for the combat tag: plain hits, arrows, end crystals (matched to
 * whoever broke them) and respawn anchors (matched to whoever clicked without glowstone).
 * Also owns custom death messages and the in-combat command blocklist.
 *
 * <p>The combat-log punishment itself lives in {@link CombatModule} — this class used to
 * kill on quit as well, at an earlier priority, which made the module's own handler (and
 * with it the {@code kill-on-quit} setting) dead code.
 */
public final class CombatListener implements Listener {

    private final Smp_library plugin;
    private final CombatModule combat;

    private Cache<UUID, UUID> crystalBreaker;
    private Cache<UUID, UUID> lastHitter;
    private Cache<Location, UUID> anchorTouch;

    private boolean playerTrigger = true;
    private boolean crystalTrigger = true;
    private boolean anchorTrigger = true;

    public CombatListener(Smp_library plugin, CombatModule combat) {
        this.plugin = plugin;
        this.combat = combat;
        reload();
    }

    public void reload() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("MODULES.combat");
        playerTrigger = cfg == null || cfg.getBoolean("triggers.player-attack", true);
        crystalTrigger = cfg == null || cfg.getBoolean("triggers.crystal", true);
        anchorTrigger = cfg == null || cfg.getBoolean("triggers.respawn-anchor", true);

        long crystalSec = Math.max(1, cfg == null ? 60 : cfg.getLong("cache.crystal-breaker-seconds", 60));
        long hitterSec = Math.max(1, cfg == null ? 15 : cfg.getLong("cache.last-hitter-seconds", 15));
        long anchorSec = Math.max(1, cfg == null ? 60 : cfg.getLong("cache.anchor-interaction-seconds", 60));

        crystalBreaker = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(crystalSec)).maximumSize(10_000).build();
        lastHitter = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(hitterSec)).maximumSize(50_000).build();
        anchorTouch = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(anchorSec)).maximumSize(20_000).build();
    }

    /** Who last hit {@code uuid}, for the module's combat-log kill credit. */
    UUID lastHitter(UUID uuid) {
        return lastHitter.getIfPresent(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCrystalBreak(EntityDamageByEntityEvent event) {
        if (!crystalTrigger) return;
        if (event.getEntity() instanceof EnderCrystal crystal && event.getDamager() instanceof Player player) {
            crystalBreaker.put(crystal.getUniqueId(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnchorTouch(PlayerInteractEvent event) {
        if (!anchorTrigger) return;
        if (event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.RESPAWN_ANCHOR
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (hand.getType() == Material.GLOWSTONE) return; // charging, not detonating
        anchorTouch.put(event.getClickedBlock().getLocation(), event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAnchorBlast(EntityDamageEvent event) {
        if (!anchorTrigger || !(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        Location damageAt = event.getDamageSource().getDamageLocation();
        if (damageAt == null || damageAt.getWorld() == null) return;
        Location block = new Location(damageAt.getWorld(),
                damageAt.getBlockX(), damageAt.getBlockY(), damageAt.getBlockZ());
        UUID interactorId = anchorTouch.getIfPresent(block);
        anchorTouch.invalidate(block);
        if (interactorId == null) return;
        Player interactor = Bukkit.getPlayer(interactorId);
        if (interactor == null) return;
        if (interactor.equals(player)) {
            combat.enterCombat(player);
        } else {
            lastHitter.put(player.getUniqueId(), interactorId);
            combat.enterCombat(player, interactor);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile) {
            if (!(projectile.getShooter() instanceof Entity shooter)) return;
            damager = shooter;
        }
        if (damager instanceof EnderCrystal crystal) {
            if (!crystalTrigger) return;
            UUID attackerId = crystalBreaker.getIfPresent(crystal.getUniqueId());
            if (attackerId == null) return;
            Player attacker = Bukkit.getPlayer(attackerId);
            if (attacker == null) return;
            if (attacker.equals(player)) {
                combat.enterCombat(player);
            } else {
                lastHitter.put(player.getUniqueId(), attackerId);
                combat.enterCombat(player, attacker);
            }
        } else if (damager instanceof Player attacker) {
            if (!playerTrigger || player.equals(attacker)) return;
            lastHitter.put(player.getUniqueId(), attacker.getUniqueId());
            combat.enterCombat(player, attacker);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        combat.untag(player, false);
        applyDeathMessage(event, player, player.getKiller());
    }

    /**
     * Replaces the vanilla line only when we actually have a text for it — an empty or
     * missing key used to broadcast {@code missing message: death.fall} to the whole server.
     */
    private void applyDeathMessage(PlayerDeathEvent event, Player player, Player killer) {
        if (killer != null) {
            if (!combat.hasMessage("death.player")) return;
            event.deathMessage(combat.render("death.player",
                    Map.of("player", player.getName(), "killer", killer.getName())));
            return;
        }
        String cause = "death." + causeKey(player);
        String key = combat.hasMessage(cause) ? cause : "death.default";
        if (!combat.hasMessage(key)) return;
        event.deathMessage(combat.render(key, Map.of("player", player.getName())));
    }

    private static String causeKey(Player player) {
        EntityDamageEvent last = player.getLastDamageCause();
        if (last == null) return "default";
        return switch (last.getCause()) {
            case FALL -> "fall";
            case DROWNING -> "drowning";
            case VOID -> "void";
            case FIRE, FIRE_TICK, LAVA -> "fire";
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE -> "mob";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "explosion";
            case SUFFOCATION -> "suffocation";
            case STARVATION -> "starvation";
            default -> "default";
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!combat.isTagged(player) || player.hasPermission(CombatModule.BYPASS_PERMISSION)) return;
        if (combat.allowedCommands().contains(normalise(event.getMessage()))) return;
        combat.tell(player, "combat.blocked", Map.of());
        event.setCancelled(true);
    }

    /**
     * {@code "/Essentials:MSG Bob"} -> {@code "/msg"}. The namespace has to come off or the
     * allowlist only recognises the bare spelling, and {@code /essentials:msg} — the same
     * command — reads as something else entirely.
     */
    private static String normalise(String message) {
        String first = message.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        return colon < 0 ? first : "/" + first.substring(colon + 1);
    }
}
