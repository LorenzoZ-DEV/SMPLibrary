package dev.lorenzzz.smplibrary.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.HelpCommand;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.ManagerService;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.adaptive.AdaptiveToolModule;
import dev.lorenzzz.smplibrary.modules.amethyst.AmethystToolModule;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGameType;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import dev.lorenzzz.smplibrary.modules.trivella.TrivellaModule;
import dev.lorenzzz.smplibrary.modules.web.WebModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.net.InetAddress;
import java.util.Map;
import java.util.function.Function;

@CommandAlias("smplibrary|smpl|sl")
@CommandPermission("smplibrary.admin")
public final class SmplCommand extends BaseCommand {

    private static final String[] HELP = {
            "",
            "&d&lSMP-Library &8» &7v%version%",
            "&7Plugin multifunzione per server SMP: gestisce key-all, chat games,",
            "&7strumenti custom (Amethyst, Adaptive, Trivella), shop, playtime,",
            "&7first-join, impostazioni player e una web dashboard.",
            "",
            "&d&lComandi principali:",
            "&8» &f/smplibrary help &7- mostra questo aiuto",
            "&8» &f/smplibrary reload &7- ricarica config & messaggi",
            "&8» &f/smplibrary modules &7- lista moduli e stato (enabled/disabled)",
            "&8» &f/smplibrary webedit &7- genera link temporaneo (5 min) per la web dashboard",
            "&8» &f/smplibrary chatgames start &7- avvia subito un round di chat games",
            "",
            "&d&lStrumenti custom:",
            "&8» &f/smplibrary amethyst give <tool> [player]",
            "&7    &7tool: &famethyst_sword &7| &famethyst_pickaxe &7(tab-complete)",
            "&7    &7player: &fomesso = te stesso &7(tab-complete online players)",
            "&8» &f/smplibrary adaptive give [player]",
            "&8» &f/smplibrary trivella give [player]",
            "",
            "&d&lAltri comandi (separati):",
            "&8» &f/keyall &7&o(alias: /keys)&7- distribuisce chiavi a tutti i player online",
            "&8» &f/pay <player> <amount> &7&o(alias: /pistacchipay, /pagapers)&7- trasferisci pistacchi",
            "",
            "&7Permessi:",
            "&8» &fsmplibrary.admin &7- accesso ai comandi admin",
            "&8» &fsmplibrary.keyall.use &7- uso di /keyall",
            "&8» &fsmplibrary.pay.use &7- uso di /pay",
            "",
            "&7Config: &fplugins/SmpLibrary/config.yml &7(moduli in &fMODULES.<id>&7)",
            "&7GUI: &fplugins/SmpLibrary/inventories/*.yml",
            "",
    };

    @Default
    @HelpCommand
    public void onHelp(CommandSender sender, CommandHelp help) {
        String version = plugin().getPluginMeta().getVersion();
        for (String line : HELP) {
            C.send(sender, line.replace("%version%", version));
        }
    }

    @Subcommand("reload")
    public void onReload(CommandSender sender) {
        Smp_library plugin = plugin();
        plugin.reloadConfig();

        ManagerService managers = plugin.getManagers();
        managers.config().reload();
        managers.messages().reload();
        managers.economy().reload();
        managers.modules().reload();

        C.send(sender, "&aSMP-Library reloaded (config + messages + modules).");
    }

    @Subcommand("modules")
    public void onModules(CommandSender sender) {
        C.send(sender, "&d&lModules:");
        ModuleManager modules = modules();
        for (Module module : modules.active()) {
            C.send(sender, "&7- &f" + module.id() + " " + status(modules, module.id()));
        }
    }

    private static String status(ModuleManager modules, String id) {
        if (!modules.isEnabled(id)) return "&cdisabled";
        return modules.isRunning(id) ? "&aenabled (running)" : "&eenabled (NOT running)";
    }

