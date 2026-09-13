package dev.lorenzzz.smplibrary.modules.gui;

import dev.lorenzzz.smplibrary.util.SoundCue;

import java.util.List;
import java.util.Map;

/**
 * The whole of {@code inventories/gui.yml}: the two screens plus what they share.
 *
 * @param homes    the homes screen
 * @param warps    the warps screen
 * @param items    the shared icons, keyed by their name in the file ({@code filler},
 *                 {@code next-page}, {@code info}…)
 * @param intercept which HuskHomes commands get replaced by these screens
 */
record GuiConfig(
        ScreenConfig homes,
        ScreenConfig warps,
        Map<String, IconConfig> items,
        InterceptConfig intercept,
        SoundCue openSound,
        SoundCue pageSound,
        SoundCue teleportSound,
        SoundCue emptySound
) {}

/**
 * One of the two screens.
 *
 * @param layout            one string per row, one character per slot — see gui.yml
 * @param command           run as the player on click, with {@code %name%} substituted
 * @param noDescription     what {@code %description%} becomes for an entry without one
 * @param publicLabel       what {@code %visibility%} becomes for a public home
 * @param privateLabel      what {@code %visibility%} becomes for a private one
 * @param filterByPermission warps only: hide the ones the player has no permission for
 */
record ScreenConfig(
        boolean enabled,
        String title,
        List<String> layout,
        String command,
        String noDescription,
        String publicLabel,
        String privateLabel,
        boolean filterByPermission,
        IconConfig item
) {}

/**
 * One icon out of the file.
 *
 * @param command what the button runs, for the icons that are buttons ({@code action}); empty
 *                for the decorative ones
 * @param close   whether the screen closes after that command
 */
record IconConfig(
        String material,
        String name,
        List<String> lore,
        boolean glow,
        String command,
        boolean close
) {}

/**
 * The HuskHomes commands these screens take over.
 *
 * <p>Held as the exact strings typed after the slash, matched only when the player supplied no
 * further arguments: {@code /homes} opens the GUI, {@code /homes Someone} stays HuskHomes'.
 */
record InterceptConfig(
        boolean enabled,
        List<String> homes,
        List<String> warps
) {}
