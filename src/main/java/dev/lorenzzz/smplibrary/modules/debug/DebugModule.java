package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.commands.BugReportCommand;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.modules.web.WebStats;
import net.j4c0b3y.api.menu.MenuHandler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The debug module: errors, bug reports and the AI diagnosis behind the dashboard's Debug tab.
 *
 * <p>Three things live here because they are the same job seen from three sides — something is
 * wrong on the server, and somebody has to find out what:
 *
 * <ul>
 *   <li>{@link ErrorCapture} takes the plugin's exceptions off the console and into
 *       {@link ErrorStore}, where the dashboard can show them with a notification instead of
 *       burying them in a stack trace nobody reads.</li>
 *   <li>{@link BugReportFlow} lets players file what they actually saw, which is the half of the
 *       evidence no log contains.</li>
 *   <li>{@link AiDiagnostics} reads both, plus the server's own configuration, and says what it
 *       thinks is going on.</li>
 * </ul>
 *
 * <p>The analysis is on demand — a button on the dashboard, {@code /smpl debug analyze} in game —
 * and never automatic unless {@code ai.auto-analyze} is switched on, because every run is a paid
 * API call. Everything sent to the API is redacted by {@link ServerEvidence} first.
 */
public final class DebugModule extends AbstractModule {

    private static final List<String> DEFAULT_TYPES =
            List.of("blocchi", "redstone", "mob", "item", "comandi", "performance", "altro");
    private static final List<String> DEFAULT_HOSTS =
            List.of("medal.tv", "gyazo.com", "imgur.com", "youtube.com", "youtu.be", "streamable.com");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    /** The last analysis, whatever came of it. */
    public record Analysis(long at, String question, String requester, boolean ok,
                           String text, String error, long inputTokens, long outputTokens, long millis) {

        public String formattedAt() {
            return at == 0 ? "-" : CLOCK.format(Instant.ofEpochMilli(at));
        }

        String json() {
            return "{\"at\":" + WebStats.jsonStr(at == 0 ? "" : CLOCK.format(Instant.ofEpochMilli(at)))
                    + ",\"epoch\":" + at
                    + ",\"question\":" + WebStats.jsonStr(question)
                    + ",\"requester\":" + WebStats.jsonStr(requester)
                    + ",\"ok\":" + ok
                    + ",\"text\":" + WebStats.jsonStr(text)
                    + ",\"error\":" + WebStats.jsonStr(error)
                    + ",\"inputTokens\":" + inputTokens
                    + ",\"outputTokens\":" + outputTokens
                    + ",\"millis\":" + millis + "}";
        }
    }

    private final ErrorStore errors = new ErrorStore();
    private final AtomicBoolean analysing = new AtomicBoolean();
    private final AtomicBoolean planningFix = new AtomicBoolean();
    private final AtomicLong version = new AtomicLong();
    private final Set<UUID> mutedAdmins = ConcurrentHashMap.newKeySet();

    private BugReportStore reports;
    private BugReportFlow flow;
    private ErrorCapture capture;
    private AiDiagnostics ai;
    private ExecutorService worker;
    private BugReportCommand command;
    private MenuHandler menuHandler;

    private volatile Analysis lastAnalysis;
    private volatile long lastAutoAnalysis;

    /** Ultimo piano fix AI: valido solo se riferito all'analisi corrente (vedi fixPlanFor). */
    private volatile List<AiDiagnostics.PlannedAction> lastFixPlan = List.of();
    private volatile long fixPlanFor = 0;
    private volatile String fixPlanError = "";

    // config cache
    private List<String> reportTypes = DEFAULT_TYPES;
    private List<String> mediaHosts = DEFAULT_HOSTS;
    private long sessionTimeoutMillis = 300_000L;
    private boolean notifyAdmins = true;
    private boolean autoAnalyze;
    private long autoCooldownMillis = 1_800_000L;
    private int logLines = 300;

