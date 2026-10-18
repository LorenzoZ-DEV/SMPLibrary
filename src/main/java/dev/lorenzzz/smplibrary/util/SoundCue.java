package dev.lorenzzz.smplibrary.util;

import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * A configured sound: {@code type} + {@code volume} + {@code pitch}, read from a config block.
 *
 * <p>Set {@code enabled: false}, or leave {@code type} blank, to mute a cue without deleting it.
 */
public record SoundCue(Sound sound, float volume, float pitch) {

    /** A cue that plays nothing — what a disabled or unreadable config block resolves to. */
    public static final SoundCue SILENT = new SoundCue(null, 0f, 0f);

    /**
     * Reads a {@code type/volume/pitch/enabled} block, falling back to {@code fallback} for any
     * field the section doesn't set. A missing section keeps the fallback as-is.
     */
    public static SoundCue from(ConfigurationSection section, SoundCue fallback) {
        if (section == null) return fallback;
        if (!section.getBoolean("enabled", true)) return SILENT;

        String configured = section.getString("type");
        Sound sound = configured == null || configured.isBlank() ? fallback.sound() : resolve(configured);
        if (sound == null) {
            Log.warning("Unknown sound '" + configured + "', keeping the default.");
            sound = fallback.sound();
        }
        return new SoundCue(sound,
                (float) section.getDouble("volume", fallback.volume()),
                (float) section.getDouble("pitch", fallback.pitch()));
    }

    /** Convenience for code-level defaults, e.g. {@code SoundCue.of("block.note_block.pling", 1f, 1f)}. */
    public static SoundCue of(String key, float volume, float pitch) {
        Sound sound = resolve(key);
        if (sound == null) {
            // A built-in default that doesn't resolve would just be silent forever — say so.
            Log.warning("Built-in sound '" + key + "' is not in this server's registry; that cue will be silent.");
        }
        return new SoundCue(sound, volume, pitch);
    }

    /**
     * Accepts both the registry key ({@code entity.ender_dragon.flap}) and the old enum spelling
     * ({@code ENTITY_ENDER_DRAGON_FLAP}). A plain underscore-to-dot conversion alone silently
     * failed on compound names like ender_dragon, so both forms are tried.
     */
    public static Sound resolve(String configured) {
        if (configured == null || configured.isBlank()) return null;
        String value = configured.trim().toLowerCase(Locale.ROOT);

        NamespacedKey key = value.contains(":") ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
        Sound sound = key == null ? null : Registry.SOUND_EVENT.get(key);
        if (sound != null) return sound;

        return Registry.SOUND_EVENT.get(NamespacedKey.minecraft(value.replace('_', '.')));
    }

    public boolean silent() {
        return sound == null || volume <= 0f;
    }

    /** Plays at the player's own position, so everyone hears it equally loud. */
    public void play(Player player) {
        if (silent()) return;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** Plays at a given spot for one player only — positioned ambience, not a broadcast. */
    public void playTo(Player player, Location location) {
        if (silent() || location.getWorld() == null) return;
        player.playSound(location, sound, volume, pitch);
    }

    /** Plays in the world, audible to whoever is nearby. */
    public void playAt(Location location) {
        if (silent() || location.getWorld() == null) return;
        location.getWorld().playSound(location, sound, volume, pitch);
    }

    /** Plays for every online player — for server-wide events like a chat game starting. */
    public void playToAll() {
        if (silent()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }
}
