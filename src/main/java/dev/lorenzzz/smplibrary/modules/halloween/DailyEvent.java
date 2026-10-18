package dev.lorenzzz.smplibrary.modules.halloween;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * One flavour of Halloween day. The season picks exactly one of these per calendar day and
 * every feature of the module asks it what to do: how often candy drops, whether the mobs
 * that spawn tonight wear a pumpkin, whether a broken pumpkin is worth opening.
 *
 * <p>The day's pick is derived from the date rather than rolled at start-up (see
 * {@link HalloweenModule#today()}), so a restart in the middle of the afternoon does not hand
 * the server a different event than the one announced at midnight.
 *
 * <p>Traits instead of a boolean per behaviour: the listeners ask {@code has(PUMPKIN_HEADS)}
 * and a new event only has to list the traits it wants, with no new field to thread through
 * every call site.
 */
public enum DailyEvent {

    /** Pioggia di caramelle: nothing special happens, candy just falls far more often. */
    CANDY_RAIN(3.0),

    /** Raccolta anime: every mob killed leaves a trail of souls, and twice the candy. */
    SOUL_HARVEST(2.0, Trait.SOUL_TRAIL),

    /** Notte spettrale: the mobs spawning at night wear a carved pumpkin. */
    SPOOKY_NIGHT(1.5, Trait.PUMPKIN_HEADS, Trait.LOUD_AMBIENCE),

    /** Luna di sangue: night mobs glow through the walls, and candy is doubled. */
    BLOOD_MOON(2.0, Trait.GLOWING_MOBS, Trait.LOUD_AMBIENCE),

    /** Zucche fortunate: breaking a pumpkin is worth it today. */
    LUCKY_PUMPKINS(1.0, Trait.LUCKY_PUMPKINS);

    /** What an event switches on, beyond its candy multiplier. */
    public enum Trait {
        /** Hostile mobs spawning at night get a carved pumpkin helmet. */
        PUMPKIN_HEADS,
        /** Hostile mobs spawning at night are given permanent glowing. */
        GLOWING_MOBS,
        /** The ambience pass fires twice as often. */
        LOUD_AMBIENCE,
        /** Killing a mob draws soul particles where it died. */
        SOUL_TRAIL,
        /** Broken pumpkins use the much higher lucky chance and can pay a treat. */
        LUCKY_PUMPKINS
    }

    private final double candyMultiplier;
    private final Set<Trait> traits;

    DailyEvent(double candyMultiplier, Trait... traits) {
        this.candyMultiplier = candyMultiplier;
        this.traits = traits.length == 0
                ? EnumSet.noneOf(Trait.class)
                : EnumSet.copyOf(Arrays.asList(traits));
    }

    /** Applied to the configured candy chances. */
    public double candyMultiplier() {
        return candyMultiplier;
    }

    public boolean has(Trait trait) {
        return traits.contains(trait);
    }

    /** config.yml / messages.yml spelling: {@code BLOOD_MOON} -> {@code blood-moon}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Accepts both spellings ({@code blood-moon}, {@code BLOOD_MOON}); {@code null} when unknown. */
    public static DailyEvent parse(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