    public DebugModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "debug"; }

    @Override
    public Map<String, Object> defaults() {
        return Map.of("enabled", true);
    }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();
        loadConfigCache(cfg);

        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "smplibrary-debug");
            thread.setDaemon(true);
            return thread;
        });

        reports = new BugReportStore(plugin);
        reports.load();

        menuHandler = managers == null ? null : managers.menuHandler();
        flow = new BugReportFlow(plugin, this, messages, dialogGuard());
        listen(flow);

        command = new BugReportCommand();
        if (commands != null) commands.register(command);

        ai = new AiDiagnostics(
                cfg == null ? null : cfg.getString("ai.api-key", ""),
                cfg == null ? null : cfg.getString("ai.endpoint", ""),
                cfg == null ? null : cfg.getString("ai.model", "deepseek-v4-flash"),
                cfg == null ? 0 : cfg.getInt("ai.max-tokens", 16000));

        errors.onNewError(this::onNewError);
        capture = new ErrorCapture(plugin, errors, cfg == null || cfg.getBoolean("quiet-console", true));
        capture.install();

        // Sweep abandoned wizards; a minute of granularity is plenty for a five-minute timeout.
        repeat(() -> flow.expireStale(sessionTimeoutMillis), 1200L, 1200L);

        C.info("Debug: cattura errori attiva, AI " + (ai.configured()
                ? "pronta (" + ai.model() + ", key da " + ai.keySource() + ")"
                : "non configurata"));
    }

    private void loadConfigCache(ConfigurationSection cfg) {
        if (cfg == null) return;
        List<String> types = cfg.getStringList("report-types");
        if (!types.isEmpty()) reportTypes = List.copyOf(types);

        List<String> hosts = cfg.getStringList("media-hosts");
        if (!hosts.isEmpty()) mediaHosts = List.copyOf(hosts);

        sessionTimeoutMillis = Math.max(30, cfg.getInt("session-timeout-seconds", 300)) * 1000L;
        notifyAdmins = cfg.getBoolean("notify-admins", true);
        autoAnalyze = cfg.getBoolean("ai.auto-analyze", false);
        autoCooldownMillis = Math.max(60, cfg.getInt("ai.auto-cooldown-seconds", 1800)) * 1000L;
        logLines = Math.max(50, Math.min(2000, cfg.getInt("log-lines", 300)));
    }

    @Override
    protected void onShutdown() {
        if (capture != null) {
            capture.uninstall();
            capture = null;
        }
        if (flow != null) {
            flow.clear();
            flow = null;
        }
        if (commands != null && command != null) commands.unregister(command);
        command = null;

        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        if (ai != null) {
            ai.close();
            ai = null;
        }
        menuHandler = null;
    }

    // ------------------------------------------------------------------ errors

    /** Records a failure the plugin caught itself, rather than one that reached a logger. */
    public void report(String source, String message, Throwable thrown) {
        errors.record(source, message, thrown);
    }

    private void onNewError(ErrorStore.Entry entry) {
        version.incrementAndGet();
        notifyAdmins("&c⚠ &fErrore nel plugin: &7" + entry.headline()
                + " &8— &7vedi la sezione Debug della dashboard");

        if (!autoAnalyze) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoAnalysis < autoCooldownMillis) return;
        lastAutoAnalysis = now;
        analyze("Analisi automatica dopo un nuovo errore: " + entry.headline(), "auto");
    }

    public ErrorStore errors() {
        return errors;
    }

    public void clearErrors() {
        errors.clear();
        version.incrementAndGet();
    }

    // ------------------------------------------------------------- bug reports

    public List<String> reportTypes() {
        return reportTypes;
    }

    public List<String> allowedMediaHosts() {
        return mediaHosts;
    }

    public BugReportStore reports() {
        return reports;
    }

    /** Entry point for {@code /bugreport}. */
    public void startReport(Player player) {
        if (flow != null) flow.start(player);
    }

    void openConfirmMenu(Player player, String type, String title, String body, String media) {
        if (menuHandler == null) {
            // Without the menu there is nothing to confirm with; file it rather than lose it.
            C.warning("debug: MenuHandler non disponibile, la segnalazione viene inviata direttamente.");
            submitReport(player);
            return;
        }
        new BugReportConfirmMenu(player, menuHandler, this, type, title, body, media).open();
    }

    /** Called by the confirmation menu; writes the report and tells everyone who cares. */
    public void submitReport(Player player) {
        if (flow == null || reports == null) return;
        BugReport draft = flow.commit(player);
        if (draft == null) return;

        BugReport stored = reports.add(draft);
        version.incrementAndGet();

        messages.send(player, "bugreport.sent", Map.of("%id%", stored.id()));
        notifyAdmins("&e⚑ &fNuova segnalazione &7#" + stored.id() + " &fda &e" + stored.author()
                + " &8— &7" + stored.title());
    }

    public void cancelReport(Player player) {
        if (flow != null) flow.cancel(player, "bugreport.cancelled");
    }

    public BugReport setReportStatus(String id, String status) {
        BugReport updated = reports == null ? null : reports.setStatus(id, status);
        if (updated != null) version.incrementAndGet();
        return updated;
    }

    public boolean deleteReport(String id) {
        boolean removed = reports != null && reports.delete(id);
        if (removed) version.incrementAndGet();
        return removed;
    }

    // ---------------------------------------------------------------- analysis

    public boolean aiConfigured() {
        return ai != null && ai.configured();
    }

    public boolean analysing() {
        return analysing.get();
    }

    public Analysis lastAnalysis() {
        return lastAnalysis;
    }

    /**
     * Runs a diagnosis off the server thread. Returns false when one is already in flight — two
     * concurrent runs would just pay twice for the same answer.
     *
     * @param question  what to look into; blank falls back to "why is the server misbehaving"
     * @param requester who asked, for the dashboard's byline
     */
    public boolean analyze(String question, String requester) {
        if (ai == null || worker == null) return false;
        if (!analysing.compareAndSet(false, true)) return false;

        String asked = question == null || question.isBlank()
                ? "Analizza lo stato del server e gli errori recenti e dimmi cosa non va."
                : question.trim();

        version.incrementAndGet();
        worker.execute(() -> {
            try {
                // Bukkit API on the server thread, files and HTTP here. Hopping from the worker
                // rather than from the caller keeps a dashboard request from waiting on a tick.
                String bundle = ServerEvidence.assemble(
                        runtimeSnapshot(),
                        ServerEvidence.files(serverRoot(), logLines),
                        "\n## Errori catturati dal plugin\n" + errors.asEvidence(10),
                        "\n## Segnalazioni recenti dei player\n" + recentReportsEvidence());

                AiDiagnostics.Result result = ai.run(asked, bundle);
                lastAnalysis = new Analysis(System.currentTimeMillis(), asked, requester,
                        result.ok(), result.text(), result.error(),
                        result.inputTokens(), result.outputTokens(), result.millis());

                notifyAdmins(result.ok()
                        ? "&b✦ &fAnalisi AI completata &8— &7apri la sezione Debug della dashboard"
                        : "&c✦ &fAnalisi AI fallita: &7" + result.error());
            } catch (RuntimeException e) {
                lastAnalysis = new Analysis(System.currentTimeMillis(), asked, requester,
                        false, "", "errore interno: " + e.getMessage(), 0, 0, 0);
            } finally {
                analysing.set(false);
                version.incrementAndGet();
            }
        });
        return true;
    }

    /** Analyses one bug report, with the player's own words as the question. */
    public boolean analyzeReport(String reportId, String requester) {
        BugReport report = reports == null ? null : reports.byId(reportId);
        if (report == null) return false;
        return analyze(report.asEvidence(), requester);
    }

    // ------------------------------------------------------------ fix plan (AI)

    public boolean planningFix() {
        return planningFix.get();
    }

    /** Piano fix corrente — vale solo se {@link #fixPlanCurrent()} e' true. */
    public List<AiDiagnostics.PlannedAction> fixPlan() {
        return lastFixPlan;
    }

    public long fixPlanFor() {
        return fixPlanFor;
    }

    public String fixPlanError() {
        return fixPlanError;
    }

    /** True quando il piano si riferisce all'ultima analisi andata a buon fine. */
    public boolean fixPlanCurrent() {
        Analysis analysis = lastAnalysis;
        return analysis != null && analysis.ok() && fixPlanFor == analysis.at() && !lastFixPlan.isEmpty();
    }

    /**
     * Chiede all'AI un piano fix machine-readable per l'ultima analisi. Async sul worker,
     * come {@link #analyze}: ritorna false se c'e' gia' una pianificazione in corso o se
     * non esiste un'analisi valida da cui partire.
     */
    public boolean requestFixPlan(String requester) {
        if (ai == null || worker == null) return false;
        Analysis analysis = lastAnalysis;
        if (analysis == null || !analysis.ok()) return false;
        if (!planningFix.compareAndSet(false, true)) return false;

        long analysisAt = analysis.at();
        String asked = analysis.question();
        String analysisText = analysis.text();
        version.incrementAndGet();
        worker.execute(() -> {
            try {
                String bundle = ServerEvidence.assemble(
                        runtimeSnapshot(),
                        ServerEvidence.files(serverRoot(), logLines),
                        "\n## Errori catturati dal plugin\n" + errors.asEvidence(10),
                        "\n## Segnalazioni recenti dei player\n" + recentReportsEvidence());

                AiDiagnostics.PlanResult result = ai.planFixes(asked, bundle, analysisText);
                if (result.ok()) {
                    lastFixPlan = result.actions();
                    fixPlanFor = analysisAt;
                    fixPlanError = "";
                } else {
                    lastFixPlan = List.of();
                    fixPlanError = result.error();
                }
                notifyAdmins(result.ok()
                        ? "&b✦ &fPiano fix pronto &8— &7/smpl debug fix per la preview"
                        : "&c✦ &fPiano fix fallito: &7" + result.error());
            } catch (RuntimeException e) {
                lastFixPlan = List.of();
                fixPlanError = "errore interno: " + e.getMessage();
            } finally {
                planningFix.set(false);
                version.incrementAndGet();
            }
        });
        return true;
    }

    private String recentReportsEvidence() {
        if (reports == null) return "(nessuna)";
        StringBuilder text = new StringBuilder();
        List<BugReport> list = reports.all();
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            text.append(list.get(i).asEvidence()).append('\n');
        }
        return text.isEmpty() ? "(nessuna)" : text.toString();
    }

    /** {@link ServerEvidence#runtime} has to run on the server thread; this gets it from any. */
    private String runtimeSnapshot() {
        if (Bukkit.isPrimaryThread()) return ServerEvidence.runtime(plugin);

        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(ServerEvidence.runtime(plugin));
            } catch (RuntimeException e) {
                future.complete("(stato runtime non disponibile: " + e.getMessage() + ")");
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "(stato runtime non disponibile: il server non ha risposto in tempo)";
        }
    }

    private File serverRoot() {
        File data = plugin.getDataFolder();               // plugins/<name>
        File plugins = data.getParentFile();
        File root = plugins == null ? null : plugins.getParentFile();
        return root == null ? new File(".") : root;
    }

    // ----------------------------------------------------------- notifications

    /** Per-admin mute, so a busy staff member can silence the pings without a config edit. */
    public boolean toggleNotifications(UUID playerId) {
        if (mutedAdmins.remove(playerId)) return true;
        mutedAdmins.add(playerId);
        return false;
    }

    private void notifyAdmins(String legacyMessage) {
        if (!notifyAdmins) return;
        Runnable send = () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission("smplibrary.admin")) continue;
                if (mutedAdmins.contains(player.getUniqueId())) continue;
                C.send(player, legacyMessage);
            }
        };
        if (Bukkit.isPrimaryThread()) send.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, send);
    }

    // -------------------------------------------------------------------- web

    /** Bumped on every change; the dashboard stream uses it to avoid pushing an unchanged tab. */
    public long version() {
        return version.get() + errors.version() + (reports == null ? 0 : reports.version());
    }

    public String json() {
        Analysis analysis = lastAnalysis;
        Set<String> sources = new LinkedHashSet<>(errors.bySource().keySet());

        return "{\"errors\":" + errors.json()
                + ",\"reports\":" + (reports == null ? "[]" : reports.json())
                + ",\"analysis\":" + (analysis == null ? "null" : analysis.json())
                + ",\"analysing\":" + analysing.get()
                + ",\"ai\":{\"configured\":" + aiConfigured()
                + ",\"model\":" + WebStats.jsonStr(ai == null ? "" : ai.model())
                + ",\"keySource\":" + WebStats.jsonStr(ai == null ? "assente" : ai.keySource())
                + ",\"autoAnalyze\":" + autoAnalyze
                + ",\"planningFix\":" + planningFix.get()
                + ",\"fixPlanReady\":" + fixPlanCurrent()
                + ",\"fixPlanError\":" + WebStats.jsonStr(fixPlanError) + "}"
                + ",\"summary\":{\"errorSignatures\":" + errors.size()
                + ",\"errorTotal\":" + errors.totalOccurrences()
                + ",\"sources\":" + WebStats.jsonStr(String.join(", ", sources))
                + ",\"reportsOpen\":" + (reports == null ? 0 : reports.openCount())
                + ",\"reportsTotal\":" + (reports == null ? 0 : reports.size()) + "}}";
    }
}
