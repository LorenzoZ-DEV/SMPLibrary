package dev.lorenzzz.smplibrary.modules.combat;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Mini;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat tag ported from venrix-utils.
 *
 * <p>Players dealing or taking PvP damage (including end crystals and respawn anchors,
 * attributed by {@link CombatListener}) stay tagged for {@code combat-time} seconds: an
 * action bar counts down, commands outside {@code allowed-commands} and ender chests are
 * blocked, and quitting mid-fight kills — or, when {@code kill-on-quit} is off, marks the
 * player to die on their next join, persisted in {@code plugins/SmpLibrary/combat-loggers.yml}
 * so a restart doesn't hand out an amnesty. Custom death messages per cause live here too.
 * Configured under {@code MODULES.combat}; texts under {@code combat.*} / {@code death.*}
 * in messages.yml (MiniMessage).
 */
public final class CombatModule extends AbstractModule implements Listener {

    public static final String BYPASS_PERMISSION = "smplibrary.combat.bypass";

    private final Map<UUID, Integer> tagged = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastDamager = new ConcurrentHashMap<>();
    private final Set<UUID> killOnJoin = ConcurrentHashMap.newKeySet();

    private CombatListener damageListener;

    private int combatTime = 15;
    private boolean killOnQuit = true;
    private boolean killOnRejoin = true;
    private boolean blockEnderChest = true;
    private List<String> allowedCommands = List.of("/msg", "/r", "/reply");

