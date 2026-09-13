package dev.lorenzzz.smplibrary.modules.tpauto;

import net.william278.huskhomes.event.ReceiveTeleportRequestEvent;
import net.william278.huskhomes.teleport.TeleportRequest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Turns HuskHomes' "somebody just asked you for a teleport" into a call back into
 * {@link TpAutoModule}.
 *
 * <p>Second of the two classes that name {@code net.william278.huskhomes.*} — registering a
 * listener resolves its event types, so this one is only ever constructed once HuskHomes is
 * confirmed present. The module itself stays free of them.
 *
 * <h2>Why it does not accept here and now</h2>
 * {@code RequestsManager#sendLocalTeleportRequest} fires this event <em>before</em> queueing the
 * request — the queueing happens in the event's own not-cancelled callback. Answering from
 * inside the handler would look for a request that does not exist yet, so the module is handed
 * the requester's name and schedules the answer for the next tick.
 */
final class TpAutoRequestListener implements Listener {

    /** Called with (recipient id, requester name) once a request has landed. */
    private final BiConsumer<UUID, String> onRequest;

    TpAutoRequestListener(BiConsumer<UUID, String> onRequest) {
        this.onRequest = onRequest;
    }

    /**
     * MONITOR: whatever else on the server wants to cancel a teleport request gets to decide
     * first, and a cancelled one is never queued — accepting it would resurrect it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onReceive(ReceiveTeleportRequestEvent event) {
        // The player has /tpignore on: HuskHomes marks the request IGNORED and drops it.
        if (event.isIgnored()) return;

        TeleportRequest request = event.getRequest();
        UUID recipient = event.getRecipient().getUuid();
        if (recipient == null || request.getRequesterName() == null) return;

        onRequest.accept(recipient, request.getRequesterName());
    }
}
