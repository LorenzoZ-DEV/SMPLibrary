package dev.lorenzzz.smplibrary.modules.lootguard;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Log;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps dropped loot from being destroyed by crystal-PvP explosions.
 *
 * <p>Vanilla lets an explosion damage item entities, and an item has so little health that one
 * blast deletes it. In a crystal fight that means the loser's drops are usually gone before
 * anybody can pick them up: the same crystals that made the kill blow the kill away.
 *
 * <p>This module cancels that damage — and only that damage. The explosion still happens, still
 * hurts players, still breaks blocks, still throws the items around; the items simply survive it.
 * Every other way an item can be lost (lava, fire, cactus, despawn, the void) is untouched, which
 * is why the fix is a cancelled damage event and not {@code setInvulnerable}.
 *
 * <h2>Telling the explosions apart</h2>
 * Only the sources listed under {@code sources} are covered — end crystals and respawn anchors out
 * of the box, so TNT cannons and creeper holes keep behaving the way the server expects.
 *
 * <p>Which of them is exploding takes more than one question, because the two arrive at the item
 * differently. A crystal is an entity, so it is the damager of an
 * {@link EntityDamageByEntityEvent}. An anchor is a block that <em>no longer exists</em> by the
 * time the blast lands — it is consumed as it detonates — so the damager block is usually air
 * already. Hence {@link #sourceKeys}: it gathers every name the event can offer (damager entity,
 * damager block, the block state Paper kept from before the break, and the damage type) and a
 * match on any one of them is enough. {@code bad_respawn_point}, the damage type behind both
 * anchors and beds, is the one that reliably survives that block disappearing.
 */
public final class LootGuardModule extends AbstractModule implements Listener {

    /**
     * What is protected by default: end crystals, and respawn anchors under both the names an
     * anchor blast can present itself with.
     *
     * <p>{@code bad_respawn_point} also covers beds detonating in the Nether and the End — the
     * same mechanic, used the same way in a fight. Drop it from the list to protect against
     * anchors only.
     */
    private static final List<String> DEFAULT_SOURCES =
            List.of("END_CRYSTAL", "RESPAWN_ANCHOR", "BAD_RESPAWN_POINT");

    /** Lower-cased {@code sources}, matched against the keys of whatever caused the damage. */
    private Set<String> sources = normalize(DEFAULT_SOURCES);

    private boolean protectExperience = true;

    public LootGuardModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "lootguard"; }

    @Override
    public Map<String, Object> defaults() {
        return Map.of(
                "enabled", true,
                "sources", DEFAULT_SOURCES,
                "protect-experience", true
        );
    }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();
        if (cfg != null) {
            List<String> configured = cfg.getStringList("sources");
            if (!configured.isEmpty()) sources = normalize(configured);
            protectExperience = cfg.getBoolean("protect-experience", true);
        }

        listen(this);
        Log.info("LootGuard enabled — il loot sopravvive a: " + String.join(", ", sources)
                + (protectExperience ? " (anche le sfere di esperienza)." : "."));
    }

    /**
     * An explosion is about to delete a dropped item: let the blast happen, but not to this.
     *
     * <p>HIGH rather than HIGHEST: a plugin that deliberately wants the drop gone — an arena
     * clearing itself between rounds, say — still gets the last word at HIGHEST or MONITOR.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!isExplosion(event.getCause())) return;
        if (!isLoot(event.getEntity())) return;
        if (!matches(event)) return;

        event.setCancelled(true);
    }

    private static boolean isExplosion(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
    }

    private boolean isLoot(Entity entity) {
        return entity instanceof Item || (protectExperience && entity instanceof ExperienceOrb);
    }

    private boolean matches(EntityDamageEvent event) {
        for (String key : sourceKeys(event)) {
            if (sources.contains(key)) return true;
        }
        return false;
    }

    /**
     * Every name this damage can be recognised by, all lower-cased the way namespaced keys are:
     * {@code end_crystal}, {@code respawn_anchor}, {@code bad_respawn_point}, {@code tnt}…
     *
     * <p>Four questions rather than one because no single one of them answers for both an
     * exploding entity and an exploding block that has already removed itself.
     */
    private static Set<String> sourceKeys(EntityDamageEvent event) {
        Set<String> keys = new LinkedHashSet<>(4);

        DamageSource source = event.getDamageSource();
        if (source != null) {
            DamageType type = source.getDamageType();
            if (type != null) keys.add(type.getKey().getKey());
            // The crystal itself, when the blow is credited to the player who shot it.
            Entity direct = source.getDirectEntity();
            if (direct != null) keys.add(direct.getType().getKey().getKey());
        }

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager != null) keys.add(damager.getType().getKey().getKey());
        }

        if (event instanceof EntityDamageByBlockEvent byBlock) {
            // Paper keeps the state the block had before it was destroyed — the only place an
            // anchor still says "respawn_anchor" instead of "air".
            BlockState state = byBlock.getDamagerBlockState();
            if (state != null) keys.add(state.getType().getKey().getKey());
            Block block = byBlock.getDamager();
            if (block != null) keys.add(block.getType().getKey().getKey());
        }

        return keys;
    }

    private static Set<String> normalize(List<String> configured) {
        Set<String> out = new LinkedHashSet<>(configured.size());
        for (String value : configured) {
            if (value == null || value.isBlank()) continue;
            // Written as END_CRYSTAL in the config, compared as the end_crystal of a namespaced
            // key — and "minecraft:end_crystal", which is how the wiki spells it, works too.
            String key = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            int colon = key.indexOf(':');
            out.add(colon < 0 ? key : key.substring(colon + 1));
        }
        return out;
    }
}
