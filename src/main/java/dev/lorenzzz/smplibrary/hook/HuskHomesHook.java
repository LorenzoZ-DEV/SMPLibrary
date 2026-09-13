package dev.lorenzzz.smplibrary.hook;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.manager.RequestsManager;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.position.PositionMeta;
import net.william278.huskhomes.position.SavedPosition;
import net.william278.huskhomes.position.Warp;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.teleport.TeleportRequest;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one class that talks to HuskHomes.
 *
 * <p>{@code huskhomes-bukkit} is {@code compileOnly}, so on a server without HuskHomes the
 * {@code net.william278.huskhomes.*} classes are simply not there. Loading this class resolves
 * them, so nothing may touch it before the plugin is confirmed present — the constructor throws
 * when it is not, which every caller reads as "try again later".
 *
 * <p>Homes and warps come back as {@link SavedPoint}, a type of ours: the menus are then plain
 * Bukkit code that cannot fail to load, and everything version-fragile lives here.
 *
 * <h2>Teleport requests</h2>
 * HuskHomes keeps a deque of pending requests per recipient and exposes only its newest entry
 * ({@link RequestsManager#getLastTeleportRequest}). That is enough for TPAuto because the live
 * path is the event — {@code ReceiveTeleportRequestEvent} carries the exact request — and this
 * sweep is only the safety net for what was already waiting when the switch was turned on.
 */
public final class HuskHomesHook {

    public static final String PLUGIN_NAME = "HuskHomes";

    /**
     * How long one requester stays "already handled" after TPAuto accepted them.
     *
     * <p>Accepting is not instantaneous over there: HuskHomes runs the teleport warmup first
     * and the request lingers while it counts down, so a sweep a few ticks later would answer
     * the same request a second time.
     */
    private static final long ACCEPT_COOLDOWN_MS = 10_000L;

    private final HuskHomesAPI api;
    private final HuskHomes plugin;

    /** "recipient:requester" -&gt; when TPAuto last accepted for that pair. */
    private final Map<String, Long> handled = new ConcurrentHashMap<>();

    public HuskHomesHook() {
        Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (found == null || !found.isEnabled()) {
            throw new IllegalStateException(PLUGIN_NAME + " is not loaded");
        }
        if (!(found instanceof HuskHomes huskHomes)) {
            // Same name, different plugin, or a build whose main class no longer implements the
            // interface. Either way the managers are out of reach and this is not our HuskHomes.
            throw new IllegalStateException(PLUGIN_NAME + " does not expose its API on this build");
        }
        this.plugin = huskHomes;
        this.api = HuskHomesAPI.getInstance();
    }

    /*
     * Deliberately no static isPresent() helper here: calling one would load this very class,
     * which is the thing that must not happen while HuskHomes is missing. Callers test with
     * Bukkit.getPluginManager().isPluginEnabled(HuskHomesHook.PLUGIN_NAME) instead — the
     * constant is inlined by the compiler, so reading it loads nothing.
     */

    /** The HuskHomes version behind this hook, for the diagnostic lines. */
    public String version() {
        Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return found == null ? "?" : found.getDescription().getVersion();
    }

    // ------------------------------------------------------------ homes/warps

    /**
     * A player's own homes, newest storage order preserved.
     *
     * <p>The future completes off the server thread — HuskHomes reads its database there — so
     * whatever the caller chains has to hop back before touching an inventory.
     */
    public CompletableFuture<List<SavedPoint>> homes(Player player) {
        OnlineUser user = api.adaptUser(player);
        return api.getUserHomes(user).thenApply(HuskHomesHook::homePoints);
    }

    /**
     * Every warp on the network.
     *
     * @param filterByPermission when true, warps the player has no {@code huskhomes.warp.<name>}
     *                           for are dropped. Leave it off unless the server actually runs
     *                           permission-restricted warps: the check falls back to the
     *                           {@code huskhomes.warp.*} wildcard, so on a server that does not
     *                           restrict anything it would hide every warp from everyone.
     */
    public CompletableFuture<List<SavedPoint>> warps(Player player, boolean filterByPermission) {
        OnlineUser user = api.adaptUser(player);
        return api.getWarps().thenApply(warps -> {
            List<SavedPoint> points = new ArrayList<>(warps.size());
            for (Warp warp : warps) {
                if (filterByPermission && !allowed(warp, user)) continue;
                points.add(point(warp, "", false));
            }
            return points;
        });
    }

    private static List<SavedPoint> homePoints(List<Home> homes) {
        List<SavedPoint> points = new ArrayList<>(homes.size());
        for (Home home : homes) {
            points.add(point(home, home.getOwner() == null ? "" : home.getOwner().getName(), home.isPublic()));
        }
        return points;
    }

    private static boolean allowed(Warp warp, OnlineUser user) {
        try {
            return warp.hasPermission(user);
        } catch (Throwable ignored) {
            // A build that moved the permission check must not blank the menu out.
            return true;
        }
    }

    private static SavedPoint point(SavedPosition position, String owner, boolean isPublic) {
        PositionMeta meta = position.getMeta();
        World world = position.getWorld();
        return new SavedPoint(
                position.getName(),
                meta == null || meta.getDescription() == null ? "" : meta.getDescription(),
                world == null || world.getName() == null ? "?" : world.getName(),
                position.getServer(),
                position.getX(), position.getY(), position.getZ(),
                owner == null ? "" : owner,
                isPublic);
    }

    // -------------------------------------------------------------- requests

    /** True when the player turned teleport requests off in HuskHomes ({@code /tpignore}). */
    public boolean isIgnoringRequests(Player player) {
        return requests().isIgnoringRequests(api.adaptUser(player));
    }

    /**
     * Accepts the request {@code requester} has waiting on {@code player}, if TPAuto may.
     *
     * <p>Called from the receive event, one tick after it fired: HuskHomes only queues the
     * request in the event's own callback, so answering during the event finds nothing.
     *
     * @return true when the request was accepted
     */
    public boolean accept(Player player, String requester) {
        OnlineUser user = api.adaptUser(player);
        Optional<TeleportRequest> found = requests().getTeleportRequest(requester, user);
        if (found.isEmpty()) return false;
        return accept(user, found.get());
    }

    /**
     * Accepts whatever is waiting on {@code player} right now — at most one request per call,
     * because that is all HuskHomes lets us see.
     *
     * @return the requester that was let through, or {@code null} when nothing was accepted
     */
    public String acceptPending(Player player) {
        OnlineUser user = api.adaptUser(player);
        Optional<TeleportRequest> found = requests().getLastTeleportRequest(user);
        if (found.isEmpty()) return null;
        TeleportRequest request = found.get();
        return accept(user, request) ? request.getRequesterName() : null;
    }

    /**
     * {@code /tpa} only, never {@code /tpahere}.
     *
     * <p>The two point in opposite directions: accepting a TPA brings the other player to you,
     * accepting a TPA_HERE takes <em>you</em> to them. TPAuto is a switch that says "people may
     * come to me" — answering a TPA_HERE for somebody would teleport them away without them
     * having agreed to anything, which is not a thing an automatic yes gets to do.
     *
     * <p>Matched positively on {@code TPA} rather than by excluding {@code TPA_HERE}, so a future
     * HuskHomes request type is ignored by default instead of accepted by accident.
     */
    private boolean accept(OnlineUser recipient, TeleportRequest request) {
        if (request.hasExpired()) return false;
        if (request.getStatus() != TeleportRequest.Status.PENDING) return false;
        if (request.getType() != TeleportRequest.Type.TPA) return false;

        String requester = request.getRequesterName();
        if (requester == null || !claim(recipient.getUuid(), requester)) return false;

        requests().respondToTeleportRequestBySenderName(recipient, requester, true);
        return true;
    }

    /** What the hook can see for one player, without accepting anything. */
    public record Diagnosis(String requester, String type, String status, boolean expired, boolean ignoring) {}

    /** Reads what {@link #acceptPending} reads, and accepts nothing — for {@code /tpauto status}. */
    public Diagnosis inspect(Player player) {
        OnlineUser user = api.adaptUser(player);
        boolean ignoring = requests().isIgnoringRequests(user);
        Optional<TeleportRequest> found = requests().getLastTeleportRequest(user);
        if (found.isEmpty()) return new Diagnosis(null, null, null, false, ignoring);

        TeleportRequest request = found.get();
        return new Diagnosis(
                request.getRequesterName(),
                request.getType() == null ? "?" : request.getType().name(),
                request.getStatus() == null ? "?" : request.getStatus().name(),
                request.hasExpired(),
                ignoring);
    }

    /** Forgets a player's accepted requests — they logged out, nothing is pending any more. */
    public void forget(UUID playerId) {
        String prefix = playerId + ":";
        handled.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private RequestsManager requests() {
        return plugin.getManager().requests();
    }

    /**
     * True the first time this requester is seen for this player in the last few seconds.
     *
     * <p>The event and the sweep can both reach the same request — the event fires, we answer a
     * tick later, and a sweep in between sees it still queued. Only one of them may go through.
     */
    private boolean claim(UUID recipient, String requester) {
        long now = System.currentTimeMillis();
        handled.values().removeIf(when -> now - when > ACCEPT_COOLDOWN_MS);

        Long previous = handled.putIfAbsent(recipient + ":" + requester, now);
        if (previous == null) return true;
        if (now - previous <= ACCEPT_COOLDOWN_MS) return false;

        handled.put(recipient + ":" + requester, now);
        return true;
    }
}
