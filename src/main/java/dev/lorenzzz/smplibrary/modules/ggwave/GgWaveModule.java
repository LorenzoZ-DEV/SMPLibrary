package dev.lorenzzz.smplibrary.modules.ggwave;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.commands.GgWaveCommand;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Mini;
import dev.lorenzzz.smplibrary.util.SoundCue;
import dev.lorenzzz.smplibrary.util.Ticks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GG wave: MineStore announces a purchase through the LiteBans broadcast
 * ({@code lbans broadcast &2&lSTORE &f&l» &a{username} ...}), and for the next
 * {@code duration} everything anyone types that reads as "gg" comes out coloured, one
 * distinct style per player, with a sound whose pitch climbs as the wave builds.
 *
 * <p>The trigger is matched on the command <em>before</em> it runs (and on the broadcast
 * text as a second net), so nothing has to be changed on the MineStore side: the store
 * keeps firing exactly the command it fires today.
 *
 * <p>Chat is rewritten through ZelChat when that plugin is installed — see
 * {@link ZelChatHook} — so the wave keeps the server's own chat format, ranks and prefixes
 * instead of replacing them. Without ZelChat the plain {@code AsyncChatEvent} path in
 * {@link GgWaveChatListener} does the same job.
 *
 * <p>Configured under {@code MODULES.ggwave}; texts live under {@code gg-wave.*} in
 * messages.yml.
 */
public final class GgWaveModule extends AbstractModule implements Listener {

    public static final String ADMIN_PERMISSION = "smplibrary.ggwave.admin";

    /**
     * Legacy colour codes in every spelling the store command can carry: {@code &#RRGGBB},
     * the {@code &x&R&R&G&G&B&B} run, and the plain {@code &a} / {@code §a} pair. Stripped
     * before matching so the configured regex describes the <em>text</em> of the broadcast
     * rather than whichever colours happen to be painted on it today.
     */
    private static final Pattern COLOR_CODES =
            Pattern.compile("(?i)[&§](#[0-9a-f]{6}|x(?:[&§][0-9a-f]){6}|[0-9a-fk-or])");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Named group a trigger regex may use to say which part of the line is the buyer. */
    private static final String USERNAME_GROUP = "username";
    private static final Pattern NAMED_USERNAME = Pattern.compile("\\(\\?<" + USERNAME_GROUP + ">");

    private static final long DEFAULT_DURATION = 30 * Ticks.SECOND;
    private static final long MINIMUM_DURATION = 3 * Ticks.SECOND;

    /**
     * How long to keep asking ZelChat for its API before settling for the Bukkit chat path —
     * ten seconds, which is far more than a plugin needs to finish starting and still short
     * enough that a genuinely broken hook is reported while the console is being read.
     */
    private static final int CHAT_ATTACH_ATTEMPTS = 10;
    private static final long CHAT_ATTACH_RETRY_TICKS = Ticks.SECOND;

    private static final SoundCue DEFAULT_START = SoundCue.of("entity.ender_dragon.growl", 0.9f, 1.2f);
    private static final SoundCue DEFAULT_GG = SoundCue.of("entity.experience_orb.pickup", 0.8f, 1.0f);
    private static final SoundCue DEFAULT_END = SoundCue.of("ui.toast.challenge_complete", 0.8f, 1.0f);

    /** Used when {@code styles} is missing or empty — a wave with no colours is not a wave. */
    private static final List<String> DEFAULT_STYLES = List.of(
            "<gradient:#ff4d4d:#ffb020><bold><gg></bold></gradient>",
            "<gradient:#38f9d7:#43e97b><bold><gg></bold></gradient>",
            "<gradient:#a18cd1:#fbc2eb><bold><gg></bold></gradient>",
            "<gradient:#f6d365:#fda085><bold><gg></bold></gradient>",
            "<gradient:#4facfe:#00f2fe><bold><gg></bold></gradient>",
            "<rainbow><bold><gg></bold></rainbow>");

    private final List<Pattern> commandTriggers = new ArrayList<>();
    private final List<Pattern> broadcastTriggers = new ArrayList<>();
    private final List<String> hookedCommands = new ArrayList<>();
    private final List<String> styles = new ArrayList<>();

