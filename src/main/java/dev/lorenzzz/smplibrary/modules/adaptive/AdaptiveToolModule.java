package dev.lorenzzz.smplibrary.modules.adaptive;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.ToolMeta;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
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
 */
public final class AdaptiveToolModule implements Module, Listener {

    private static final String TOOL_ID = "adaptive";

    private final JavaPlugin plugin;
    private MessagesManager messages;

    private final NamespacedKey K_USES;
    private final NamespacedKey K_MAX;
    private final NamespacedKey K_TOOL;
    private final NamespacedKey K_FORM;

    // --- CACHE VARIABLES (Ottimizzazione Spark) ---
    private boolean enabled = false;
    private String defaultForm = "pickaxe";
    private int maxUses = 1500;
    private String displayNameFormat = "";
    private List<String> loreFormat = new ArrayList<>();

    private final Map<String, Material> formMaterials = new HashMap<>();
    private final Map<String, ConfigurationSection> formConfigs = new HashMap<>();

    private Particle cachedParticle = Particle.DRAGON_BREATH;
    private int particleCount = 20;
    private double pOffsetX = 0.5, pOffsetY = 1.0, pOffsetZ = 0.5, pSpeed = 0.05;

    private Sound cachedSound = Sound.ENTITY_ENDER_DRAGON_FLAP;
    private float soundVolume = 0.6f, soundPitch = 1.5f;

    public AdaptiveToolModule(JavaPlugin plugin) {
        this.plugin = plugin;
        K_USES = new NamespacedKey(plugin, "adaptive_uses");
        K_MAX  = new NamespacedKey(plugin, "adaptive_max");
        K_TOOL = new NamespacedKey(plugin, "adaptive_tool");
        K_FORM = new NamespacedKey(plugin, "adaptive_form");
    }

    @Override public String id() { return "adaptive-tool"; }

