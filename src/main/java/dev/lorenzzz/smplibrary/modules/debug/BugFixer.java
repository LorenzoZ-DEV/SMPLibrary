package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.text.Chat;
import dev.lorenzzz.smplibrary.util.Log;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Esegue piani fix per QUALSIASI problema — mai l'output libero dell'AI.
 *
 * <p>Due sorgenti, stessa coda di esecuzione:
 * <ul>
 *   <li>piano AI ({@link AiDiagnostics#planFixes}): JSON validato contro l'allowlist
 *       (GAMERULE, DIFFICULTY, CONFIG su qualsiasi .yml/.properties del server inclusi
 *       i config di tutti i plugin sotto plugins/, MANUAL). Azioni fuori allowlist
 *       scartate in validazione e mai eseguite.</li>
 *   <li>fallback deterministico ({@link #preview}): ricontrolli locali per lo spawn
 *       mob (gamerule, difficulty, bukkit.yml) quando l'AI non e' configurata o non
 *       propone nulla di valido.</li>
 * </ul>
 *
 * <p>Solo server thread (i comandi ACF lo sono gia'). Backup .bak prima di ogni
 * scrittura file; broadcast SMPCORE BUG FIXXER e restart (10s) solo se un file lo richiede.
 */
public final class BugFixer {

    /** File server scrivibili anche fuori da plugins/ e config/. */
    private static final Set<String> SERVER_FILES = Set.of(
            "bukkit.yml",
            "spigot.yml",
            "paper-global.yml",
            "paper-world-defaults.yml",
            "config/paper-global.yml",
            "config/paper-world-defaults.yml",
            "server.properties",
            "purpur.yml",
            "pufferfish.yml");

    /** Un singolo intervento proposto o applicato. {@code manual} = solo istruzione, mai auto. */
    public record Fix(String id, String description, boolean requiresRestart, boolean manual) {}

    /** Esito di un apply: cosa e' stato fatto e se il restart e' stato schedulato. */
    public record Result(int appliedLive, int appliedFile, List<String> backups,
                         boolean restartScheduled, List<String> manualLeft) {}

    private BugFixer() {}

    /**
     * True se il planner AI puo' proporre questo file: accesso completo ai config —
     * qualsiasi {@code .yml/.yaml/.properties} sotto la root del server (inclusi
     * {@code plugins/...} di tutti i plugin), piu' i file server noti. Niente path
     * assoluti, niente {@code ..}, niente altre estensioni (jar, db, yml di log, ...).
     * Usato anche in validazione.
     */
    public static boolean allowedConfigFile(String file) {
        return isEditableConfig(file);
    }

    public static boolean isEditableConfig(String file) {
        if (file == null) return false;
        String clean = file.trim().replace('\\', '/');
        if (clean.isEmpty() || clean.length() > 160) return false;
        if (clean.startsWith("/") || clean.matches("^[A-Za-z]:/.*")) return false;
        if (clean.contains("..")) return false;
        String lower = clean.toLowerCase();
        boolean ext = lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties");
        if (!ext) return false;
        if (SERVER_FILES.contains(clean)) return true;
        // Qualsiasi config dei plugin + cartella config di Paper: accesso completo.
        return lower.startsWith("plugins/") || lower.startsWith("config/");
    }

    // ------------------------------------------------------- fallback mob (no AI)

    /** Ricontrolli locali spawn mob. Solo lettura, non cambia nulla. */
    public static List<Fix> preview(Smp_library plugin) {
        List<Fix> fixes = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            Boolean doSpawn = world.getGameRuleValue(GameRule.DO_MOB_SPAWNING);
            if (Boolean.FALSE.equals(doSpawn)) {
                fixes.add(new Fix("gamerule:" + world.getName(),
                        "Gamerule doMobSpawning=false nel mondo '" + world.getName() + "' -> imposta true (live, senza restart)",
                        false, false));
            }
            if (world.getDifficulty() == Difficulty.PEACEFUL) {
                fixes.add(new Fix("difficulty:" + world.getName(),
                        "Difficolta' PEACEFUL nel mondo '" + world.getName() + "' (niente mob ostili) -> imposta NORMAL (live, senza restart)",
                        false, false));
            }
        }

        File bukkitYml = new File(serverRoot(plugin), "bukkit.yml");
        if (bukkitYml.isFile()) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(bukkitYml);
                int monsters = yaml.getInt("spawn-limits.monsters", -999);
                if (monsters <= 0 && monsters != -999) {
                    fixes.add(new Fix("bukkit:spawn-limits.monsters",
                            "bukkit.yml spawn-limits.monsters=" + monsters + " (spawn azzerati) -> imposta 70 (richiede restart)",
                            true, false));
                }
            } catch (RuntimeException e) {
                Log.warning("BugFixer: bukkit.yml illeggibile (" + e.getMessage() + ")");
            }
        }

        fixes.add(new Fix("manual:worldguard",
                "MANUALE: alla zona segnalata esegui /rg info — se mob-spawning: deny o deny-spawn mostri, metti allow. Il plugin non modifica le regioni.",
                false, true));

        return fixes;
    }

    /**
     * Applica solo i fix non-manuali usciti da {@link #preview}. Backup .bak prima di
     * scrivere file, poi broadcast e restart (10s) solo se un file lo richiede.
     */
    public static Result apply(Smp_library plugin, String topic) {
        return applyFixes(plugin, preview(plugin), topic);
    }

    // ------------------------------------------------------- piano AI (generico)

    /** Traduce azioni AI validate in righe di preview (auto vs manuali). Solo lettura. */
    public static List<Fix> previewPlan(List<AiDiagnostics.PlannedAction> actions) {
        List<Fix> fixes = new ArrayList<>();
        if (actions == null) return fixes;
        for (int i = 0; i < actions.size(); i++) {
            AiDiagnostics.PlannedAction action = actions.get(i);
            boolean manual = "MANUAL".equals(action.kind());
            fixes.add(new Fix("plan:" + i,
                    (manual ? "MANUALE: " : "") + action.description()
                            + (manual ? "" : (action.requiresRestart() ? " (richiede restart)" : " (live)")),
                    action.requiresRestart(), manual));
        }
        return fixes;
    }

    /** Applica un piano AI (solo azioni non-manuali, ri-validate). Backup + restart se serve. */
    public static Result applyPlan(Smp_library plugin, List<AiDiagnostics.PlannedAction> actions, String topic) {
        int live = 0;
        int files = 0;
        List<String> backups = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        boolean fileChanged = false;

        if (actions == null) actions = List.of();
        for (AiDiagnostics.PlannedAction action : actions) {
            if (action == null || "MANUAL".equals(action.kind())) {
                if (action != null) manual.add("MANUALE: " + action.description());
                continue;
            }
            try {
                switch (action.kind()) {
                    case "GAMERULE" -> {
                        if (applyGamerule(action)) live++;
                    }
                    case "DIFFICULTY" -> {
                        if (applyDifficulty(action)) live++;
                    }
                    case "CONFIG" -> {
                        String backup = applyConfig(plugin, action);
                        if (backup != null) {
                            files++;
                            fileChanged = true;
                            if (!backup.isEmpty()) backups.add(backup);
                        }
                    }
                    default -> Log.warning("BugFixer: kind sconosciuto '" + action.kind() + "' ignorato");
                }
            } catch (Exception e) {
                Log.warning("BugFixer: fix '" + action.description() + "' fallito (" + e.getMessage() + ")");
            }
        }

        boolean restarted = finish(plugin, topic, live, fileChanged);
        return new Result(live, files, backups, restarted, manual);
    }

    // ------------------------------------------------------- esecutori allowlist

    private static List<World> targetWorlds(String world) {
        if (world == null || world.isBlank() || "*".equals(world.trim())) return Bukkit.getWorlds();
        World single = Bukkit.getWorld(world.trim());
        return single == null ? List.of() : List.of(single);
    }

    private static GameRule<?> findGameRule(String name) {
        for (GameRule<?> rule : RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE)) {
            if (rule.getKey().getKey().equalsIgnoreCase(name)) return rule;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean applyGamerule(AiDiagnostics.PlannedAction action) {
        GameRule<?> rule = findGameRule(action.rule());
        if (rule == null) {
            Log.warning("BugFixer: gamerule sconosciuta '" + action.rule() + "', salto");
            return false;
        }
        List<World> worlds = targetWorlds(action.world());
        if (worlds.isEmpty()) {
            Log.warning("BugFixer: mondo '" + action.world() + "' non trovato, salto");
            return false;
        }
        boolean any = false;
        for (World world : worlds) {
            Object current = world.getGameRuleValue(rule);
            try {
                if (current instanceof Boolean) {
                    world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(action.value()));
                } else if (current instanceof Integer) {
                    world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(action.value()));
                } else {
                    Log.warning("BugFixer: gamerule '" + action.rule() + "' di tipo non supportato, salto");
                    continue;
                }
                any = true;
                Log.info("BugFixer: gamerule " + action.rule() + "=" + action.value() + " in '" + world.getName() + "'");
            } catch (RuntimeException e) {
                Log.warning("BugFixer: gamerule '" + action.rule() + "' valore '" + action.value()
                        + "' non valido (" + e.getMessage() + ")");
            }
        }
        return any;
    }

    private static boolean applyDifficulty(AiDiagnostics.PlannedAction action) {
        Difficulty difficulty;
        try {
            difficulty = Difficulty.valueOf(action.value().toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.warning("BugFixer: difficulty '" + action.value() + "' non valida, salto");
            return false;
        }
        List<World> worlds = targetWorlds(action.world());
        if (worlds.isEmpty()) {
            Log.warning("BugFixer: mondo '" + action.world() + "' non trovato, salto");
            return false;
        }
        for (World world : worlds) {
            world.setDifficulty(difficulty);
            Log.info("BugFixer: difficulty=" + difficulty + " in '" + world.getName() + "'");
        }
        return true;
    }

    /**
     * Scrive un allowlisted config file. Ritorna nome backup o null se saltato;
     * stringa vuota = applicato senza backup separato (impossibile qui, sempre backup).
     */
    private static String applyConfig(Smp_library plugin, AiDiagnostics.PlannedAction action) throws Exception {
        if (!allowedConfigFile(action.file())) {
            Log.warning("BugFixer: file '" + action.file() + "' fuori allowlist, salto");
            return null;
        }
        String path = action.path() == null ? "" : action.path().trim();
        if (!path.matches("[A-Za-z0-9._\\-]{1,80}")) {
            Log.warning("BugFixer: path '" + path + "' non valido, salto");
            return null;
        }
        String lowered = path.toLowerCase();
        if (lowered.contains("password") || lowered.contains("secret") || lowered.contains("token")
                || lowered.contains("api-key") || lowered.contains("apikey")) {
            Log.warning("BugFixer: path '" + path + "' sembra una credenziale, salto");
            return null;
        }

        File root = serverRoot(plugin);
        File target = new File(root, action.file().trim().replace('\\', '/'));
        try {
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                Log.warning("BugFixer: file '" + action.file() + "' fuori dalla root, salto");
                return null;
            }
        } catch (Exception e) {
            Log.warning("BugFixer: file '" + action.file() + "' non risolvibile, salto");
            return null;
        }
        if (!target.isFile()) {
            Log.warning("BugFixer: file '" + action.file() + "' assente, salto");
            return null;
        }
        File backup = new File(target.getParentFile(),
                target.getName() + ".bak-smpfix-" + System.currentTimeMillis());
        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);

        if (target.getName().endsWith(".properties")) {
            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(target.toPath(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            props.setProperty(path, action.value());
            try (var writer = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
                props.store(writer, "smp-library BugFixer");
            }
        } else {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(target);
            yaml.set(path, coerce(action.value()));
            yaml.save(target);
        }
        Log.info("BugFixer: " + action.file() + " " + path + "=" + action.value() + " (backup " + backup.getName() + ")");
        return backup.getName();
    }

    private static Object coerce(String value) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        String stripped = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
        try {
            return Integer.parseInt(stripped);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(stripped);
        } catch (NumberFormatException ignored) {
        }
        return stripped;
    }

    // ------------------------------------------------------- coda comune

    private static Result applyFixes(Smp_library plugin, List<Fix> fixes, String topic) {
        int live = 0;
        int files = 0;
        List<String> backups = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        boolean fileChanged = false;

        for (Fix fix : fixes) {
            if (fix.manual()) {
                manual.add(fix.description());
                continue;
            }
            try {
                if (fix.id().startsWith("gamerule:")) {
                    String worldName = fix.id().substring("gamerule:".length());
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        world.setGameRule(GameRule.DO_MOB_SPAWNING, true);
                        live++;
                        Log.info("BugFixer: doMobSpawning=true in '" + worldName + "'");
                    }
                } else if (fix.id().startsWith("difficulty:")) {
                    String worldName = fix.id().substring("difficulty:".length());
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        world.setDifficulty(Difficulty.NORMAL);
                        live++;
                        Log.info("BugFixer: difficulty=NORMAL in '" + worldName + "'");
                    }
                } else if (fix.id().equals("bukkit:spawn-limits.monsters")) {
                    File bukkitYml = new File(serverRoot(plugin), "bukkit.yml");
                    File backup = new File(bukkitYml.getParentFile(),
                            "bukkit.yml.bak-smpfix-" + System.currentTimeMillis());
                    Files.copy(bukkitYml.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    backups.add(backup.getName());
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(bukkitYml);
                    yaml.set("spawn-limits.monsters", 70);
                    yaml.save(bukkitYml);
                    files++;
                    fileChanged = true;
                    Log.info("BugFixer: bukkit.yml spawn-limits.monsters=70 (backup " + backup.getName() + ")");
                }
            } catch (Exception e) {
                Log.warning("BugFixer: fix '" + fix.id() + "' fallito (" + e.getMessage() + ")");
            }
        }

        boolean restarted = finish(plugin, topic, live, fileChanged);
        return new Result(live, files, backups, restarted, manual);
    }

    /** Broadcast SMPCORE BUG FIXXER + restart (10s) solo se un file e' cambiato. */
    private static boolean finish(Smp_library plugin, String topic, int live, boolean fileChanged) {
        if (fileChanged) {
            String clean = topic == null || topic.isBlank() ? "bug segnalato" : topic.trim().replaceAll("\\s+", " ");
            if (clean.length() > 80) clean = clean.substring(0, 80);
            String msg = "&5&lSMPCORE BUG FIXXER: &fRiavvio tra 10 secondi per sistemare il bug su &d" + clean;
            broadcastAll(msg);
            Log.info("BugFixer: restart schedulato tra 10s (" + clean + ")");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
                } catch (RuntimeException e) {
                    Log.warning("BugFixer: restart automatico fallito (" + e.getMessage() + ") — riavvia a mano.");
                }
            }, 200L);
            return true;
        }
        if (live > 0) {
            broadcastAll("&5&lSMPCORE BUG FIXXER: &fFix applicato live, &anessun restart necessario&f.");
        }
        return false;
    }

    /** Topic per il broadcast: domanda dell'ultima analisi o fallback generico. */
    public static String topicFrom(String lastQuestion) {
        if (lastQuestion == null || lastQuestion.isBlank()) return "bug segnalato";
        String oneLine = lastQuestion.trim().replaceAll("\\s+", " ");
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80);
    }

    static void broadcastAll(String legacyMessage) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Chat.send(player, legacyMessage);
        }
        Chat.send(Bukkit.getConsoleSender(), legacyMessage);
    }

    private static File serverRoot(Smp_library plugin) {
        File data = plugin.getDataFolder();
        File plugins = data.getParentFile();
        File root = plugins == null ? null : plugins.getParentFile();
        return root == null ? new File(".") : root;
    }
}
