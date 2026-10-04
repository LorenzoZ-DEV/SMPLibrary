package dev.lorenzzz.smplibrary.modules.adaptive;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import dev.lorenzzz.smplibrary.modules.UseBatcher;
import dev.lorenzzz.smplibrary.text.Text;
import dev.lorenzzz.smplibrary.util.Log;
import dev.lorenzzz.smplibrary.util.SoundCue;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One morphing tool. The key timing — ported from a known-good reference: morph on
 * {@link BlockDamageEvent} (the instant the player starts mining) and on
 * {@link PrePlayerAttackEntityEvent} (before the hit lands), NOT on break/damage-completion.
 * That way the break itself runs with the right tool tier (speed + silk/fortune) and the sword
 * form's damage attributes apply to the hit. A use is spent on {@link BlockBreakEvent} and on a
 * confirmed attack ({@link PrePlayerAttackEntityEvent#willAttack()}); "shatters" at zero.
 * Admin give via {@code /smplibrary adaptive give [player]}.
 *
 * <p>Everything the event handlers need is cached into fields at enable time — the handlers run
 * on every block break, and re-reading the config section there showed up in profiling.
 */
public final class AdaptiveToolModule extends AbstractModule implements Listener {

    private static final String TOOL_ID = "adaptive";
    private static final String SWORD = "sword";
    private static final SoundCue DEFAULT_MORPH_SOUND = SoundCue.of("entity.ender_dragon.flap", 0.6f, 1.5f);
    private static final SoundCue SHATTER_SOUND = SoundCue.of("entity.ender_dragon.death", 0.4f, 1.8f);

    // DRAGON_BREATH takes a float for the power of the breath, so both of these carry one.
    // Passing no data at all is what threw "missing required data class java.lang.Float" out of
    // the block-damage handler on every hit.
    private static final ParticleSpec DEFAULT_PARTICLE =
            new ParticleSpec(Particle.DRAGON_BREATH, 20, 0.5, 1.0, 0.5, 0.05, 1.0f);
    private static final ParticleSpec SHATTER_PARTICLE =
            new ParticleSpec(Particle.DRAGON_BREATH, 60, 0.6, 1.0, 0.6, 0.08, 1.0f);

    /** A configured form: the material it morphs into plus its own enchantment block. */
    private record Form(Material material, ConfigurationSection section) {}

    /**
     * A configured particle, plus the extra object its type requires.
     *
     * <p>{@code data} is null for the majority of particles, which take none. Bukkit's
     * {@code spawnParticle} overload without a data argument throws
     * {@code IllegalArgumentException: missing required data} for the ones that do need it, so
     * the two cases take different overloads at spawn time.
     */
    private record ParticleSpec(Particle particle, int count, double offsetX, double offsetY,
                                double offsetZ, double speed, Object data) {}

    private final NamespacedKey usesKey;
    private final NamespacedKey maxKey;
    private final NamespacedKey toolKey;
    private final NamespacedKey formKey;

    // Mining breaks come in far faster than once a second, and every write to the held stack
    // costs the client its block-breaking progress. See UseBatcher.
    private final UseBatcher uses = new UseBatcher(1000L);

    // --- config cache, rebuilt on every enable ---
    private final Map<String, Form> forms = new LinkedHashMap<>();
    private boolean enabled;
    private String defaultForm = "pickaxe";
    private int maxUses = 1500;
    private String nameFormat = "";
    private List<String> loreFormat = List.of();
    private ParticleSpec morphParticle = DEFAULT_PARTICLE;
    private SoundCue morphSound = DEFAULT_MORPH_SOUND;

    public AdaptiveToolModule(Smp_library plugin) {
        super(plugin);
        this.usesKey = new NamespacedKey(plugin, "adaptive_uses");
        this.maxKey = new NamespacedKey(plugin, "adaptive_max");
        this.toolKey = new NamespacedKey(plugin, "adaptive_tool");
        this.formKey = new NamespacedKey(plugin, "adaptive_form");
    }

    @Override public String id() { return "adaptive-tool"; }

    @Override
    public void onEnable() {
        loadConfigCache();
        listen(this);
    }

    @Override
    protected void onShutdown() {
        forms.clear();
        uses.clear();
        enabled = false;
    }

    // ----------------------------------------------------------------- config

    private void loadConfigCache() {
        forms.clear();

        ConfigurationSection cfg = config();
        enabled = cfg != null;
        if (cfg == null) return;

        maxUses = Math.max(1, cfg.getInt("max-uses", 1500));
        nameFormat = cfg.getString("name", "");
        loreFormat = cfg.getStringList("lore");
        defaultForm = cfg.getString("default-form", "pickaxe").toLowerCase(Locale.ROOT);

        loadForms(cfg.getConfigurationSection("forms"));
        // Falls back to the constant, never to the current field: the runtime guard below can
        // set that to null, and a reload has to be able to recover from it.
        morphParticle = loadParticle(cfg.getConfigurationSection("particles"), DEFAULT_PARTICLE);
        morphSound = SoundCue.from(cfg.getConfigurationSection("sound"), DEFAULT_MORPH_SOUND);
    }

    private void loadForms(ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection form = section.getConfigurationSection(key);
            if (form == null) continue;
            Material material = Material.matchMaterial(form.getString("material", ""));
            if (material == null || material.isAir()) {
                Log.warning("adaptive-tool: bad material for form '" + key + "', skipping.");
                continue;
            }
            forms.put(key.toLowerCase(Locale.ROOT), new Form(material, form));
        }
    }

    private static ParticleSpec loadParticle(ConfigurationSection section, ParticleSpec fallback) {
        if (section == null) return fallback;
        Particle particle = fallback.particle();
        try {
            particle = Particle.valueOf(section.getString("type", particle.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Log.warning("adaptive-tool: unknown particle '" + section.getString("type") + "', keeping default.");
        }

        // Resolve the extra data here, not at spawn time. A particle that needs data and does
        // not get it throws from inside the BlockDamageEvent handler — once per block hit — so
        // an unusable configuration has to be caught while we can still fall back quietly.
        Object data;
        try {
            data = particleData(particle, section);
        } catch (UnsupportedOperationException unsupported) {
            Log.warning("adaptive-tool: " + unsupported.getMessage()
                    + " Falling back to " + fallback.particle().name() + ".");
            particle = fallback.particle();
            data = fallback.data();
        }

        return new ParticleSpec(particle,
                section.getInt("count", fallback.count()),
                section.getDouble("offset-x", fallback.offsetX()),
                section.getDouble("offset-y", fallback.offsetY()),
                section.getDouble("offset-z", fallback.offsetZ()),
                section.getDouble("speed", fallback.speed()),
                data);
    }

    /**
     * Builds the extra object {@code particle} needs, reading the optional keys that go with it.
     *
     * <p>Returns null for the particles that take no data — the common case. Throws
     * {@link UnsupportedOperationException} for the handful whose data this module has no
     * sensible way to configure (vibrations need a destination, trails need a target), which
     * the caller turns into a warning plus a fallback.
     */
    private static Object particleData(Particle particle, ConfigurationSection section) {
        Class<?> type = particle.getDataType();
        if (type == Void.class) return null;

        if (type == Float.class) {
            // Two particles want a float and mean different things by it: DRAGON_BREATH reads
            // it as the power of the breath, where 0 draws nothing, while SCULK_CHARGE reads it
            // as a roll angle in radians, where 0 is the natural value.
            double sane = particle == Particle.SCULK_CHARGE ? 0.0 : 1.0;
            return (float) section.getDouble("data", sane);
        }
        if (type == Integer.class) {
            // SHRIEK's delay, in ticks.
            return section.getInt("data", 0);
        }
        if (type == Color.class) {
            return colorOf(section.getString("color"), Color.FUCHSIA);
        }
        if (type == Particle.DustOptions.class) {
            return new Particle.DustOptions(
                    colorOf(section.getString("color"), Color.FUCHSIA),
                    (float) section.getDouble("size", 1.0));
        }
        if (type == Particle.DustTransition.class) {
            return new Particle.DustTransition(
                    colorOf(section.getString("color"), Color.FUCHSIA),
                    colorOf(section.getString("to-color"), Color.AQUA),
                    (float) section.getDouble("size", 1.0));
        }
        if (type == BlockData.class) {
            Material material = Material.matchMaterial(section.getString("block", "STONE"));
            if (material == null || !material.isBlock()) {
                throw new UnsupportedOperationException("particle " + particle.name()
                        + " needs a valid 'block:' material, got '" + section.getString("block") + "'.");
            }
            return material.createBlockData();
        }
        if (type == ItemStack.class) {
            Material material = Material.matchMaterial(section.getString("item", "STONE"));
            if (material == null || material.isAir()) {
                throw new UnsupportedOperationException("particle " + particle.name()
                        + " needs a valid 'item:' material, got '" + section.getString("item") + "'.");
            }
            return new ItemStack(material);
        }

        throw new UnsupportedOperationException("particle " + particle.name() + " requires "
                + type.getSimpleName() + " data, which this module cannot build from config.");
    }

    /** {@code #rrggbb} or {@code rrggbb}; the fallback keeps a typo from killing the config. */
    private static Color colorOf(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            return Color.fromRGB(Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16));
        } catch (IllegalArgumentException bad) {
            Log.warning("adaptive-tool: bad particle colour '" + hex + "', using the default.");
            return fallback;
        }
    }

    // ------------------------------------------------------------------ build

    /** Build the adaptive tool in its default form, or {@code null} if unconfigured. */
    public ItemStack buildAdaptive() {
        Form form = forms.get(defaultForm);
        if (!enabled || form == null) {
            Log.warning("adaptive-tool: no forms configured or disabled.");
            return null;
        }

        ItemStack item = new ItemStack(form.material());
        ItemMeta meta = item.getItemMeta();
        writeForm(meta, defaultForm, form, maxUses, maxUses);
        item.setItemMeta(meta);
        return item;
    }

    /** Writes name, lore, enchantments, flags and the PDC block for {@code formName} onto {@code meta}. */
    private void writeForm(ItemMeta meta, String formName, Form form, int uses, int max) {
        // Clear the previous form's enchants so stale slots don't leak across morphs.
        meta.getEnchants().keySet().forEach(meta::removeEnchant);
        ToolMeta.applyEnchantments(meta, form.section().getConfigurationSection("enchantments"));
        ToolMeta.seal(meta, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        meta.displayName(Text.legacy(nameFormat.replace("{form}", formName)));
        meta.lore(ToolMeta.lore(loreFormat, Map.of(
                "{uses}", String.valueOf(uses),
                "{max}", String.valueOf(max),
                "{form}", formName)));

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(toolKey, PersistentDataType.STRING, TOOL_ID);
        pdc.set(formKey, PersistentDataType.STRING, formName);
        pdc.set(usesKey, PersistentDataType.INTEGER, uses);
        pdc.set(maxKey, PersistentDataType.INTEGER, max);
    }

    // --------------------------------------------------------------- handlers

    /** Morph the instant the player STARTS mining so the break runs with that tool. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        morphFor(event.getPlayer(), event.getBlock().getType(), false);
    }

    /**
     * Safety net: if the damage-time morph was missed (or the tool was stuck in a stale
     * form), morph before the break is processed so the break itself runs with the right
     * tool (speed + silk/fortune). No-op when the tool is already the right form.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreakMorph(BlockBreakEvent event) {
        morphFor(event.getPlayer(), event.getBlock().getType(), true);
    }

    /** A completed break spends one use (the tool is already the right form). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!enabled || player.getGameMode() == GameMode.CREATIVE) return;

        ItemStack tool = heldAdaptive(player);
        if (tool != null) spendUse(player, tool, player.getInventory().getHeldItemSlot(), false);
    }

    /**
     * Uses booked while mining belong to the stack that earned them, so they are written out
     * before that stack can leave the hand.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(PlayerItemHeldEvent event) {
        flushBooked(event.getPlayer(), event.getPreviousSlot());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        flushBooked(event.getPlayer(), event.getPlayer().getInventory().getHeldItemSlot());
    }

    /** Morph to sword before the hit lands (sword damage attrs apply); spend a use only if it connects. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        Player player = event.getPlayer();
        if (!enabled) return;

        ItemStack tool = heldAdaptive(player);
        if (tool == null) return;

        morph(player, tool, SWORD);
        if (event.willAttack()) {
            // The morph replaced the held stack — spend the use on the item now in hand. Hits do
            // not come in fast enough to be worth batching, and the item was just rewritten
            // anyway, so this write goes through immediately together with anything booked.
            ItemStack held = heldAdaptive(player);
            if (held != null) spendUse(player, held, player.getInventory().getHeldItemSlot(), true);
        }
    }

    private void morphFor(Player player, Material block, boolean skipCreative) {
        if (!enabled) return;
        if (skipCreative && player.getGameMode() == GameMode.CREATIVE) return;

        ItemStack tool = heldAdaptive(player);
        if (tool == null) return;

        String desired = bestFormFor(block);
        if (desired != null) morph(player, tool, desired);
    }

    /** The held item if it is an adaptive tool, else {@code null}. */
    private ItemStack heldAdaptive(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) return null;
        return ToolMeta.hasTag(item.getItemMeta(), toolKey, TOOL_ID) ? item : null;
    }

    /** Pick the configured form matching the block's mineable tag (cobweb/bamboo → sword), else null. */
    private String bestFormFor(Material type) {
        if (Tag.MINEABLE_PICKAXE.isTagged(type) && forms.containsKey("pickaxe")) return "pickaxe";
        if (Tag.MINEABLE_AXE.isTagged(type) && forms.containsKey("axe")) return "axe";
        if (Tag.MINEABLE_SHOVEL.isTagged(type) && forms.containsKey("shovel")) return "shovel";
        if (Tag.MINEABLE_HOE.isTagged(type) && forms.containsKey("hoe")) return "hoe";
        if ((type == Material.COBWEB || type == Material.BAMBOO) && forms.containsKey(SWORD)) return SWORD;
        return null;
    }

    /**
     * Swap the held tool into {@code desired} form in place (same use budget: new material +
     * that form's enchants + lore). Plays particles + sound and sends the morphed message.
     * No-op if the tool already is that form.
     */
    private void morph(Player player, ItemStack item, String desired) {
        Form form = forms.get(desired);
        if (form == null) return;

        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String current = pdc.getOrDefault(formKey, PersistentDataType.STRING, "");
        // Already the right form AND the held material matches it: nothing to do. If the
        // material disagrees with the PDC (stale state from a partial morph or a config
        // change), fall through so the tool converges to the correct form.
        if (desired.equals(current) && item.getType() == form.material()) return;

        int uses = pdc.getOrDefault(usesKey, PersistentDataType.INTEGER, 0);
        int max = pdc.getOrDefault(maxKey, PersistentDataType.INTEGER, uses);

        // Build the morphed stack completely, then hand it over — writing meta into a stack
        // already placed in the inventory does not propagate back to the live item.
        ItemStack morphed = item.withType(form.material());
        ItemMeta meta = morphed.getItemMeta();
        writeForm(meta, desired, form, uses, max);
        morphed.setItemMeta(meta);
        player.getInventory().setItemInMainHand(morphed);

        playMorph(player);
        if (messages != null) messages.sendBoth(player, "adaptive-tool.morphed", Map.of("{form}", desired));
    }

    /**
     * Books one use against the tool in {@code slot} and writes the counter when the batcher
     * lets it through — every write costs the miner their break progress, so breaks batch.
     *
     * @param immediate write now, taking any booked uses along (attacks, slot changes)
     */
    private void spendUse(Player player, ItemStack item, int slot, boolean immediate) {
        UUID id = player.getUniqueId();
        int written = item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(usesKey, PersistentDataType.INTEGER, 0);

        int spent = immediate ? 1 + uses.drain(id) : uses.spend(id, written);
        if (spent > 0) applyUses(player, item, slot, spent);
    }

    /** Writes out uses booked earlier, if the tool that earned them is still in {@code slot}. */
    private void flushBooked(Player player, int slot) {
        UUID id = player.getUniqueId();
        if (uses.peek(id) == 0) return;

        ItemStack item = player.getInventory().getItem(slot);
        int booked = uses.drain(id);
        // Not the tool any more: whatever was booked went with it rather than onto a stack that
        // never earned it. A batch is worth a second of mining — nobody notices, nobody duplicates.
        if (item == null || item.getType().isAir()) return;
        if (!ToolMeta.hasTag(item.getItemMeta(), toolKey, TOOL_ID)) return;

        applyUses(player, item, slot, booked);
    }

    /** Subtracts {@code spent} uses from the tool in {@code slot} and refreshes its lore. */
    private void applyUses(Player player, ItemStack item, int slot, int spent) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        int remaining = pdc.getOrDefault(usesKey, PersistentDataType.INTEGER, 0) - spent;
        int max = pdc.getOrDefault(maxKey, PersistentDataType.INTEGER, remaining + spent);
        String form = pdc.getOrDefault(formKey, PersistentDataType.STRING, defaultForm);

        if (remaining <= 0) {
            uses.forget(player.getUniqueId());
            shatter(player, slot);
            return;
        }

        pdc.set(usesKey, PersistentDataType.INTEGER, remaining);
        meta.lore(ToolMeta.lore(loreFormat, Map.of(
                "{uses}", String.valueOf(remaining),
                "{max}", String.valueOf(max),
                "{form}", form)));
        item.setItemMeta(meta);
        player.getInventory().setItem(slot, item);
    }

    private void shatter(Player player, int slot) {
        player.getInventory().setItem(slot, null);
        Location loc = player.getLocation().add(0, 1, 0);
        spawn(player.getWorld(), loc, SHATTER_PARTICLE);
        SHATTER_SOUND.play(player);
        if (messages != null) messages.sendBoth(player, "adaptive-tool.destroyed", Map.of());
    }

    private void playMorph(Player player) {
        Location loc = player.getLocation().add(0, 1, 0);
        spawnMorphParticle(player.getWorld(), loc);
        morphSound.playAt(loc);
    }

    /**
     * Spawns the configured morph particle, and gives up on it if the server refuses.
     *
     * <p>The load-time check catches configurations we can reason about, but particle data
     * requirements shift between versions and forks, so a rejection at spawn time is still
     * possible. This runs from a {@link BlockDamageEvent} handler: left to throw, it would take
     * down the handler and log a stack trace on every single block hit. Disabling the particle
     * after the first failure keeps the tool working and the console readable — a reload picks
     * the setting back up.
     */
    private void spawnMorphParticle(World world, Location loc) {
        ParticleSpec spec = morphParticle;
        if (spec != null && !spawn(world, loc, spec)) morphParticle = null;
    }

    /** Spawns one spec, picking the overload its data requirement needs. False if refused. */
    private static boolean spawn(World world, Location loc, ParticleSpec spec) {
        try {
            if (spec.data() == null) {
                world.spawnParticle(spec.particle(), loc, spec.count(),
                        spec.offsetX(), spec.offsetY(), spec.offsetZ(), spec.speed());
            } else {
                world.spawnParticle(spec.particle(), loc, spec.count(),
                        spec.offsetX(), spec.offsetY(), spec.offsetZ(), spec.speed(), spec.data());
            }
            return true;
        } catch (RuntimeException rejected) {
            Log.warning("adaptive-tool: the server rejected particle " + spec.particle().name()
                    + " (" + rejected.getMessage() + "). Not drawing it again until the next"
                    + " reload; check MODULES.adaptive-tool.particles in config.yml.");
            return false;
        }
    }
}
