package dev.lorenzzz.smplibrary.modules.menu;

import dev.lorenzzz.smplibrary.C;
import net.j4c0b3y.api.menu.Menu;
import net.j4c0b3y.api.menu.MenuHandler;
import net.j4c0b3y.api.menu.MenuSize;
import net.j4c0b3y.api.menu.button.Button;
import net.j4c0b3y.api.menu.button.ButtonClick;
import net.j4c0b3y.api.menu.button.impl.SimpleButton;
import net.j4c0b3y.api.menu.layer.impl.BackgroundLayer;
import net.j4c0b3y.api.menu.layer.impl.ForegroundLayer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.stream.Collectors;

public class DynamicMenu extends Menu {

    private final MenuConfig config;
    private final MenuModule module;

    public DynamicMenu(MenuConfig config, Player player, MenuHandler handler, MenuModule module) {
        super(config.title(), MenuSize.of(config.size()), player, handler);
        this.config = config;
        this.module = module;
    }

    @Override
    public void setup(BackgroundLayer background, ForegroundLayer foreground) {
        if (config.fillItem() != null) {
            ItemStack filler = buildItem(config.fillItem().item());
            if (filler != null) {
                background.fill(new SimpleButton(filler, click -> {}));
            }
        }

        for (MenuItemConfig item : config.items()) {
            if (item.slot() < 0) continue;

            ItemStack icon = buildItem(item.item());
            if (icon == null) continue;

            Button button = new SimpleButton(icon, click -> handleClick(click, item));

            if (item.slot() >= getTotalSlots()) continue;
            int x = item.slot() % 9;
            int y = item.slot() / 9;
            foreground.set(x, y, button);
        }
    }

    private void handleClick(ButtonClick click, MenuItemConfig item) {
        Player player = click.getMenu().getPlayer();

        if (item.clickRequirement() != null) {
            handlePurchase(player, item);
            return;
        }

        if (item.commands() != null && !item.commands().isEmpty()) {
            for (String cmd : item.commands()) {
                Bukkit.dispatchCommand(player, cmd.replace("%player%", player.getName()).replace("%player_name%", player.getName()));
            }
            if (item.close() != null && item.close()) {
                player.closeInventory();
            }
            return;
        }

        if (item.consoleCommands() != null && !item.consoleCommands().isEmpty()) {
            for (String cmd : item.consoleCommands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()).replace("%player_name%", player.getName()));
            }
            if (item.close() != null && item.close()) {
                player.closeInventory();
            }
            return;
        }

        if (item.actions() != null && !item.actions().isEmpty()) {
            for (Map<String, String> action : item.actions()) {
                String type = action.get("type");
                if ("inventory".equals(type)) {
                    String inv = action.get("inventory");
                    module.openMenu(player, inv);
                } else if ("close".equals(type)) {
                    player.closeInventory();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePurchase(Player player, MenuItemConfig item) {
        Map<String, Object> cr = item.clickRequirement().purchase();
        if (cr == null) return;

        Map<String, Object> purchase = (Map<String, Object>) cr.get("purchase");
        if (purchase == null) purchase = cr;

        List<Map<String, Object>> requirements = (List<Map<String, Object>>) purchase.get("requirements");
        if (requirements == null) {
            runActions(player, (List<Map<String, Object>>) purchase.get("success"));
            return;
        }

        boolean passed = true;
        for (Map<String, Object> req : requirements) {
            String type = (String) req.get("type");
            if ("placeholder".equals(type)) {
                String placeholder = (String) req.get("placeholder");
                String valueStr = (String) req.get("value");
                if (placeholder != null && valueStr != null) {
                    double required = Double.parseDouble(valueStr);
                    double balance = getBalance(player, placeholder);
                    if (balance < required) {
                        passed = false;
                        break;
                    }
                }
            }
        }

        if (passed) {
            runActions(player, (List<Map<String, Object>>) purchase.get("success"));
        } else {
            runActions(player, (List<Map<String, Object>>) purchase.get("deny"));
        }
    }

    private double getBalance(Player player, String placeholder) {
        if (placeholder.contains("zessentials")) {
            return module.getBalance(player);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            String parsed = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
            try {
                return Double.parseDouble(parsed);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private void runActions(Player player, List<Map<String, Object>> actions) {
        if (actions == null) return;
        for (Map<String, Object> action : actions) {
            String type = (String) action.get("type");
            if (type == null) continue;

            switch (type) {
                case "console_command" -> {
                    List<String> cmds = (List<String>) action.get("commands");
                    if (cmds != null) {
                        for (String cmd : cmds) {
                            String parsed = cmd
                                    .replace("%player%", player.getName())
                                    .replace("%player_name%", player.getName());
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                        }
                    }
                }
                case "message" -> {
                    List<String> messages = (List<String>) action.get("messages");
                    if (messages != null) {
                        for (String msg : messages) {
                            player.sendMessage(C.parse(msg, player, Map.of()));
                        }
                    }
                }
                case "close" -> player.closeInventory();
                case "inventory" -> {
                    String inv = (String) action.get("inventory");
                    if (inv != null) module.openMenu(player, inv);
                }
            }
        }
    }

    public static ItemStack buildItem(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return null;

        String materialStr = (String) data.get("material");
        if (materialStr == null) return null;

        Material material = Material.getMaterial(materialStr.toUpperCase());
        if (material == null) material = Material.STONE;

        int amount = 1;
        if (data.containsKey("amount")) {
            try { amount = Integer.parseInt(data.get("amount").toString()); } catch (NumberFormatException ignored) {}
        }

        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (data.containsKey("name")) {
            String name = C.color(data.get("name").toString());
            meta.setDisplayName(name);
        }

        if (data.containsKey("lore")) {
            Object loreObj = data.get("lore");
            List<String> lore = new ArrayList<>();
            if (loreObj instanceof List<?> list) {
                for (Object line : list) {
                    lore.add(C.color(line.toString().replace("&", "§")));
                }
            } else if (loreObj instanceof String s) {
                lore.add(C.color(s));
            }
            meta.setLore(lore);
        }

        if (data.containsKey("playerHead") && meta instanceof SkullMeta skullMeta) {
            String owner = data.get("playerHead").toString();
            skullMeta.setOwner(owner);
        }

        if (data.containsKey("glow") && Boolean.parseBoolean(data.get("glow").toString())) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }

        item.setItemMeta(meta);
        return item;
    }
}