    private Pattern ggPattern;
    private long durationTicks = DEFAULT_DURATION;
    private long triggerCooldownMillis = 5_000L;
    private boolean countOncePerPlayer = true;
    private boolean showTitle = true;
    private float pitchStep = 0.05f;
    private float pitchMax = 2.0f;

    private SoundCue startSound = DEFAULT_START;
    private SoundCue ggSound = DEFAULT_GG;
    private SoundCue endSound = DEFAULT_END;

    // Chat arrives off the main thread, the triggers arrive on it: everything shared
    // between the two is either volatile, concurrent or guarded below.
    private volatile Wave current;
    private int endTaskId = -1;
    private String lastBuyer = "";
    private long lastTriggerAt;

    private final StoreCommandWatcher watcher = new StoreCommandWatcher();
    private Runnable zelChatUninstall;
    private GgWaveCommand command;

    /**
     * Which chat path ended up live, and how many messages it has actually handed us. Both
     * exist for {@code /ggwave status}: "the wave ran and nobody's gg was counted" has
     * several possible causes and they are indistinguishable from in-game without this.
     */
    private volatile String chatPath = "non ancora deciso";
    private final AtomicInteger seenMessages = new AtomicInteger();

    public GgWaveModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "ggwave"; }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        load(config());
        listen(this);

        command = new GgWaveCommand();
        if (commands != null) commands.register(command);

        C.info("GG wave enabled — " + (durationTicks / Ticks.SECOND) + "s per onda, "
                + styles.size() + " stili.");

        // A tick later, not now. Neither of these can be done at enable time: our plugin may
        // well start before ZelChat and LiteBans — declaring them in softdepend orders the
        // load, but nothing orders a plugin that finishes setting itself up later — and at
        // enable time their API is not published and their commands are not in the map. The
        // first scheduler tick runs once every plugin is up, and no chat can happen before it.
        // Two separate tasks, not two statements in one. They used to share a task, and when
        // the command hook threw the exception escaped the Runnable and the chat was never
        // wired at all — the wave ran with no path to chat, silently. Independent tasks (and
        // the guards inside them) mean the wave keeps whatever still works.
        later(() -> attachChat(1), 1L);
        later(this::hookCommands, 1L);
    }

    /**
     * Wires the chat path, retrying while ZelChat is still starting.
     *
     * <p>The first attempt used to be the only one, and on a boot where ZelChat published its
     * API after us it failed with "API is not set!" and the wave silently spent the whole
     * session on the fallback path — losing the server's chat format for every gg.
     */
    private void attachChat(int attempt) {
        ZelChatHook.Attachment attachment;
        try {
            attachment = ZelChatHook.install(plugin, this);
        } catch (Throwable failed) {
            // install() already swallows its own failures; this is the belt to that braces.
            // Whatever happens, chat must end up wired to something before this returns.
            C.warning("ggwave: aggancio a ZelChat interrotto (" + failed + ").");
            attachment = ZelChatHook.Attachment.GIVE_UP;
        }

        if (attachment.attached()) {
            zelChatUninstall = attachment.detach();
            chatPath = "ZelChat";
            C.info("GG wave — chat agganciata a ZelChat"
                    + (attempt > 1 ? " (al tentativo " + attempt + ")" : "") + ".");
            return;
        }

        if (attachment.retry() && attempt < CHAT_ATTACH_ATTEMPTS) {
            chatPath = "in attesa di ZelChat (tentativo " + attempt + ")";
            later(() -> attachChat(attempt + 1), CHAT_ATTACH_RETRY_TICKS);
            return;
        }
        if (attachment.retry()) {
            C.warning("ggwave: " + ZelChatHook.pendingReason() + " dopo " + attempt
                    + " tentativi — la chat passa da AsyncChatEvent.");
        }

        listen(new GgWaveChatListener(this));
        chatPath = "AsyncChatEvent";
        C.info("GG wave — chat gestita via AsyncChatEvent.");
    }

    /** Which chat path is live — {@code /ggwave status} and the end-of-wave warning use it. */
    public String chatPath() {
        return chatPath;
    }

    /** How many chat messages either path has handed to {@link #decorate} since boot. */
    public int seenMessages() {
        return seenMessages.get();
    }

    private void hookCommands() {
        List<String> wrapped = watcher.hook(hookedCommands,
                line -> matchTriggers(commandTriggers, line, true));
        if (!wrapped.isEmpty()) {
            C.info("GG wave — comandi osservati per l'annuncio dello store: "
                    + String.join(", ", wrapped) + ".");
        } else if (!hookedCommands.isEmpty()) {
            C.warning("ggwave: nessuno dei comandi in triggers.watch-commands ("
                    + String.join(", ", hookedCommands) + ") e' registrato su questo server."
                    + " L'onda partira' solo se l'annuncio passa da console/RCON o dal broadcast.");
        }
    }

    @Override
    protected void onShutdown() {
        current = null;
        endTaskId = -1;
        watcher.unhook();
        if (commands != null && command != null) commands.unregister(command);
        command = null;
        if (zelChatUninstall != null) {
            zelChatUninstall.run();
            zelChatUninstall = null;
        }
    }

    private void load(ConfigurationSection cfg) {
        // An unconfigured module still has to work: every read below falls back to its default.
        if (cfg == null) cfg = new MemoryConfiguration();

        durationTicks = Math.max(MINIMUM_DURATION,
                Math.max(1, cfg.getInt("duration-seconds", 30)) * Ticks.SECOND);
        triggerCooldownMillis = Math.max(0, cfg.getInt("trigger-cooldown-seconds", 5)) * 1000L;
        countOncePerPlayer = cfg.getBoolean("count-once-per-player", true);
        showTitle = cfg.getBoolean("title", true);

        loadTriggers(cfg);
        loadGgPattern(cfg);

        styles.clear();
        styles.addAll(cfg.getStringList("styles"));
        styles.removeIf(style -> style == null || style.isBlank());
        if (styles.isEmpty()) styles.addAll(DEFAULT_STYLES);

        ConfigurationSection sounds = cfg.getConfigurationSection("sounds");
        startSound = SoundCue.from(sounds == null ? null : sounds.getConfigurationSection("start"), DEFAULT_START);
        ggSound = SoundCue.from(sounds == null ? null : sounds.getConfigurationSection("gg"), DEFAULT_GG);
        endSound = SoundCue.from(sounds == null ? null : sounds.getConfigurationSection("end"), DEFAULT_END);
        pitchStep = (float) (sounds == null ? 0.05 : sounds.getDouble("pitch-step", 0.05));
        pitchMax = (float) (sounds == null ? 2.0 : sounds.getDouble("pitch-max", 2.0));
    }

    private void loadTriggers(ConfigurationSection cfg) {
        commandTriggers.clear();
        broadcastTriggers.clear();
        compileInto(cfg.getStringList("triggers.commands"), commandTriggers, "triggers.commands");
        compileInto(cfg.getStringList("triggers.broadcasts"), broadcastTriggers, "triggers.broadcasts");

        // An explicit empty list turns the command-map wrapping off; an absent one is a config
        // written before the setting existed, and still wants the default.
        hookedCommands.clear();
        if (!commandTriggers.isEmpty()) {
            hookedCommands.addAll(cfg.isSet("triggers.watch-commands")
                    ? cfg.getStringList("triggers.watch-commands")
                    : List.of("lbans", "litebans"));
        }

        if (commandTriggers.isEmpty() && broadcastTriggers.isEmpty()) {
            C.warning("ggwave: nessun trigger valido configurato — l'onda partira' solo con /ggwave.");
        }
    }

    private void compileInto(List<String> raw, List<Pattern> target, String where) {
        for (String expression : raw) {
            if (expression == null || expression.isBlank()) continue;
            try {
                target.add(Pattern.compile(expression));
            } catch (Exception e) {
                C.warning("ggwave: regex non valida in " + where + " (" + expression + "): " + e.getMessage());
            }
        }
    }

    /**
     * What counts as a "gg". Built from the word list rather than written by hand so a server
     * owner adds {@code ggwp} without having to know regex; {@code pattern} is there for the
     * ones who do. The word boundaries are what keep a name like {@code GG_Master} out of it.
     */
    private void loadGgPattern(ConfigurationSection cfg) {
        String override = cfg.getString("pattern", "");
        if (override != null && !override.isBlank()) {
            try {
                ggPattern = Pattern.compile(override, Pattern.CASE_INSENSITIVE);
                return;
            } catch (Exception e) {
                C.warning("ggwave: pattern non valido (" + override + "): " + e.getMessage());
            }
        }

        List<String> words = new ArrayList<>(cfg.getStringList("words"));
        words.removeIf(word -> word == null || word.isBlank());
        if (words.isEmpty()) words = List.of("gg", "ggs", "ggwp");

        StringBuilder alternatives = new StringBuilder();
        for (String word : words) {
            if (!alternatives.isEmpty()) alternatives.append('|');
            alternatives.append(Pattern.quote(word.trim()));
        }
        // "gggggg" is the same enthusiasm as "gg" and players type it constantly.
        if (cfg.getBoolean("match-repeated-g", true)) alternatives.append("|g{2,}");

        ggPattern = Pattern.compile("\\b(?:" + alternatives + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    // --------------------------------------------------------------- triggers

    /**
     * The store command, caught before it runs. {@code RemoteServerCommandEvent} (RCON, which
     * is how some MineStore setups deliver commands) extends this one, so both arrive here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        matchTriggers(commandTriggers, event.getCommand(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        matchTriggers(commandTriggers, event.getMessage(), true);
    }

    /** Last net: an announcement that reaches chat without passing through a command at all. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent event) {
        matchTriggers(broadcastTriggers,
                PlainTextComponentSerializer.plainText().serialize(event.message()), false);
    }

    /** Runs {@code line} past every pattern; the first hit starts the wave. */
    private void matchTriggers(List<Pattern> patterns, String line, boolean command) {
        if (patterns.isEmpty() || line == null || line.isEmpty()) return;

        String clean = command ? normaliseCommand(line) : normalise(line);
        if (clean.isEmpty()) return;

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(clean);
            if (matcher.find()) {
                trigger(buyerOf(pattern, matcher));
                return;
            }
        }
    }

    /** Colour codes out, runs of whitespace collapsed. */
    private static String normalise(String line) {
        String stripped = COLOR_CODES.matcher(line).replaceAll("");
        return SPACES.matcher(stripped).replaceAll(" ").trim();
    }

    /**
     * {@link #normalise} plus the two spellings only a command line has: the leading slash a
     * player types, and the {@code plugin:} namespace the server puts in front of an alias.
     * A pattern written for {@code lbans broadcast …} should not have to also match
     * {@code /litebans:lbans broadcast …}.
     */
    private static String normaliseCommand(String line) {
        String clean = normalise(line);
        if (clean.startsWith("/")) clean = clean.substring(1).trim();

        int space = clean.indexOf(' ');
        int colon = clean.indexOf(':');
        if (colon > 0 && (space < 0 || colon < space)) clean = clean.substring(colon + 1);
        return clean;
    }

    /**
     * The buyer name: the {@code username} group when the pattern declares one, otherwise
     * the first capturing group, otherwise nothing — a wave with no name still runs.
     */
    private static String buyerOf(Pattern pattern, Matcher matcher) {
        // The source is checked rather than just asking for the group: group("username") on a
        // pattern that has no such group is an IllegalArgumentException, and a hand-written
        // regex in config.yml must not be able to throw out of an event handler.
        if (NAMED_USERNAME.matcher(pattern.pattern()).find()) {
            try {
                String named = matcher.group(USERNAME_GROUP);
                if (named != null && !named.isBlank()) return named;
            } catch (IllegalArgumentException noSuchGroup) {
                // The text "(?<username>" was in the pattern without being a real group.
            }
        }
        if (matcher.groupCount() >= 1) {
            String first = matcher.group(1);
            if (first != null && !first.isBlank()) return first;
        }
        return "";
    }

    /**
     * Starts a wave for {@code buyer} unless the same purchase just started one. Both nets
     * above can see a single purchase — the command and then the broadcast it produces — and
     * without this the announcement would go out twice, a second apart.
     */
    private void trigger(String buyer) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (buyer.equalsIgnoreCase(lastBuyer) && now - lastTriggerAt < triggerCooldownMillis) return;
            lastBuyer = buyer;
            lastTriggerAt = now;
        }
        // One tick later, so the store's own announcement is already in chat when the wave
        // opens — the two lines read as cause and effect instead of arriving the wrong way round.
        later(() -> start(buyer, durationTicks), 1L);
    }

    // ------------------------------------------------------------- the wave

    /**
     * Opens the wave, or extends the one already running — a second purchase mid-wave is an
     * occasion to celebrate again, not a reason to cut the first celebration short. Players
     * who already have a colour keep it.
     *
     * @param buyer who paid; may be empty
     * @param ticks how long the wave lasts from now
     */
    public void start(String buyer, long ticks) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> start(buyer, ticks));
            return;
        }

        Wave wave = current;
        if (wave == null) {
            wave = new Wave(styles, seenMessages.get());
            current = wave;
        }
        wave.buyer(buyer);
        wave.endsAt(System.currentTimeMillis() + ticks / Ticks.SECOND * 1000L);

        cancelTask(endTaskId);
        Wave started = wave;
        endTaskId = later(() -> finish(started), ticks);

        Map<String, String> placeholders = placeholders(wave);
        placeholders.put("seconds", String.valueOf(Math.max(1, ticks / Ticks.SECOND)));
        Mini.broadcast(messages, "gg-wave.start", placeholders);
        if (showTitle) showTitle(placeholders);
        startSound.playToAll();
    }

    /** Skipped entirely when neither key is filled in — an empty title still blanks the screen. */
    private void showTitle(Map<String, String> placeholders) {
        boolean hasTitle = messages.has("gg-wave.title");
        boolean hasSubtitle = messages.has("gg-wave.subtitle");
        if (!hasTitle && !hasSubtitle) return;

        Component title = hasTitle ? Mini.render(messages, "gg-wave.title", placeholders) : Component.empty();
        Component subtitle = hasSubtitle
                ? Mini.render(messages, "gg-wave.subtitle", placeholders) : Component.empty();

        Title card = Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400)));
        for (Player player : Bukkit.getOnlinePlayers()) player.showTitle(card);
    }

    /** Closes {@code wave} — ignored when it has already been replaced by a later one. */
    private void finish(Wave wave) {
        if (current != wave) return;
        current = null;
        endTaskId = -1;

        Map<String, String> placeholders = placeholders(wave);
        Mini.broadcast(messages, wave.total() == 0 ? "gg-wave.end-empty" : "gg-wave.end", placeholders);
        endSound.playToAll();

        if (wave.total() == 0) reportSilentWave(wave);
    }

    /**
     * A wave that counted nothing is usually just a quiet server — but if chat happened
     * during it and none of it reached us, the chat path is wired wrong and the console is
     * the only place that can say so. Says it once per wave, and only when there is
     * something to say.
     */
    private void reportSilentWave(Wave wave) {
        if (seenMessages.get() > wave.seenAtStart()) return; // messages arrived, none said gg

        C.warning("ggwave: onda finita senza vedere un solo messaggio di chat (percorso: "
                + chatPath + "). Se qualcuno ha scritto mentre era in corso, la chat non sta"
                + " passando da qui: con ZelChat installato serve l'aggancio all'API (cerca"
                + " 'chat agganciata a ZelChat' all'avvio), altrimenti un altro plugin sta"
                + " consumando l'evento prima. /ggwave status mostra lo stato.");
    }

    private Map<String, String> placeholders(Wave wave) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", wave.buyer());
        placeholders.put("count", String.valueOf(wave.total()));
        placeholders.put("players", String.valueOf(wave.participants()));
        return placeholders;
    }

    /** The configured wave length in ticks — what {@code /ggwave} uses when given no number. */
    public long defaultDurationTicks() {
        return durationTicks;
    }

    /** True while a wave is live — read by the chat paths before they do any work. */
    public boolean active() {
        return current != null;
    }

    /** Seconds left in the live wave, 0 when none is running. Used by {@code /ggwave}. */
    public long secondsLeft() {
        Wave wave = current;
        if (wave == null) return 0L;
        return Math.max(0L, (wave.endsAt() - System.currentTimeMillis()) / 1000L);
    }

    /** Ends the live wave early, as if its timer had just run out. */
    public void stop() {
        Wave wave = current;
        if (wave == null) return;
        cancelTask(endTaskId);
        finish(wave);
    }

    // ----------------------------------------------------------------- chat

    /**
     * The one entry point both chat paths use: returns the recoloured message, or
     * {@code null} when this message is none of the wave's business — no wave running, or
     * nothing in it that reads as a gg.
     *
     * <p>{@code message} is the component that will actually be shown, which under ZelChat
     * carries the whole format (prefix, rank, name). Only the gg tokens inside it are
     * replaced, so the format survives untouched.
     *
     * @param player who typed it
     * @param raw    the plain text they typed
     * @param message the component about to be sent
     */
    public Component decorate(Player player, String raw, Component message) {
        seenMessages.incrementAndGet();

        Wave wave = current;
        if (wave == null || player == null || message == null) return null;
        if (raw == null || raw.isBlank() || ggPattern == null) return null;
        if (!ggPattern.matcher(raw).find()) return null;

        String template = wave.styleFor(player.getUniqueId());
        Component styled = message.replaceText(builder -> builder
                .match(ggPattern)
                .replacement((result, ignored) ->
                        C.mm(template, Placeholder.unparsed("gg", result.group()))));

        boolean first = wave.countPlayer(player.getUniqueId());
        int total = wave.hit();
        if (first || !countOncePerPlayer) playCombo(total);
        return styled;
    }

    /** One note per gg, climbing as the wave builds. Scheduled: chat is not the main thread. */
    private void playCombo(int combo) {
        if (ggSound.silent() || !plugin.isEnabled()) return;
        float pitch = Math.min(pitchMax, ggSound.pitch() + pitchStep * (combo - 1));
        SoundCue cue = new SoundCue(ggSound.sound(), ggSound.volume(), pitch);
        if (Bukkit.isPrimaryThread()) {
            cue.playToAll();
        } else {
            Bukkit.getScheduler().runTask(plugin, cue::playToAll);
        }
    }

    /**
     * One live wave. Every field is touched from the chat threads as well as the main one.
     *
     * <p>The style list is shuffled once per wave and handed out in order, so the players who
     * join a wave get <em>different</em> colours rather than randomly colliding ones, and each
     * of them keeps the colour they were given until the wave ends.
     */
    private static final class Wave {

        private final List<String> palette;
        private final Map<UUID, String> assigned = new ConcurrentHashMap<>();
        private final Set<UUID> counted = ConcurrentHashMap.newKeySet();
        private final AtomicInteger nextStyle = new AtomicInteger();
        private final AtomicInteger total = new AtomicInteger();

        private volatile String buyer = "";
        private volatile long endsAt;

        /** Messages the chat path had handled when this wave opened — see reportSilentWave. */
        private final int seenAtStart;

        private Wave(List<String> styles, int seenAtStart) {
            List<String> shuffled = new ArrayList<>(styles);
            Collections.shuffle(shuffled);
            this.palette = List.copyOf(shuffled);
            this.seenAtStart = seenAtStart;
        }

        private int seenAtStart() { return seenAtStart; }

        private String styleFor(UUID playerId) {
            return assigned.computeIfAbsent(playerId,
                    key -> palette.get(Math.floorMod(nextStyle.getAndIncrement(), palette.size())));
        }

        /** Records the player; true the first time they say gg in this wave. */
        private boolean countPlayer(UUID playerId) {
            return counted.add(playerId);
        }

        private int hit() { return total.incrementAndGet(); }
        private int total() { return total.get(); }
        private int participants() { return counted.size(); }
        private String buyer() { return buyer; }
        private long endsAt() { return endsAt; }

        private void buyer(String value) {
            if (value != null && !value.isBlank()) this.buyer = value;
        }

        private void endsAt(long value) { this.endsAt = value; }
    }

    @Override
    public Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("enabled", true);
        defaults.put("duration-seconds", 30);
        defaults.put("trigger-cooldown-seconds", 5);
        defaults.put("count-once-per-player", true);
        defaults.put("title", true);
        defaults.put("match-repeated-g", true);
        defaults.put("words", List.of("gg", "ggs", "ggwp"));
        defaults.put("styles", DEFAULT_STYLES);
        defaults.put("triggers.commands", List.of(
                "(?i)^/?l(?:ite)?bans\\s+broadcast\\b.*?\\bSTORE\\b\\s*»\\s*(?<username>\\S+)"));
        defaults.put("triggers.watch-commands", List.of("lbans", "litebans"));
        defaults.put("triggers.broadcasts", List.of(
                "(?i)\\bSTORE\\b\\s*»\\s*(?<username>\\S+)\\s+ha appena supportato"));
        return defaults;
    }
}
