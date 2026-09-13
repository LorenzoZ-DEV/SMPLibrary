package dev.lorenzzz.smplibrary.hook;

/**
 * One home or warp, flattened into types this plugin owns.
 *
 * <p>The menus are built from these and never from {@code net.william278.huskhomes.position.*},
 * so the GUI classes stay loadable on a server without HuskHomes — only {@link HuskHomesHook}
 * has to be kept away from such a server, and it is the one class that checks.
 *
 * @param name        the home/warp name, as the player typed it into {@code /sethome}
 * @param description its description, empty when it has none
 * @param world       the world it sits in
 * @param server      the server it sits on; equal to the local one unless the network is proxied
 * @param owner       who owns the home; empty for warps, which belong to nobody
 * @param isPublic    whether the home is published to {@code /phome}; always false for warps
 */
public record SavedPoint(
        String name,
        String description,
        String world,
        String server,
        double x,
        double y,
        double z,
        String owner,
        boolean isPublic
) {

    /** The coordinates as whole blocks, which is what a lore line wants. */
    public String blockX() { return String.valueOf((int) Math.floor(x)); }
    public String blockY() { return String.valueOf((int) Math.floor(y)); }
    public String blockZ() { return String.valueOf((int) Math.floor(z)); }
}
