package dev.lorenzzz.smplibrary.modules.nightvision;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.commands.NightVisionCommands;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Mini;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toggleable infinite night vision ported from venrix-utils ({@code /nv}).
 *
 * <p>The effect survives milk and respawn (re-applied a tick later) and is forgotten on
 * quit. Amplifier, duration, particles, icon and ambience come from
 * {@code MODULES.nightvision}; texts under {@code night-vision.*} in messages.yml.
 */
public final class NightVisionModule extends AbstractModule implements Listener {

    public static final String USE_PERMISSION = "smplibrary.nightvision.use";
    public static final String OTHERS_PERMISSION = "smplibrary.nightvision.others";

    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    private int amplifier;
    private int durationTicks;
    private boolean particles;
    private boolean icon;
    private boolean ambient;

    private NightVisionCommands command;

    public NightVisionModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "nightvision"; }

    @Override
    public void onEnable() {
        loadCache(config());
        listen(this);
        command = new NightVisionCommands();
        if (commands != null) commands.register(command);
    }

    @Override
    protected void onShutdown() {
        if (commands != null && command != null) commands.unregister(command);
        command = null;
        active.clear();
    }

    private void loadCache(ConfigurationSection cfg) {
        amplifier = Math.max(0, cfg == null ? 0 : cfg.getInt("amplifier", 0));
        int duration = cfg == null ? -1 : cfg.getInt("duration-ticks", -1);
        durationTicks = duration < 0 ? Integer.MAX_VALUE : duration;
        particles = cfg == null || cfg.getBoolean("show-particles", false);
        icon = cfg == null || cfg.getBoolean("show-icon", false);
        ambient = cfg == null || cfg.getBoolean("ambient", true);
    }

    /**
     * Toggles the effect on {@code target}, reporting to {@code viewer}.
     *
     * <p>Keyed on what this module handed out, not on {@code hasPotionEffect}: that read the
     * state off the player, so {@code /nv} on someone who had merely drunk a night vision
     * potion stripped the potion and turned nothing on, and the module's own bookkeeping —
     * which drives the re-apply after milk and respawn — drifted out of step with it.
     */
    public void toggle(Player viewer, Player target) {
        UUID targetId = target.getUniqueId();
        boolean wasActive = active.remove(targetId);
        if (wasActive) {
            target.removePotionEffect(PotionEffectType.NIGHT_VISION);
        } else {
            active.add(targetId);
            apply(target);
        }
        String key = (wasActive ? "night-vision.disabled" : "night-vision.enabled")
                + (viewer.equals(target) ? "" : "-other");
        Mini.send(viewer, messages, key, Map.of("target", target.getName()));
    }

    /** Sends a {@code night-vision.*} MiniMessage text — for the command class. */
    public void tell(Player player, String key, Map<String, String> placeholders) {
        Mini.send(player, messages, key, placeholders);
    }

    private void apply(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION, durationTicks, amplifier, ambient, particles, icon));
    }

    private void reapplyLater(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && active.contains(player.getUniqueId())) apply(player);
        }, 1L);
    }

    @EventHandler
    public void onMilk(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() != Material.MILK_BUCKET) return;
        if (active.contains(event.getPlayer().getUniqueId())) reapplyLater(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (active.contains(event.getPlayer().getUniqueId())) reapplyLater(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }
}