    @Subcommand("webedit")
    public void onWebedit(CommandSender sender) {
        WebModule web = modules().module("web", WebModule.class);
        if (web == null || web.boundPort() == -1) {
            C.send(sender, "&cWeb dashboard is not running. Check MODULES.web.enabled in config.yml.");
            return;
        }

        String token = web.generateTempToken();
        if (token == null) {
            C.send(sender, "&cFailed to generate access token.");
            return;
        }

        String url = "http://" + reachableHost(web.boundHost()) + ":" + web.boundPort() + "/?token=" + token;
        C.send(sender, "&d&lWeb Editor &8&l» &7Click the link below to access the dashboard:");
        C.send(sender, "&9&n" + url);
        C.send(sender, "&7Token expires in 5 minutes.");

        if (sender instanceof Player player) {
            player.sendMessage(Component.text("§d⬇  §fClick here to open the Web Dashboard  §d⬇")
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("§a" + url))));
        }
    }

    /** A wildcard bind address is useless in a URL — swap it for something clickable. */
    private static String reachableHost(String host) {
        if (host != null && !host.isEmpty() && !"0.0.0.0".equals(host)) return host;
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    @Subcommand("chatgames start")
    public void onChatGamesStart(CommandSender sender) {
        ChatGamesModule chatGames = modules().module("chatgames", ChatGamesModule.class);
        if (chatGames == null) {
            C.send(sender, "&cchatgames module is not running.");
            return;
        }
        ChatGameType started = chatGames.startRound();
        if (started == null) {
            C.send(sender, "&cNo game type has usable data — check words / trivia.questions in config.yml.");
            return;
        }
        C.send(sender, "&aStarted a &f" + started.name() + " &around.");
    }

    // ------------------------------------------------------------ tool giving

    @Subcommand("amethyst give")
    @CommandCompletion("@nothing @players")
    public void onAmethystGive(CommandSender sender, String tool, @Optional String targetName) {
        Player target = resolveTarget(sender, targetName);
        if (target == null) return;

        AmethystToolModule module = modules().module("amethyst-tool", AmethystToolModule.class);
        if (module == null) {
            C.send(sender, "&camethyst-tool module is not running.");
            return;
        }

        ItemStack item = module.buildAmethyst(tool);
        if (item == null) {
            C.send(sender, messages("amethyst-tool.unknown", Map.of("%tool%", tool)));
            return;
        }
        give(target, item);
        C.send(sender, messages("amethyst-tool.given", Map.of("%tool%", tool, "%player%", target.getName())));
    }

    @Subcommand("adaptive give")
    @CommandCompletion("@players")
    public void onAdaptiveGive(CommandSender sender, @Optional String targetName) {
        giveTool(sender, targetName, "adaptive-tool", AdaptiveToolModule.class,
                AdaptiveToolModule::buildAdaptive, "Adaptive Tool");
    }

    @Subcommand("trivella give")
    @CommandCompletion("@players")
    public void onTrivellaGive(CommandSender sender, @Optional String targetName) {
        giveTool(sender, targetName, "trivella", TrivellaModule.class,
                TrivellaModule::buildTrivella, "Trivella");
    }

    private <T extends Module> void giveTool(CommandSender sender, String targetName, String moduleId,
                                             Class<T> type, Function<T, ItemStack> builder, String label) {
        Player target = resolveTarget(sender, targetName);
        if (target == null) return;

        T module = modules().module(moduleId, type);
        if (module == null) {
            C.send(sender, "&c" + moduleId + " module is not running.");
            return;
        }

        ItemStack item = builder.apply(module);
        if (item == null) {
            C.send(sender, "&c" + label + " is not configured.");
            return;
        }
        give(target, item);
        C.send(sender, "&dGave the &5" + label + " &dto &f" + target.getName() + "&d.");
    }

    /** The named player, or the sender when no name was given. Reports the problem itself. */
    private static Player resolveTarget(CommandSender sender, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            if (sender instanceof Player player) return player;
            C.send(sender, "&cSpecify a player: ... give <tool> <player>");
            return null;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) C.send(sender, "&cPlayer not found: " + targetName);
        return target;
    }

    /** Adds the item to the inventory, dropping whatever doesn't fit at the player's feet. */
    private static void give(Player target, ItemStack item) {
        target.getInventory().addItem(item).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
    }

    private static Smp_library plugin() { return Smp_library.getInstance(); }

    private static ModuleManager modules() { return plugin().getManagers().modules(); }

    private static String messages(String path, Map<String, ?> placeholders) {
        return plugin().getManagers().messages().get(path, placeholders);
    }
}