    @Override public void onEnable() {
        loadConfigCache(); // Carica i dati in RAM una sola volta
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Carica tutte le impostazioni del config in variabili Java per non leggere dal disco ad ogni evento */
    private void loadConfigCache() {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("MODULES.adaptive-tool");
        if (root == null) {
            enabled = false;
            return;
        }
        enabled = true;
        maxUses = Math.max(1, root.getInt("max-uses", 1500));
        displayNameFormat = root.getString("name", "");
        loreFormat = root.getStringList("lore");
        defaultForm = root.getString("default-form", "pickaxe").toLowerCase(Locale.ROOT);

        formMaterials.clear();
        formConfigs.clear();
        ConfigurationSection forms = root.getConfigurationSection("forms");
        if (forms != null) {
            for (String key : forms.getKeys(false)) {
                ConfigurationSection fc = forms.getConfigurationSection(key);
                if (fc != null) {
                    formConfigs.put(key.toLowerCase(Locale.ROOT), fc);
                    Material mat = Material.matchMaterial(fc.getString("material", ""));
                    if (mat != null && !mat.isAir()) {
                        formMaterials.put(key.toLowerCase(Locale.ROOT), mat);
                    }
                }
            }
        }

        // Cache Particles
        ConfigurationSection p = root.getConfigurationSection("particles");
        if (p != null) {
            try {
                cachedParticle = Particle.valueOf(p.getString("type", "DRAGON_BREATH").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
            particleCount = p.getInt("count", 20);
            pOffsetX = p.getDouble("offset-x", 0.5);
            pOffsetY = p.getDouble("offset-y", 1.0);
            pOffsetZ = p.getDouble("offset-z", 0.5);
            pSpeed = p.getDouble("speed", 0.05);
        }

        // Cache Sounds
        ConfigurationSection s = root.getConfigurationSection("sound");
        if (s != null) {
            String soundName = s.getString("type", "ENTITY_ENDER_DRAGON_FLAP").toLowerCase(Locale.ROOT).replace("_", ".");
            Sound sound = Registry.SOUND_EVENT.get(NamespacedKey.minecraft(soundName));
            if (sound != null) cachedSound = sound;
            soundVolume = (float) s.getDouble("volume", 0.6);
            soundPitch = (float) s.getDouble("pitch", 1.5);
        }
    }

    /** Build the adaptive tool in its default form, or {@code null} if unconfigured. */
    public ItemStack buildAdaptive() {
        if (!enabled || !formConfigs.containsKey(defaultForm)) {
            C.warning("adaptive-tool: no forms configured or disabled.");
            return null;
        }

        ItemStack item = new ItemStack(formMaterials.get(defaultForm));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(C.component(displayNameFormat.replace("{form}", defaultForm)));
        meta.lore(ToolMeta.lore(loreFormat,
                Map.of("{uses}", String.valueOf(maxUses), "{max}", String.valueOf(maxUses), "{form}", defaultForm)));
        ToolMeta.applyEnchantments(meta, formConfigs.get(defaultForm).getConfigurationSection("enchantments"));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(K_USES, PersistentDataType.INTEGER, maxUses);
        pdc.set(K_MAX,  PersistentDataType.INTEGER, maxUses);
        pdc.set(K_TOOL, PersistentDataType.STRING,  TOOL_ID);
        pdc.set(K_FORM, PersistentDataType.STRING,  defaultForm);
        item.setItemMeta(meta);
        return item;
    }

    /** Morph to the matching form the instant the player STARTS mining so the break runs with that tool. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!enabled) return;
        Player p = event.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        ItemMeta meta = tool.getItemMeta();
        if (!isAdaptive(meta)) return; // Fast exit using cached meta

        String desired = bestFormFor(event.getBlock().getType());
        if (desired == null) return;
        morphIfNeeded(p, tool, meta, desired);
    }

    /** A completed break spends one use (the tool is already the right form). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabled) return;
        Player p = event.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        ItemMeta meta = tool.getItemMeta();
        if (!isAdaptive(meta)) return;
        decrementUses(p, tool, meta);
    }

    /** Morph to sword before the hit lands (sword damage attrs apply); spend a use only if the attack connects. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!enabled) return;
        Player p = event.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        ItemMeta meta = tool.getItemMeta();
        if (!isAdaptive(meta)) return;

        morphIfNeeded(p, tool, meta, "sword");
        if (event.willAttack()) {
            // Re-get meta as morphIfNeeded might have changed it
            decrementUses(p, p.getInventory().getItemInMainHand(), p.getInventory().getItemInMainHand().getItemMeta());
        }
    }

    private boolean isAdaptive(ItemMeta meta) {
        if (meta == null) return false;
        return TOOL_ID.equals(meta.getPersistentDataContainer().get(K_TOOL, PersistentDataType.STRING));
    }

    /** Pick the configured form matching the block's mineable tag (cobweb/bamboo → sword), else null. */
    private String bestFormFor(Material type) {
        if (Tag.MINEABLE_PICKAXE.isTagged(type) && formConfigs.containsKey("pickaxe")) return "pickaxe";
        if (Tag.MINEABLE_AXE.isTagged(type)      && formConfigs.containsKey("axe"))      return "axe";
        if (Tag.MINEABLE_SHOVEL.isTagged(type)   && formConfigs.containsKey("shovel"))   return "shovel";
        if (Tag.MINEABLE_HOE.isTagged(type)      && formConfigs.containsKey("hoe"))       return "hoe";
        if ((type == Material.COBWEB || type == Material.BAMBOO) && formConfigs.containsKey("sword")) return "sword";
        return null;
    }

    /**
     * Swap the held tool into {@code desired} form in place (same stack: new material + that form's
     * enchants + lore). Clears the previous form's enchants first so stale slots don't leak across
     * morphs. Plays particles + sound + sends the morphed message. No-op if already that form.
     */
    private void morphIfNeeded(Player player, ItemStack item, ItemMeta meta, String desired) {
        if (desired == null) return;
        desired = desired.toLowerCase(Locale.ROOT);
        ConfigurationSection formSec = formConfigs.get(desired);
        if (formSec == null) return;

        var pdc = meta.getPersistentDataContainer();
        String current = pdc.getOrDefault(K_FORM, PersistentDataType.STRING, "");
        if (desired.equalsIgnoreCase(current)) return; // Già nella forma corretta, nessun calcolo extra

        Material newMat = formMaterials.get(desired);
        if (newMat == null || newMat.isAir()) return;

        int uses = pdc.getOrDefault(K_USES, PersistentDataType.INTEGER, 0);
        int max  = pdc.getOrDefault(K_MAX,  PersistentDataType.INTEGER, uses);

        item = item.withType(newMat);
        player.getInventory().setItemInMainHand(item);
        meta = item.getItemMeta();   // Re-read per il nuovo materiale

        // Rimuovi vecchi incantesimi in modo sicuro senza creare array inutili
        meta.getEnchants().keySet().forEach(meta::removeEnchant);

        ToolMeta.applyEnchantments(meta, formSec.getConfigurationSection("enchantments"));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.displayName(C.component(displayNameFormat.replace("{form}", desired)));
        meta.lore(ToolMeta.lore(loreFormat,
                Map.of("{uses}", String.valueOf(uses), "{max}", String.valueOf(max), "{form}", desired)));

        var npdc = meta.getPersistentDataContainer();
        npdc.set(K_TOOL, PersistentDataType.STRING, TOOL_ID);
        npdc.set(K_USES, PersistentDataType.INTEGER, uses);
        npdc.set(K_MAX,  PersistentDataType.INTEGER, max);
        npdc.set(K_FORM, PersistentDataType.STRING, desired);
        item.setItemMeta(meta);

        playMorph(player);
        if (messages != null) messages.sendBoth(player, "adaptive-tool.morphed", Map.of("{form}", desired));
    }

    private void decrementUses(Player player, ItemStack item, ItemMeta meta) {
        if (item == null || item.getType().isAir() || !isAdaptive(meta)) return;

        var pdc = meta.getPersistentDataContainer();
        int uses = pdc.getOrDefault(K_USES, PersistentDataType.INTEGER, 0) - 1;
        int max  = pdc.getOrDefault(K_MAX,  PersistentDataType.INTEGER, uses + 1);
        String form = pdc.getOrDefault(K_FORM, PersistentDataType.STRING, "pickaxe");

        if (uses <= 0) {
            player.getInventory().setItemInMainHand(null);
            Location loc = player.getLocation().add(0, 1, 0);
            player.getWorld().spawnParticle(Particle.DRAGON_BREATH, loc, 60, 0.6, 1.0, 0.6, 0.08);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, 0.4f, 1.8f);
            if (messages != null) messages.sendBoth(player, "adaptive-tool.destroyed", Map.of());
            return;
        }

        pdc.set(K_USES, PersistentDataType.INTEGER, uses);
        meta.lore(ToolMeta.lore(loreFormat,
                Map.of("{uses}", String.valueOf(uses), "{max}", String.valueOf(max), "{form}", form)));
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
    }

    private void playMorph(Player player) {
        Location loc = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();

        // Usa i dati cachati invece di leggerli dal config
        if (cachedParticle != null) {
            world.spawnParticle(cachedParticle, loc, particleCount, pOffsetX, pOffsetY, pOffsetZ, pSpeed);
        }
        if (cachedSound != null) {
            world.playSound(loc, cachedSound, soundVolume, soundPitch);
        }
    }

    @Override public Module storage(StorageManager storage) { return this; }
    @Override public Module commands(CommandManager commands) { return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }

    @Override public void onDisable() { HandlerList.unregisterAll(this); }
}