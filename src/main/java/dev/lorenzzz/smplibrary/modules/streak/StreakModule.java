package dev.lorenzzz.smplibrary.modules.streak;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Mini;
import dev.lorenzzz.smplibrary.util.SoundCue;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Killstreaks ported from venrix-utils.
 *
 * <p>Consecutive player kills build a streak; crossing a {@code milestones} value
 * broadcasts (or whispers) a message with a sound, and dying to a player ends a streak
 * worth announcing. Streaks reset on death and quit unless configured otherwise.
 * Per-milestone message ({@code streak.milestones.<n>}) and sound overrides supported.
 * Configured under {@code MODULES.streak}; texts under {@code streak.*} in messages.yml.
 */
public final class StreakModule extends AbstractModule implements Listener {

    private record StreakSound(boolean enabled, SoundCue cue, boolean toAll) {}

    private static final StreakSound SILENT =
            new StreakSound(false, SoundCue.SILENT, false);

    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();

    private boolean resetOnDeath = true;
    private boolean resetOnQuit = true;
    private boolean broadcast = true;
    private boolean announceEnd = true;
    private int minEndStreak = 5;
    private final Set<Integer> milestones = new HashSet<>();

    private StreakSound milestoneSound = SILENT;
    private StreakSound endedSound = SILENT;
    private final Map<Integer, StreakSound> overrides = new HashMap<>();

    public StreakModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "streak"; }

    @Override
    public void onEnable() {
        loadCache(config());
        listen(this);
    }

    @Override
    protected void onShutdown() {
        streaks.clear();
    }

    private void loadCache(ConfigurationSection cfg) {
        if (cfg == null) return;
        resetOnDeath = cfg.getBoolean("reset-on-death", true);
        resetOnQuit = cfg.getBoolean("reset-on-quit", true);
        broadcast = cfg.getBoolean("broadcast", true);
        announceEnd = cfg.getBoolean("announce-end", true);
        minEndStreak = Math.max(1, cfg.getInt("min-end-streak", 5));
        milestones.clear();
        for (int milestone : cfg.getIntegerList("milestones")) {
            if (milestone > 0) milestones.add(milestone);
        }
        milestoneSound = readSound(cfg.getConfigurationSection("sounds.milestone"),
                Sound.ENTITY_ENDER_DRAGON_GROWL);
        endedSound = readSound(cfg.getConfigurationSection("sounds.ended"),
                Sound.ENTITY_WITHER_DEATH);
        overrides.clear();
        ConfigurationSection section = cfg.getConfigurationSection("sounds.overrides");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int milestone = Integer.parseInt(key);
                    overrides.put(milestone,
                            readSound(section.getConfigurationSection(key), milestoneSound.cue().sound()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private StreakSound readSound(ConfigurationSection section, Sound fallback) {
        if (section == null) return new StreakSound(false, SoundCue.SILENT, false);
        SoundCue cue = SoundCue.from(section, new SoundCue(fallback, 1f, 1f));
        return new StreakSound(section.getBoolean("enabled", true), cue,
                section.getBoolean("to-all", true));
    }

    /** Current streak, 0 when none. Public for placeholders and other modules. */
    public int getStreak(UUID playerId) {
        Integer value = streaks.get(playerId);
        return value == null ? 0 : value;
    }

    public void resetStreak(UUID playerId) {
        streaks.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        UUID victimId = victim.getUniqueId();

        int previous = getStreak(victimId);
        if (resetOnDeath) streaks.remove(victimId);
        if (killer == null || killer.equals(victim)) return;

        if (announceEnd && previous >= minEndStreak) {
            Mini.broadcast(messages, "streak.ended", Map.of(
                    "killer", killer.getName(),
                    "victim", victim.getName(),
                    "streak", String.valueOf(previous)));
            play(endedSound, killer);
        }

        int current = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
        if (milestones.contains(current)) announceMilestone(killer, current);
    }

    private void announceMilestone(Player player, int streak) {
        String specific = "streak.milestones." + streak;
        String key = messages.has(specific) ? specific : "streak.milestone";
        Map<String, String> placeholders = Map.of(
                "player", player.getName(), "streak", String.valueOf(streak));
        if (broadcast) {
            Mini.broadcast(messages, key, placeholders);
        } else {
            Mini.send(player, messages, key, placeholders);
        }
        play(overrides.getOrDefault(streak, milestoneSound), player);
    }

    private void play(StreakSound sound, Player source) {
        if (sound == null || !sound.enabled() || sound.cue().silent()) return;
        if (sound.toAll()) {
            sound.cue().playToAll();
        } else if (source != null && source.isOnline()) {
            sound.cue().play(source);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (resetOnQuit) streaks.remove(event.getPlayer().getUniqueId());
    }
}
