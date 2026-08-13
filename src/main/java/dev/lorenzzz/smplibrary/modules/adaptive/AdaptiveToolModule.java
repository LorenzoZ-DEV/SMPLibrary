package dev.lorenzzz.smplibrary.modules.adaptive;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import dev.lorenzzz.smplibrary.util.SoundCue;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** A configured form: the material it morphs into plus its own enchantment block. */
    private record Form(Material material, ConfigurationSection section) {}

    private record ParticleSpec(Particle particle, int count, double offsetX, double offsetY,
                                double offsetZ, double speed) {}

    private final NamespacedKey usesKey;
    private final NamespacedKey maxKey;
    private final NamespacedKey toolKey;
    private final NamespacedKey formKey;

    // --- config cache, rebuilt on every enable ---
    private final Map<String, Form> forms = new LinkedHashMap<>();
    private boolean enabled;
    private String defaultForm = "pickaxe";
    private int maxUses = 1500;
    private String nameFormat = "";
    private List<String> loreFormat = List.of();
    private ParticleSpec morphParticle = new ParticleSpec(Particle.DRAGON_BREATH, 20, 0.5, 1.0, 0.5, 0.05);
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
        morphParticle = loadParticle(cfg.getConfigurationSection("particles"), morphParticle);
        morphSound = SoundCue.from(cfg.getConfigurationSection("sound"), DEFAULT_MORPH_SOUND);
    }

    private void loadForms(ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection form = section.getConfigurationSection(key);
            if (form == null) continue;
            Material material = Material.matchMaterial(form.getString("material", ""));
            if (material == null || material.isAir()) {
                C.warning("adaptive-tool: bad material for form '" + key + "', skipping.");
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
            C.warning("adaptive-tool: unknown particle '" + section.getString("type") + "', keeping default.");
        }
        return new ParticleSpec(particle,
                section.getInt("count", fallback.count()),
                section.getDouble("offset-x", fallback.offsetX()),
                section.getDouble("offset-y", fallback.offsetY()),
                section.getDouble("offset-z", fallback.offsetZ()),
                section.getDouble("speed", fallback.speed()));
    }

    // ------------------------------------------------------------------ build

    /** Build the adaptive tool in its default form, or {@code null} if unconfigured. */
    public ItemStack buildAdaptive() {
        Form form = forms.get(defaultForm);
        if (!enabled || form == null) {
            C.warning("adaptive-tool: no forms configured or disabled.");
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

        meta.displayName(C.component(nameFormat.replace("{form}", formName)));
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
        if (tool != null) spendUse(player, tool);
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
            // The morph replaced the held stack — spend the use on the item now in hand.
            ItemStack held = heldAdaptive(player);
            if (held != null) spendUse(player, held);
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

    private void spendUse(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        int uses = pdc.getOrDefault(usesKey, PersistentDataType.INTEGER, 0) - 1;
        int max = pdc.getOrDefault(maxKey, PersistentDataType.INTEGER, uses + 1);
        String form = pdc.getOrDefault(formKey, PersistentDataType.STRING, defaultForm);

        if (uses <= 0) {
            shatter(player);
            return;
        }

        pdc.set(usesKey, PersistentDataType.INTEGER, uses);
        meta.lore(ToolMeta.lore(loreFormat, Map.of(
                "{uses}", String.valueOf(uses),
                "{max}", String.valueOf(max),
                "{form}", form)));
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
    }

    private void shatter(Player player) {
        player.getInventory().setItemInMainHand(null);
        Location loc = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.DRAGON_BREATH, loc, 60, 0.6, 1.0, 0.6, 0.08);
        SHATTER_SOUND.play(player);
        if (messages != null) messages.sendBoth(player, "adaptive-tool.destroyed", Map.of());
    }

    private void playMorph(Player player) {
        Location loc = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(morphParticle.particle(), loc, morphParticle.count(),
                morphParticle.offsetX(), morphParticle.offsetY(), morphParticle.offsetZ(), morphParticle.speed());
        morphSound.playAt(loc);
    }
}