    public CombatModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "combat"; }

    @Override
    public void onEnable() {
        loadCache(config());
        loadLoggers();
        listen(this);
        damageListener = new CombatListener(plugin, this);
        listen(damageListener);
        repeat(this::tick, 0L, 20L);
    }

    @Override
    protected void onShutdown() {
        saveLoggers();
        tagged.clear();
        lastDamager.clear();
        killOnJoin.clear();
        damageListener = null;
    }

    // ------------------------------------------------------------------ config

    private void loadCache(ConfigurationSection cfg) {
        if (cfg == null) return;
        combatTime = Math.max(0, cfg.getInt("combat-time", 15));
        killOnQuit = cfg.getBoolean("kill-on-quit", true);
        killOnRejoin = cfg.getBoolean("kill-on-rejoin", true);
        blockEnderChest = cfg.getBoolean("block-ender-chest", true);
        // Normalised once: the comparison in CombatListener is against a lower-cased command,
        // and an entry typed 'Msg' in the config silently never matched.
        allowedCommands = cfg.getStringList("allowed-commands").stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .map(entry -> entry.startsWith("/") ? entry : "/" + entry)
                .toList();
        if (damageListener != null) damageListener.reload();
    }

    private File loggersFile() {
        return new File(plugin.getDataFolder(), "combat-loggers.yml");
    }

    private void loadLoggers() {
        File file = loggersFile();
        if (!file.isFile()) return;
        for (String raw : YamlConfiguration.loadConfiguration(file).getStringList("loggers")) {
            try {
                killOnJoin.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /**
     * Persists the pending rejoin-kills, and only those.
     *
     * <p>It used to persist everyone currently <em>tagged</em>, which turned a restart into a
     * mass execution: stop the server mid-fight and every player in combat died on their next
     * login, having done nothing but be online at the wrong moment. A shutdown is not a
     * combat log.
     */
    private void saveLoggers() {
        File file = loggersFile();
        if (killOnJoin.isEmpty()) {
            if (file.isFile() && !file.delete()) {
                C.warning("Combat: impossibile pulire combat-loggers.yml.");
            }
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("loggers", new ArrayList<>(killOnJoin).stream().map(UUID::toString).toList());
        try {
            yaml.save(file);
        } catch (Exception e) {
            C.warning("Combat: salvataggio combat-loggers fallito (" + e.getMessage() + ")");
        }
    }

    // ------------------------------------------------------------------ api

    public void enterCombat(Player player, Player damager) {
        if (player == null || player.equals(damager)) return;
        boolean victimBypass = player.hasPermission(BYPASS_PERMISSION);
        boolean damagerBypass = damager != null && damager.hasPermission(BYPASS_PERMISSION);

        if (!victimBypass) {
            tag(player);
        }
        if (damager != null && !damagerBypass) {
            if (!victimBypass) {
                lastDamager.put(player.getUniqueId(), damager.getUniqueId());
                lastDamager.put(damager.getUniqueId(), player.getUniqueId());
            }
            tag(damager);
        }
    }

    public void enterCombat(Player player) {
        if (player == null || player.hasPermission(BYPASS_PERMISSION)) return;
        tag(player);
    }

    private void tag(Player player) {
        if (!isTagged(player)) {
            Mini.send(player, messages, "combat.enter", Map.of());
            // An ender chest opened a moment before the first hit stayed open and usable:
            // the click guard cancels moves but the window itself was never taken away.
            closeEnderChest(player);
        }
        tagged.put(player.getUniqueId(), combatTime);
    }

    public void untag(Player player) {
        untag(player, true);
    }

    /** Silent form — a player who just died does not need to be told the fight is over. */
    void untag(Player player, boolean notify) {
        if (tagged.remove(player.getUniqueId()) != null) {
            if (notify) Mini.send(player, messages, "combat.exit", Map.of());
            lastDamager.remove(player.getUniqueId());
        }
    }

    public boolean isTagged(Player player) {
        Integer seconds = tagged.get(player.getUniqueId());
        return seconds != null && seconds > 0;
    }

    public int timeLeft(UUID uuid) {
        Integer seconds = tagged.get(uuid);
        return seconds == null ? 0 : Math.max(0, seconds);
    }

    public UUID lastDamager(UUID uuid) {
        return lastDamager.get(uuid);
    }

    public List<String> allowedCommands() {
        return allowedCommands;
    }

    void tell(Player player, String key, Map<String, String> placeholders) {
        Mini.send(player, messages, key, placeholders);
    }

    boolean hasMessage(String key) {
        return messages.has(key);
    }

    net.kyori.adventure.text.Component render(String key, Map<String, String> placeholders) {
        return Mini.render(messages, key, placeholders);
    }

    // ------------------------------------------------------------------ ticker

    private void tick() {
        if (tagged.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = tagged.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                lastDamager.remove(entry.getKey());
            } else if (entry.getValue() > 0) {
                entry.setValue(entry.getValue() - 1);
                Mini.actionBar(player, messages, "combat.action-bar",
                        Map.of("time", String.valueOf(entry.getValue() + 1)));
            } else {
                it.remove();
                lastDamager.remove(entry.getKey());
                Mini.send(player, messages, "combat.exit", Map.of());
            }
        }
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!killOnJoin.remove(player.getUniqueId())) return;
        if (!killOnRejoin) return;
        // A tick later: killing inside the join event runs before the client has finished
        // loading in, and the death screen it produces is the one nobody can click away.
        later(() -> {
            if (player.isOnline() && !player.isDead()) player.setHealth(0.0);
        }, 1L);
    }

    /**
     * Combat log. Single owner of the punishment — {@link CombatListener} used to kill here
     * too, at an earlier priority, so the player was already dead by the time this ran and
     * {@code kill-on-quit: false} did nothing at all.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!isTagged(player)) return;

        boolean killed = false;
        if (killOnQuit && !player.isDead() && player.getHealth() > 0.0) {
            Player killer = attributedKiller(uuid);
            if (killer != null) {
                player.setKiller(killer);
                Mini.send(killer, messages, "combat.quit-killer", Map.of("player", player.getName()));
            }
            player.setHealth(0.0);
            killed = true;
        }
        // Either the kill is off, or it could not be applied — the rejoin kill is what stops
        // that from being a free escape. Written to disk now so a crash doesn't lose it.
        if (!killed && killOnRejoin) {
            killOnJoin.add(uuid);
            saveLoggers();
        }

        tagged.remove(uuid);
        lastDamager.remove(uuid);
    }

    /** Whoever last hit them, preferring the listener's own attribution (crystals, anchors). */
    private Player attributedKiller(UUID uuid) {
        UUID killerId = damageListener == null ? null : damageListener.lastHitter(uuid);
        if (killerId == null) killerId = lastDamager.get(uuid);
        if (killerId == null) return null;
        Player killer = Bukkit.getPlayer(killerId);
        return killer != null && killer.isOnline() ? killer : null;
    }

    // -------------------------------------------------------------- ender chest

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChestOpen(InventoryOpenEvent event) {
        if (!blockEnderChest || !(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (!isTagged(player)) return;
        event.setCancelled(true);
        Mini.send(player, messages, "combat.blocked", Map.of());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChestClick(InventoryClickEvent event) {
        if (!blockEnderChest) return;
        HumanEntity entity = event.getWhoClicked();
        if (!(entity instanceof Player player) || !isTagged(player)) return;
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        event.setCancelled(true);
        Mini.send(player, messages, "combat.blocked", Map.of());
        player.closeInventory();
    }

    /**
     * Dragging is a separate event from clicking, and it was not covered: a tagged player with
     * an ender chest already open could still move stacks in and out of it by dragging.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChestDrag(InventoryDragEvent event) {
        if (!blockEnderChest) return;
        HumanEntity entity = event.getWhoClicked();
        if (!(entity instanceof Player player) || !isTagged(player)) return;
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        event.setCancelled(true);
        Mini.send(player, messages, "combat.blocked", Map.of());
        player.closeInventory();
    }

    private void closeEnderChest(Player player) {
        if (!blockEnderChest) return;
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getType() == InventoryType.ENDER_CHEST) {
            player.closeInventory();
        }
    }
}
