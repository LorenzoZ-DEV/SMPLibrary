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
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
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

    public AdaptiveToolModule(JavaPlugin plugin) {
        this.plugin = plugin;
        K_USES = new NamespacedKey(plugin, "adaptive_uses");
        K_MAX  = new NamespacedKey(plugin, "adaptive_max");
        K_TOOL = new NamespacedKey(plugin, "adaptive_tool");
        K_FORM = new NamespacedKey(plugin, "adaptive_form");
    }

    @Override public String id() { return "adaptive-tool"; }

    @Override public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("MODULES.adaptive-tool");
    }

    private boolean hasForm(ConfigurationSection forms, String form) {
        return forms != null && forms.isConfigurationSection(form);
    }

    /** Build the adaptive tool in its default form, or {@code null} if unconfigured. */
    public ItemStack buildAdaptive() {
        ConfigurationSection root = cfg();
        if (root == null) return null;
        ConfigurationSection forms = root.getConfigurationSection("forms");
        String def = root.getString("default-form", "pickaxe");
        if (!hasForm(forms, def)) def = forms == null ? null : forms.getKeys(false).stream().findFirst().orElse(null);
        if (def == null) { C.warning("adaptive-tool: no forms configured."); return null; }

        int max = Math.max(1, root.getInt("max-uses", 1500));
        ItemStack item = new ItemStack(materialOf(forms.getConfigurationSection(def)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(C.component(root.getString("name", "").replace("{form}", def)));
        meta.lore(ToolMeta.lore(root.getStringList("lore"),
                Map.of("{uses}", String.valueOf(max), "{max}", String.valueOf(max), "{form}", def)));
        ToolMeta.applyEnchantments(meta, forms.getConfigurationSection(def).getConfigurationSection("enchantments"));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(K_USES, PersistentDataType.INTEGER, max);
        pdc.set(K_MAX,  PersistentDataType.INTEGER, max);
        pdc.set(K_TOOL, PersistentDataType.STRING,  TOOL_ID);
        pdc.set(K_FORM, PersistentDataType.STRING,  def.toLowerCase(Locale.ROOT));
        item.setItemMeta(meta);
        return item;
    }

    /** Morph to the matching form the instant the player STARTS mining so the break runs with that tool. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player p = event.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isAdaptive(tool)) return;
        String desired = bestFormFor(event.getBlock());
        if (desired == null) return;
        morphIfNeeded(p, p.getInventory().getItemInMainHand(), desired);
    }

    /** A completed break spends one use (the tool is already the right form). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isAdaptive(tool)) return;
        decrementUses(p, tool);
    }

    /** Morph to sword before the hit lands (sword damage attrs apply); spend a use only if the attack connects. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        Player p = event.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isAdaptive(tool)) return;
        morphIfNeeded(p, tool, "sword");
        if (event.willAttack()) decrementUses(p, p.getInventory().getItemInMainHand());
    }

    private boolean isAdaptive(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String v = meta.getPersistentDataContainer().get(K_TOOL, PersistentDataType.STRING);
        return TOOL_ID.equals(v);
    }

    /** Pick the configured form matching the block's mineable tag (cobweb/bamboo → sword), else null. */
    private String bestFormFor(Block block) {
        ConfigurationSection root = cfg();
        ConfigurationSection forms = root == null ? null : root.getConfigurationSection("forms");
        Material type = block.getType();
        if (Tag.MINEABLE_PICKAXE.isTagged(type) && hasForm(forms, "pickaxe")) return "pickaxe";
        if (Tag.MINEABLE_AXE.isTagged(type)      && hasForm(forms, "axe"))      return "axe";
        if (Tag.MINEABLE_SHOVEL.isTagged(type)   && hasForm(forms, "shovel"))   return "shovel";
        if (Tag.MINEABLE_HOE.isTagged(type)      && hasForm(forms, "hoe"))       return "hoe";
        if ((type == Material.COBWEB || type == Material.BAMBOO) && hasForm(forms, "sword")) return "sword";
        return null;
    }

    /**
     * Swap the held tool into {@code desired} form in place (same stack: new material + that form's
     * enchants + lore). Clears the previous form's enchants first so stale slots don't leak across
     * morphs. Plays particles + sound + sends the morphed message. No-op if already that form.
     */
    private void morphIfNeeded(Player player, ItemStack item, String desired) {
        if (desired == null) return;
        ConfigurationSection root = cfg();
        if (root == null) return;
        ConfigurationSection forms = root.getConfigurationSection("forms");
        ConfigurationSection formSec = forms == null ? null : forms.getConfigurationSection(desired);
        if (formSec == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        String current = pdc.getOrDefault(K_FORM, PersistentDataType.STRING, "");
        if (desired.equalsIgnoreCase(current)) return;

        Material newMat = materialOf(formSec);
        if (newMat == null || newMat.isAir()) return;

        int uses = pdc.getOrDefault(K_USES, PersistentDataType.INTEGER, 0);
        int max  = pdc.getOrDefault(K_MAX,  PersistentDataType.INTEGER, uses);

        item.setType(newMat);
        meta = item.getItemMeta();   // re-read for the new material
        for (Enchantment e : new ArrayList<>(meta.getEnchants().keySet())) meta.removeEnchant(e);
        ToolMeta.applyEnchantments(meta, formSec.getConfigurationSection("enchantments"));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.displayName(C.component(root.getString("name", "").replace("{form}", desired)));
        meta.lore(ToolMeta.lore(root.getStringList("lore"),
                Map.of("{uses}", String.valueOf(uses), "{max}", String.valueOf(max), "{form}", desired)));

        var npdc = meta.getPersistentDataContainer();
        npdc.set(K_TOOL, PersistentDataType.STRING, TOOL_ID);
        npdc.set(K_USES, PersistentDataType.INTEGER, uses);
        npdc.set(K_MAX,  PersistentDataType.INTEGER, max);
        npdc.set(K_FORM, PersistentDataType.STRING, desired.toLowerCase(Locale.ROOT));
        item.setItemMeta(meta);

        playMorph(player);
        if (messages != null) messages.sendBoth(player, "adaptive-tool.morphed", Map.of("{form}", desired));
    }

    private void decrementUses(Player player, ItemStack item) {
        if (item == null || item.getType().isAir() || !isAdaptive(item)) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
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
        ConfigurationSection root = cfg();
        if (root != null) meta.lore(ToolMeta.lore(root.getStringList("lore"),
                Map.of("{uses}", String.valueOf(uses), "{max}", String.valueOf(max), "{form}", form)));
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
    }

    private void playMorph(Player player) {
        ConfigurationSection root = cfg();
        if (root == null) return;
        Location loc = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        ConfigurationSection p = root.getConfigurationSection("particles");
        if (p != null) {
            try {
                Particle type = Particle.valueOf(p.getString("type", "DRAGON_BREATH").toUpperCase(Locale.ROOT));
                world.spawnParticle(type, loc, p.getInt("count", 20),
                        p.getDouble("offset-x", 0.5), p.getDouble("offset-y", 1.0), p.getDouble("offset-z", 0.5),
                        p.getDouble("speed", 0.05));
            } catch (IllegalArgumentException ignored) { /* misconfigured particle — silent */ }
        }
        ConfigurationSection s = root.getConfigurationSection("sound");
        if (s != null) {
            try {
                Sound type = Sound.valueOf(s.getString("type", "ENTITY_ENDER_DRAGON_FLAP").toUpperCase(Locale.ROOT));
                world.playSound(loc, type, (float) s.getDouble("volume", 0.6), (float) s.getDouble("pitch", 1.5));
            } catch (IllegalArgumentException ignored) { /* misconfigured sound — silent */ }
        }
    }

    private Material materialOf(ConfigurationSection formSection) {
        if (formSection == null) return null;
        Material mat = Material.matchMaterial(formSection.getString("material", ""));
        return mat == null || mat.isAir() ? null : mat;
    }

    @Override public Module storage(StorageManager storage) { return this; }
    @Override public Module commands(CommandManager commands) { return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }

    @Override public void onDisable() {}
}