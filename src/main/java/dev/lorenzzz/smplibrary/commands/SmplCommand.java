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
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.manager.ManagerService;
import dev.lorenzzz.smplibrary.manager.ModuleManager;
import dev.lorenzzz.smplibrary.modules.Module;
import dev.lorenzzz.smplibrary.modules.adaptive.AdaptiveToolModule;
import dev.lorenzzz.smplibrary.modules.amethyst.AmethystToolModule;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGameType;
import dev.lorenzzz.smplibrary.modules.chatgames.ChatGamesModule;
import dev.lorenzzz.smplibrary.modules.debug.DebugModule;
import dev.lorenzzz.smplibrary.modules.debug.ErrorStore;
import dev.lorenzzz.smplibrary.modules.trivella.TrivellaModule;
import dev.lorenzzz.smplibrary.modules.web.WebModule;
import dev.lorenzzz.smplibrary.text.Chat;
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
            "&d&lDebug &7(sezione Debug della dashboard):",
            "&8» &f/smplibrary debug &7- errori catturati e segnalazioni aperte",
            "&8» &f/smplibrary debug analyze [domanda] &7- analisi AI della causa",
            "&8» &f/smplibrary debug result &7- rileggi l'ultima analisi",
            "&8» &f/smplibrary debug fix &7- piano fix AI per l'ultima analisi (preview)",
            "&8» &f/smplibrary debug fix confirm &7- applica fix + broadcast + restart se serve",
            "&8» &f/smplibrary debug fix regen &7- rigenera il piano fix",
            "&8» &f/smplibrary debug clear &7- azzera gli errori catturati",
            "&8» &f/smplibrary debug notify &7- silenzia/riattiva le tue notifiche",
            "&8» &f/bugreport &7&o(alias: /segnalabug)&7- i player segnalano un bug",
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
            "&8» &f/homegui &7&o(alias: /homesgui)&7- GUI delle home &7(dati di HuskHomes)",
            "&8» &f/warpgui &7&o(alias: /warpsgui)&7- GUI dei warp &7(dati di HuskHomes)",
            "&8» &f/spawn &7- teleport allo spawn &7(/setspawn per impostarlo)",
            "&8» &f/nv &7&o(alias: /nightvision)&7- visione notturna &7([player] per altri)",
            "&8» &f/discord &7- link Discord &7(/vote, /store per voto e shop)",
            "&8» &f/tpauto [on|off] &7- accetta da solo le richieste di teleport",
            "&7    &f/tpauto status &7- diagnostica: interruttore, hook HuskHomes, richieste",
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
            Chat.send(sender, line.replace("%version%", version));
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

        Chat.send(sender, "&aSMP-Library reloaded (config + messages + modules).");
    }

    @Subcommand("modules")
    public void onModules(CommandSender sender) {
        Chat.send(sender, "&d&lModules:");
        ModuleManager modules = modules();
        for (Module module : modules.active()) {
            Chat.send(sender, "&7- &f" + module.id() + " " + status(modules, module.id()));
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
            Chat.send(sender, "&cWeb dashboard is not running. Check MODULES.web.enabled in config.yml.");
            return;
        }

        String token = web.generateTempToken();
        if (token == null) {
            Chat.send(sender, "&cFailed to generate access token.");
            return;
        }

        String url = "http://" + reachableHost(web.boundHost()) + ":" + web.boundPort() + "/?token=" + token;
        Chat.send(sender, "&d&lWeb Editor &8&l» &7Click the link below to access the dashboard:");
        Chat.send(sender, "&9&n" + url);
        Chat.send(sender, "&7Token expires in 5 minutes.");

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

    // ------------------------------------------------------------------ debug

    @Subcommand("debug")
    public void onDebug(CommandSender sender) {
        DebugModule debug = debugModule(sender);
        if (debug == null) return;

        Chat.send(sender, "&d&lDebug &8» &7errori catturati: &f" + debug.errors().size()
                + " &7(occorrenze totali: &f" + debug.errors().totalOccurrences() + "&7)");
        Chat.send(sender, "&7Segnalazioni aperte: &f" + debug.reports().openCount()
                + "&7/&f" + debug.reports().size());
        Chat.send(sender, "&7AI: " + (debug.aiConfigured() ? "&aconfigurata" : "&cnon configurata")
                + (debug.analysing() ? " &e(analisi in corso)" : ""));

        int shown = 0;
        for (ErrorStore.Entry entry : debug.errors().recent()) {
            if (shown++ >= 5) break;
            Chat.send(sender, "&8» &c" + entry.headline() + " &8x" + entry.count());
        }
        Chat.send(sender, "&7Usa &f/smpl debug analyze [domanda] &7per l'analisi AI, "
                + "&f/smpl debug clear&7, &f/smpl debug notify&7.");
    }

    @Subcommand("debug analyze")
    @CommandCompletion("@nothing")
    public void onDebugAnalyze(CommandSender sender, @Optional String question) {
        DebugModule debug = debugModule(sender);
        if (debug == null) return;

        if (!debug.aiConfigured()) {
            Chat.send(sender, "&cNessuna API key: imposta la variabile d'ambiente DEEPSEEK_API_KEY "
                    + "oppure MODULES.debug.ai.api-key in config.yml.");
            return;
        }
        if (!debug.analyze(question, sender.getName())) {
            Chat.send(sender, "&eUn'analisi e' gia' in corso. Attendi che finisca.");
            return;
        }
        Chat.send(sender, "&7Analisi avviata. Il risultato arriva nella sezione &fDebug &7della "
                + "dashboard, e ti avviso in chat quando e' pronta.");
    }

    @Subcommand("debug result")
    public void onDebugResult(CommandSender sender) {
        DebugModule debug = debugModule(sender);
        if (debug == null) return;

        DebugModule.Analysis analysis = debug.lastAnalysis();
        if (analysis == null) {
            Chat.send(sender, "&7Nessuna analisi ancora eseguita.");
            return;
        }
        if (!analysis.ok()) {
            Chat.send(sender, "&cUltima analisi fallita: &7" + analysis.error());
            return;
        }
        Chat.send(sender, "&d&lAnalisi del &f" + analysis.formattedAt()
                + " &8(&7richiesta da " + analysis.requester() + "&8)");
        for (String line : analysis.text().split("\\R")) {
            Chat.send(sender, line.startsWith("#") ? "&d&l" + line.replace("#", "").trim() : "&7" + line);
        }
    }

    @Subcommand("debug fix")
    @CommandCompletion("confirm regen")
    public void onDebugFix(CommandSender sender, @Optional String arg) {
        DebugModule debug = debugModule(sender);
        if (debug == null) return;

        // --- confirm: applica piano AI se corrente, altrimenti fallback locale mob ---
        if (arg != null && arg.equalsIgnoreCase("confirm")) {
            DebugModule.Analysis analysis = debug.lastAnalysis();
            String topic = dev.lorenzzz.smplibrary.modules.debug.BugFixer.topicFrom(
                    analysis == null ? "" : analysis.question());
            dev.lorenzzz.smplibrary.modules.debug.BugFixer.Result result;
            if (debug.fixPlanCurrent()) {
                result = dev.lorenzzz.smplibrary.modules.debug.BugFixer.applyPlan(
                        plugin(), debug.fixPlan(), topic);
            } else {
                result = dev.lorenzzz.smplibrary.modules.debug.BugFixer.apply(plugin(), topic);
            }
            Chat.send(sender, "&5&lSMPCORE BUG FIXXER &8» &7fix live: &f" + result.appliedLive()
                    + " &7file: &f" + result.appliedFile()
                    + (result.backups().isEmpty() ? "" : " &8(backup: " + String.join(", ", result.backups()) + "&8)"));
            if (result.restartScheduled()) {
                Chat.send(sender, "&5&lSMPCORE BUG FIXXER: &fRiavvio per sistemare il bug su &d" + topic);
            } else if (result.appliedLive() == 0 && result.appliedFile() == 0) {
                Chat.send(sender, "&aNessun fix automatico da applicare. Vedi i passi manuali sotto.");
            } else {
                Chat.send(sender, "&aFix applicato live, nessun restart necessario.");
            }
            for (String manual : result.manualLeft()) {
                Chat.send(sender, "&e" + manual);
            }
            return;
        }

        // --- regen: forza un nuovo piano AI per l'ultima analisi ---
        if (arg != null && arg.equalsIgnoreCase("regen")) {
            if (debug.requestFixPlan(sender.getName())) {
                Chat.send(sender, "&7Nuovo piano fix in generazione, riprova tra qualche secondo.");
            } else if (debug.planningFix()) {
                Chat.send(sender, "&ePiano fix gia' in generazione, attendi.");
            } else {
                Chat.send(sender, "&cServe prima un'analisi valida: &f/smpl debug analyze [problema]");
            }
            return;
        }

        // --- preview: piano AI se corrente, altrimenti controlli locali immediati ---
        java.util.List<dev.lorenzzz.smplibrary.modules.debug.BugFixer.Fix> local =
                dev.lorenzzz.smplibrary.modules.debug.BugFixer.preview(plugin());
        long localAuto = local.stream().filter(f -> !f.manual()).count();

        if (!debug.aiConfigured()) {
            showFixes(sender, local, "controlli locali (AI non configurata)");
            return;
        }
        DebugModule.Analysis analysis = debug.lastAnalysis();
        if (analysis == null || !analysis.ok()) {
            showFixes(sender, local, "controlli locali");
            Chat.send(sender, "&7Per un piano sul tuo problema: &f/smpl debug analyze [problema] &7poi &f/smpl debug fix&7.");
            return;
        }
        if (debug.planningFix()) {
            showFixes(sender, local, "controlli locali");
            Chat.send(sender, "&ePiano fix AI in generazione, riprova tra qualche secondo.");
            return;
        }
        if (!debug.fixPlanCurrent()) {
            if (debug.requestFixPlan(sender.getName())) {
                showFixes(sender, local, "controlli locali");
                Chat.send(sender, "&7Piano fix AI in generazione dall'ultima analisi, riprova tra qualche secondo.");
            } else {
                showFixes(sender, local, "controlli locali");
                if (!debug.fixPlanError().isEmpty()) {
                    Chat.send(sender, "&cPiano fix fallito: &7" + debug.fixPlanError());
                }
            }
            return;
        }

        java.util.List<dev.lorenzzz.smplibrary.modules.debug.BugFixer.Fix> plan =
                dev.lorenzzz.smplibrary.modules.debug.BugFixer.previewPlan(debug.fixPlan());
        showFixes(sender, plan, "piano AI per: "
                + dev.lorenzzz.smplibrary.modules.debug.BugFixer.topicFrom(analysis.question()));
    }

    private static void showFixes(CommandSender sender,
            java.util.List<dev.lorenzzz.smplibrary.modules.debug.BugFixer.Fix> fixes, String title) {
        long auto = fixes.stream().filter(f -> !f.manual()).count();
        if (auto == 0) {
            Chat.send(sender, "&aNessun fix automatico (" + title + ").");
        } else {
            Chat.send(sender, "&5&lSMPCORE BUG FIXXER &8» &7anteprima " + title + " (" + auto + " auto + "
                    + (fixes.size() - auto) + " manuali):");
            for (dev.lorenzzz.smplibrary.modules.debug.BugFixer.Fix fix : fixes) {
                if (!fix.manual()) {
                    Chat.send(sender, "&d[auto] &f" + fix.description());
                }
            }
            Chat.send(sender, "&7Applica con &f/smpl debug fix confirm &7— backup .bak, "
                    + "restart (10s + broadcast) solo se un file lo richiede.");
        }
        for (dev.lorenzzz.smplibrary.modules.debug.BugFixer.Fix fix : fixes) {
            if (fix.manual()) Chat.send(sender, "&e" + fix.description());
        }
    }

    @Subcommand("debug clear")
    public void onDebugClear(CommandSender sender) {
        DebugModule debug = debugModule(sender);
        if (debug == null) return;
        debug.clearErrors();
        Chat.send(sender, "&aErrori catturati azzerati.");
    }

    @Subcommand("debug notify")
    public void onDebugNotify(CommandSender sender) {
        DebugModule debug = debugModule(sender);
        if (debug == null) return;
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "&cSolo un player puo' cambiare le proprie notifiche.");
            return;
        }
        boolean on = debug.toggleNotifications(player.getUniqueId());
        Chat.send(sender, on ? "&aNotifiche di debug attivate." : "&7Notifiche di debug silenziate.");
    }

    private DebugModule debugModule(CommandSender sender) {
        DebugModule debug = modules().module("debug", DebugModule.class);
        if (debug == null) {
            Chat.send(sender, "&cIl modulo debug non e' in esecuzione (MODULES.debug.enabled).");
        }
        return debug;
    }

    @Subcommand("chatgames start")
    public void onChatGamesStart(CommandSender sender) {
        ChatGamesModule chatGames = modules().module("chatgames", ChatGamesModule.class);
        if (chatGames == null) {
            Chat.send(sender, "&cchatgames module is not running.");
            return;
        }
        ChatGameType started = chatGames.startRound();
        if (started == null) {
            Chat.send(sender, "&cNo game type has usable data — check words / trivia.questions in config.yml.");
            return;
        }
        Chat.send(sender, "&aStarted a &f" + started.name() + " &around.");
    }

    // ------------------------------------------------------------ tool giving

    @Subcommand("amethyst give")
    @CommandCompletion("@nothing @players")
    public void onAmethystGive(CommandSender sender, String tool, @Optional String targetName) {
        Player target = resolveTarget(sender, targetName);
        if (target == null) return;

        AmethystToolModule module = modules().module("amethyst-tool", AmethystToolModule.class);
        if (module == null) {
            Chat.send(sender, "&camethyst-tool module is not running.");
            return;
        }

        ItemStack item = module.buildAmethyst(tool);
        if (item == null) {
            Chat.send(sender, messages("amethyst-tool.unknown", Map.of("%tool%", tool)));
            return;
        }
        give(target, item);
        Chat.send(sender, messages("amethyst-tool.given", Map.of("%tool%", tool, "%player%", target.getName())));
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
            Chat.send(sender, "&c" + moduleId + " module is not running.");
            return;
        }

        ItemStack item = builder.apply(module);
        if (item == null) {
            Chat.send(sender, "&c" + label + " is not configured.");
            return;
        }
        give(target, item);
        Chat.send(sender, "&dGave the &5" + label + " &dto &f" + target.getName() + "&d.");
    }

    /** The named player, or the sender when no name was given. Reports the problem itself. */
    private static Player resolveTarget(CommandSender sender, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            if (sender instanceof Player player) return player;
            Chat.send(sender, "&cSpecify a player: ... give <tool> <player>");
            return null;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) Chat.send(sender, "&cPlayer not found: " + targetName);
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
