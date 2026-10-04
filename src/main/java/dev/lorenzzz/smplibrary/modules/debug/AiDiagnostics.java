package dev.lorenzzz.smplibrary.modules.debug;

import dev.lorenzzz.smplibrary.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The model call behind the dashboard's "analizza" button.
 *
 * <p>One blocking request per analysis, run from {@link DebugModule}'s own executor — never from
 * the server thread, and never from a Bukkit async task that could outlive a reload. The evidence
 * bundle ({@link ServerEvidence}) is already redacted by the time it gets here.
 *
 * <p>The default provider is DeepSeek, an OpenAI-compatible gateway. It is spoken
 * over plain HTTP with the JDK's own client rather than a vendor SDK: the whole protocol
 * is one POST and one JSON body, and an SDK here would be another dependency to shade
 * and relocate. Gson comes from Paper, so nothing is added to the jar at all.
 *
 * <p>The key is resolved in order: the {@code DEEPSEEK_API_KEY} environment variable
 * (legacy {@code OPENCODE_API_KEY} still honoured as fallback), then config.yml.
 * It is never logged, and {@link ServerEvidence#redact} blanks anything key-shaped
 * before evidence leaves the machine.
 */
public final class AiDiagnostics {

    /**
     * No bundled key: DeepSeek has no shared key, every server must use its own
     * {@code DEEPSEEK_API_KEY} or {@code MODULES.debug.ai.api-key}. Older jars shipped
     * an OpenCode Zen key here — removed so a missing key fails fast with
     * "nessuna API key" instead of a misleading 401 on the DeepSeek endpoint.
     */
    private static final String BUNDLED_KEY = "";

    /**
     * OpenAI-compatible chat-completions endpoint of DeepSeek.
     *
     * <p>Overridable from config.yml, because nothing below this line is DeepSeek-specific: the
     * request and the reply are plain OpenAI chat-completions. Pointing {@code ai.endpoint} at
     * OpenCode Zen, Gemini's compatibility endpoint, Cerebras, OpenRouter or a local Ollama —
     * and setting the matching {@code ai.model} and {@code ai.api-key} — moves the whole
     * feature to that provider without touching this class.
     */
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";

    /**
     * DeepSeek's cost-efficient model. Use {@code deepseek-v4-pro} for harder cases.
     * Legacy {@code deepseek-chat}/{@code deepseek-reasoner} ids were retired by DeepSeek
     * on 24/07/2026 — configs still holding them get a 404.
     */
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private static final long DEFAULT_MAX_TOKENS = 16_000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final String SYSTEM_PROMPT = """
            Sei un esperto di server Minecraft (Paper/Spigot) e di sviluppo di plugin Bukkit.
            Ricevi lo stato di un server reale: configurazioni, gamerule, lista plugin, coda di
            log e gli errori catturati dal plugin smp-library, piu' la descrizione di un problema.

            Il tuo compito e' individuare la causa piu' probabile e dire cosa fare, distinguendo
            in modo netto tre casi: (1) il problema e' causato dal plugin smp-library, (2) e'
            causato da un altro plugin o dalla configurazione del server, (3) e' comportamento
            vanilla o una meccanica cambiata dalla versione di Minecraft in uso.

            Rispondi in italiano, in markdown, con questa struttura e nient'altro:

            ## Causa piu' probabile
            Una o due frasi.

            ## Prove
            Elenco puntato: cita il file, la riga di log o la voce di configurazione precisa che
            supporta la conclusione. Se non hai prove per un'ipotesi, dillo invece di inventarle.

            ## smp-library e' coinvolto?
            Si / No / Non determinabile, con una riga di motivazione.

            ## Cosa fare
            Passi concreti e verificabili, in ordine di priorita'. Ogni passo deve essere
            un'azione che un amministratore puo' eseguire e il cui esito puo' controllare.

            ## Cosa non ho potuto verificare
            Elenco di quello che servirebbe per essere sicuri.

            Sii conciso: niente premesse, niente riepiloghi di quello che ti e' stato dato.
            """;

    private final String apiKey;
    private final String keySource;
    private final String endpoint;
    private final String model;
    private final long maxTokens;

    private volatile HttpClient client;

    public AiDiagnostics(String configuredKey, String endpoint, String model, int maxTokens) {
        String fromEnv = firstPresent(System.getenv("DEEPSEEK_API_KEY"), System.getenv("OPENCODE_API_KEY"));
        this.apiKey = firstPresent(fromEnv, configuredKey, BUNDLED_KEY);
        this.keySource = !firstPresent(fromEnv).isEmpty() ? "variabile d'ambiente"
                : !firstPresent(configuredKey).isEmpty() ? "config.yml"
                : !apiKey.isEmpty() ? "compilata nel jar"
                : "assente";
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.maxTokens = maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
    }

    /** The first of the candidates that actually holds something, trimmed; else empty. */
    private static String firstPresent(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        return "";
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    public String model() {
        return model;
    }

    /** Where the key came from, for the dashboard to show — never the key itself. */
    public String keySource() {
        return keySource;
    }

    /** The outcome of one analysis. {@code text} is markdown when {@code ok}, else empty. */
    public record Result(boolean ok, String text, String error, long inputTokens, long outputTokens, long millis) {
        static Result failed(String error) {
            return new Result(false, "", error, 0, 0, 0);
        }
    }

    /**
     * One machine-readable fix step proposed by the planner. Only these four kinds exist —
     * anything else the model invents is dropped by {@link #parsePlan}. {@code MANUAL} is
     * never executed, only shown.
     */
    public record PlannedAction(String kind, String world, String rule, String value,
                                String file, String path, String description, boolean requiresRestart) {}

    /** The outcome of one fix-planning call. {@code actions} is empty when {@code ok} is false. */
    public record PlanResult(boolean ok, List<PlannedAction> actions, String error) {
        static PlanResult failed(String error) {
            return new PlanResult(false, List.of(), error);
        }
    }

    /**
     * Runs one diagnosis. Blocking — call it from a worker thread.
     *
     * @param question what the admin (or the bug report) is asking about
     * @param evidence the redacted server bundle
     */
    public Result run(String question, String evidence) {
        if (!configured()) {
            return Result.failed("nessuna API key configurata: imposta la variabile d'ambiente "
                    + "DEEPSEEK_API_KEY oppure MODULES.debug.ai.api-key in config.yml");
        }

        long started = System.currentTimeMillis();
        try {
            HttpResponse<String> response = post(requestBody(question, evidence));

            if (response.statusCode() != 200) {
                return Result.failed(httpError(response.statusCode(), response.body()));
            }
            return parse(response.body(), started);

        } catch (IOException e) {
            return Result.failed("nessuna connessione all'API: " + safeMessage(e));
        } catch (InterruptedException e) {
            // Restore the flag: the executor's shutdown path is what interrupted us.
            Thread.currentThread().interrupt();
            return Result.failed("analisi interrotta");
        } catch (RuntimeException e) {
            return Result.failed("analisi fallita: " + safeMessage(e));
        }
    }

    /** Shared POST — one chat-completions call. Blocking, throws like the caller expects. */
    private HttpResponse<String> post(String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ fix planner

    private static final String FIX_PLANNER_PROMPT = """
            Sei il pianificatore fix di un plugin Minecraft Paper. Ricevi problema, analisi e stato server.
            Rispondi SOLO con un JSON array valido, niente markdown, niente testo fuori dal JSON. Max 8 azioni.
            Ogni azione ha: {"kind":"GAMERULE|DIFFICULTY|CONFIG|MANUAL","world":"nome o *","rule":"id gamerule","value":"valore","file":"file","path":"chiave","description":"...","requiresRestart":false}
            Regole per kind:
            - GAMERULE: world = nome mondo o "*" per tutti; rule = id gamerule vanilla (es doMobSpawning); value "true"/"false"/numero.
            - DIFFICULTY: world come sopra; value in PEACEFUL/NORMAL/EASY/HARD.
            - CONFIG: hai ACCESSO COMPLETO ai config: qualsiasi .yml/.yaml/.properties del server, inclusi plugins/<plugin>/config.yml e simili (es plugins/Essentials/config.yml, plugins/WorldGuard/config.yml), piu' bukkit.yml, spigot.yml, paper-global.yml, config/paper-world-defaults.yml, server.properties. path = chiave yaml/properties; value corto. MAI credenziali (password/secret/token/api-key). requiresRestart true. Proponi SOLO file che esistono davvero secondo lo stato server fornito.
            - MANUAL: tutto cio' che non puoi sistemare da solo (WorldGuard/regioni, plugin terzi, luce/bioma, verifiche visive): solo description, mai eseguito.
            - requiresRestart true solo per CONFIG. description in italiano, max 160 caratteri, concreta.
            Esempio: [{"kind":"GAMERULE","world":"world","rule":"doMobSpawning","value":"true","description":"Riattiva lo spawn dei mob nel mondo world","requiresRestart":false}]
            Se non sai cosa fare, restituisci un solo MANUAL che dice cosa controllare a mano.
            """;

    /**
     * Asks the model for a machine-readable fix plan for any problem. Blocking — call it
     * from a worker thread. The returned actions are already validated against the
     * allowlist; {@link BugFixer} re-validates again before applying (defense in depth).
     */
    public PlanResult planFixes(String question, String evidence, String analysisText) {
        if (!configured()) {
            return PlanResult.failed("nessuna API key configurata: imposta la variabile d'ambiente "
                    + "DEEPSEEK_API_KEY oppure MODULES.debug.ai.api-key in config.yml");
        }
        try {
            String safeEvidence = evidence == null ? "" : evidence;
            if (safeEvidence.length() > 60_000) {
                safeEvidence = safeEvidence.substring(0, 60_000) + "\n… (stato troncato per il planner)";
            }
            String safeAnalysis = analysisText == null ? "" : analysisText;
            if (safeAnalysis.length() > 8_000) safeAnalysis = safeAnalysis.substring(0, 8_000);
            int planMax = (int) Math.max(1000, Math.min(maxTokens, 2000));
            HttpResponse<String> response = post(planRequestBody(question, safeEvidence, safeAnalysis, planMax));
            if (response.statusCode() != 200) {
                return PlanResult.failed(httpError(response.statusCode(), response.body()));
            }
            return parsePlan(response.body());
        } catch (IOException e) {
            return PlanResult.failed("nessuna connessione all'API: " + safeMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PlanResult.failed("pianificazione interrotta");
        } catch (RuntimeException e) {
            return PlanResult.failed("pianificazione fallita: " + safeMessage(e));
        }
    }

    private String planRequestBody(String question, String evidence, String analysis, int maxOut) {
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", FIX_PLANNER_PROMPT);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "# Problema segnalato\n" + question
                + "\n\n# Analisi precedente\n" + analysis
                + "\n\n# Stato del server\n" + evidence);

        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxOut);
        body.add("messages", messages);
        return body.toString();
    }

    /** Extracts the JSON array (tolerating code fences) and keeps only allowlisted actions. */
    PlanResult parsePlan(String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return PlanResult.failed("risposta dell'API illeggibile (non e' JSON)");
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return PlanResult.failed("il modello ha risposto senza contenuto");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String text = message == null ? "" : string(message, "content");
        if (text.isBlank()) return PlanResult.failed("il modello ha risposto senza testo");

        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return PlanResult.failed("il modello non ha restituito un piano fix (niente JSON array)");
        }
        JsonArray array;
        try {
            array = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonArray();
        } catch (RuntimeException e) {
            return PlanResult.failed("piano fix illeggibile (JSON array non valido)");
        }

        List<PlannedAction> actions = new ArrayList<>();
        int dropped = 0;
        for (JsonElement element : array) {
            if (actions.size() >= 8) break;
            if (!element.isJsonObject()) {
                dropped++;
                continue;
            }
            PlannedAction action = validatePlanned(element.getAsJsonObject());
            if (action == null) dropped++;
            else actions.add(action);
        }
        if (actions.isEmpty()) {
            return PlanResult.failed("il modello non ha proposto fix validi"
                    + (dropped > 0 ? " (" + dropped + " scartati: fuori allowlist)" : ""));
        }
        return new PlanResult(true, List.copyOf(actions), "");
    }

    /** Null when the action is outside the allowlist — the caller counts it as dropped. */
    private static PlannedAction validatePlanned(JsonObject obj) {
        String kind = string(obj, "kind").trim().toUpperCase();
        String description = string(obj, "description").trim().replaceAll("\\s+", " ");
        if (description.isEmpty()) return null;
        if (description.length() > 160) description = description.substring(0, 160);
        boolean restart = false;
        try {
            var flag = obj.has("requiresRestart") ? obj.get("requiresRestart") : null;
            if (flag != null && !flag.isJsonNull() && flag.isJsonPrimitive()) {
                var primitive = flag.getAsJsonPrimitive();
                restart = primitive.isBoolean() ? primitive.getAsBoolean()
                        : primitive.isString() && Boolean.parseBoolean(primitive.getAsString());
            }
        } catch (RuntimeException ignored) {
            restart = false;
        }

        switch (kind) {
            case "MANUAL" -> {
                return new PlannedAction("MANUAL", "", "", "", "", "", description, false);
            }
            case "GAMERULE" -> {
                String world = string(obj, "world").trim();
                String rule = string(obj, "rule").trim();
                String value = string(obj, "value").trim();
                if (world.isEmpty() || world.length() > 64 || rule.isEmpty() || rule.length() > 64
                        || value.isEmpty() || value.length() > 32) return null;
                if (!rule.matches("[A-Za-z0-9_]+")) return null;
                if (!value.matches("(?i)true|false|[0-9]{1,6}")) return null;
                return new PlannedAction("GAMERULE", world, rule, value, "", "", description, false);
            }
            case "DIFFICULTY" -> {
                String world = string(obj, "world").trim();
                String value = string(obj, "value").trim().toUpperCase();
                if (world.isEmpty() || world.length() > 64) return null;
                if (!value.matches("PEACEFUL|NORMAL|EASY|HARD")) return null;
                return new PlannedAction("DIFFICULTY", world, "", value, "", "", description, false);
            }
            case "CONFIG" -> {
                String file = string(obj, "file").trim();
                String path = string(obj, "path").trim();
                String value = string(obj, "value").trim();
                if (!BugFixer.allowedConfigFile(file)) return null;
                if (!path.matches("[A-Za-z0-9._\\-]{1,80}")) return null;
                String lowered = path.toLowerCase();
                if (lowered.contains("password") || lowered.contains("secret") || lowered.contains("token")
                        || lowered.contains("api-key") || lowered.contains("apikey")) return null;
                if (value.isEmpty() || value.length() > 200) return null;
                return new PlannedAction("CONFIG", "", "", value, file, path, description, true);
            }
            default -> {
                return null;
            }
        }
    }

    /** The OpenAI-compatible chat-completions payload. */
    private String requestBody(String question, String evidence) {
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", SYSTEM_PROMPT);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "# Problema segnalato\n" + question
                + "\n\n# Stato del server\n" + evidence);

        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.add("messages", messages);
        return body.toString();
    }

    /** Pulls the markdown and the token counts out of a 200 response. */
    private Result parse(String body, long started) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return Result.failed("risposta dell'API illeggibile (non e' JSON)");
        }

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return Result.failed("il modello ha risposto senza contenuto");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        String finish = string(choice, "finish_reason");

        // A refused or filtered request comes back as a normal 200 with an empty message;
        // reading it blind would look like a successful analysis that says nothing.
        JsonObject message = choice.getAsJsonObject("message");
        String text = message == null ? "" : string(message, "content");
        if (text.isBlank()) {
            return Result.failed("il modello ha risposto senza testo (finish_reason: "
                    + (finish.isEmpty() ? "sconosciuto" : finish) + ")");
        }
        if ("length".equals(finish)) {
            text += "\n\n_(risposta troncata: alza MODULES.debug.ai.max-tokens)_";
        }

        JsonObject usage = root.getAsJsonObject("usage");
        long in = usage == null ? 0 : number(usage, "prompt_tokens");
        long out = usage == null ? 0 : number(usage, "completion_tokens");
        return new Result(true, text, "", in, out, System.currentTimeMillis() - started);
    }

    /** Turns a non-200 into something an admin can act on, preferring the gateway's own text. */
    private String httpError(int status, String body) {
        String detail = "";
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (error != null) detail = string(error, "message");
        } catch (RuntimeException ignored) {
            // Not every gateway error is JSON; the status code alone still says enough.
        }
        String suffix = detail.isBlank() ? "" : ": " + ServerEvidence.redact(detail);
        return switch (status) {
            case 401, 403 -> "API key rifiutata (" + status + "): controlla che sia valida, "
                    + "non revocata, e che appartenga al provider in MODULES.debug.ai.endpoint"
                    + suffix;
            case 404 -> "modello o endpoint sconosciuti (404): controlla "
                    + "MODULES.debug.ai.model e MODULES.debug.ai.endpoint" + suffix;
            case 429 -> "rate limit raggiunto (429): riprova tra qualche minuto" + suffix;
            default -> "l'API ha risposto con un errore (" + status + ")" + suffix;
        };
    }

    private static String string(JsonObject owner, String field) {
        JsonElement value = owner.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject owner, String field) {
        JsonElement value = owner.get(field);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    /** Built on first use so a server that never runs an analysis never builds an HTTP client. */
    private HttpClient client() {
        HttpClient existing = client;
        if (existing != null) return existing;
        synchronized (this) {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .build();
            }
            return client;
        }
    }

    public void close() {
        HttpClient open = client;
        client = null;
        if (open == null) return;
        try {
            open.close();
        } catch (Exception e) {
            Log.warning("debug: chiusura del client HTTP fallita: " + e.getMessage());
        }
    }

    /** Never let a key echo back through an error message. */
    private String safeMessage(Throwable thrown) {
        String message = thrown.getMessage();
        if (message == null || message.isBlank()) return thrown.getClass().getSimpleName();
        String clean = ServerEvidence.redact(message);
        return apiKey.isEmpty() ? clean : clean.replace(apiKey, "***");
    }
}
