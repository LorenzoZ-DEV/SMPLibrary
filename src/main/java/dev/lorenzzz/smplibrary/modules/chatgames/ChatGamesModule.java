package dev.lorenzzz.smplibrary.modules.chatgames;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.util.Log;
import dev.lorenzzz.smplibrary.util.SoundCue;
import dev.lorenzzz.smplibrary.util.Ticks;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Periodic chat mini-games (scrambled word, arithmetic, trivia). One round is live at a
 * time; the first player to type a matching answer wins and the configured reward command
 * runs for them. Rounds expire after {@code time-limit} minutes.
 *
 * <p>All player-facing text lives in {@code chat-games.*} in messages.yml.
 */
public final class ChatGamesModule extends AbstractModule implements Listener {

    private static final long DEFAULT_PERIOD = 30 * Ticks.MINUTE;
    private static final long MINIMUM_PERIOD = Ticks.MINUTE;
    private static final int HISTORY_LIMIT = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** A live round: the accepted (lower-cased) answers plus the placeholders its messages use. */
    private record Round(ChatGameType type, Set<String> answers, String display, Map<String, String> placeholders) {}

    // Defaults chosen to be audible without being obnoxious; override under MODULES.chatgames.sounds.
    private static final SoundCue DEFAULT_START = SoundCue.of("block.note_block.pling", 0.8f, 1.2f);
    private static final SoundCue DEFAULT_WIN = SoundCue.of("entity.player.levelup", 0.7f, 1.4f);
    private static final SoundCue DEFAULT_TIMEOUT = SoundCue.of("block.note_block.bass", 0.7f, 0.8f);

    private final List<ChatGameType> availableTypes = new ArrayList<>();
    private final Deque<Map<String, String>> recentGames = new ArrayDeque<>();

    private SoundCue startSound = DEFAULT_START;
    private SoundCue winSound = DEFAULT_WIN;
    private SoundCue timeoutSound = DEFAULT_TIMEOUT;

    private volatile Round current;
    private volatile long roundEndsAt;
    private int timeoutTaskId = -1;

    public ChatGamesModule(Smp_library plugin) {
        super(plugin);
    }

    @Override public String id() { return "chatgames"; }

    public ChatGameType currentType() {
        Round round = current;
        return round == null ? null : round.type();
    }

    public String currentAnswer() {
        Round round = current;
        return round == null ? null : round.display();
    }

    /** What players are asked right now (the scrambled word or the question), or {@code null}. */
    public String currentPrompt() {
        Round round = current;
        return round == null ? null : round.placeholders().get("%question%");
    }

    /** Epoch millis at which the live round expires, or 0 when no round is running. */
    public long roundEndsAt() {
        return current == null ? 0L : roundEndsAt;
    }

    public synchronized List<Map<String, String>> recentGames() {
        return List.copyOf(recentGames);
    }

    @Override
    public void onEnable() {
        ConfigurationSection cfg = config();
        if (cfg == null) return;

        loadTypes(cfg.getStringList("game-types"));
        loadSounds(cfg.getConfigurationSection("sounds"));
        listen(this);

        long period = Ticks.parseAtLeast(cfg.getString("task-every"), DEFAULT_PERIOD, MINIMUM_PERIOD);
        repeat(this::startGame, period, period);
        Log.info("Chat games enabled — first round in " + (period / Ticks.MINUTE) + " min ("
                + availableTypes.size() + " type(s) available).");
    }

    private void loadTypes(List<String> configured) {
        availableTypes.clear();
        for (String name : configured) {
            try {
                availableTypes.add(ChatGameType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                Log.warning("Unknown chat game type: " + name);
            }
        }
        if (availableTypes.isEmpty()) availableTypes.add(ChatGameType.WORD);
    }

    /** Sounds are optional: with no {@code sounds} block the defaults above still play. */
    private void loadSounds(ConfigurationSection sounds) {
        if (sounds == null) return;
        startSound = SoundCue.from(sounds.getConfigurationSection("start"), DEFAULT_START);
        winSound = SoundCue.from(sounds.getConfigurationSection("win"), DEFAULT_WIN);
        timeoutSound = SoundCue.from(sounds.getConfigurationSection("timeout"), DEFAULT_TIMEOUT);
    }

    // ------------------------------------------------------------- the rounds

    private void startGame() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return; // nobody to play against
        startRound();
    }

    /**
     * Starts a round now, replacing any round in progress. Returns the type that was
     * started, or {@code null} when nothing could be built from the config.
     */
    public ChatGameType startRound() {
        ConfigurationSection cfg = config();
        if (cfg == null) return null;

        Round round = buildAnyRound(cfg);
        if (round == null) {
            Log.warning("chatgames: none of the configured game types has usable data — check words/trivia in config.yml.");
            return null;
        }

        int timeLimit = Math.max(1, cfg.getInt("time-limit", 3));
        round.placeholders().put("%type%", typeLabel(round.type()));
        round.placeholders().put("%time_left%", String.valueOf(timeLimit));

        openRound(round, timeLimit * Ticks.MINUTE);
        record("started", round.type().name(), timeLimit + "m");

        messages.broadcast("chat-games.message-box", round.placeholders());
        messages.broadcast("chat-games." + round.type().name().toLowerCase(Locale.ROOT) + "-prompt",
                round.placeholders());
        startSound.playToAll();
        return round.type();
    }

    /**
     * Picks a random type that can actually produce a round. Trying only one type meant a
     * TRIVIA pick with an empty question list silently skipped the whole cycle.
     */
    private Round buildAnyRound(ConfigurationSection cfg) {
        List<ChatGameType> candidates = new ArrayList<>(availableTypes);
        Collections.shuffle(candidates);
        for (ChatGameType type : candidates) {
            Round round = switch (type) {
                case WORD -> buildWordRound(cfg);
                case MATH -> buildMathRound(cfg);
                case TRIVIA -> buildTriviaRound(cfg);
            };
            if (round != null) return round;
        }
        return null;
    }

    /**
     * Publishes the round and arms its expiry under the same lock {@link #claim} and
     * {@link #timeout} take. Any previous timer is cancelled first: a long time-limit with a
     * short task-every used to leave an old timer running, which then expired the new round.
     */
    private synchronized void openRound(Round round, long timeoutTicks) {
        if (timeoutTaskId != -1) {
            cancelTask(timeoutTaskId);
            timeoutTaskId = -1;
        }
        current = round;
        roundEndsAt = System.currentTimeMillis() + timeoutTicks / Ticks.SECOND * 1000L;
        timeoutTaskId = later(() -> timeout(round), timeoutTicks);
    }

    private Round buildWordRound(ConfigurationSection cfg) {
        List<String> words = cfg.getStringList("words");
        if (words.isEmpty()) return null;

        String word = words.get(ThreadLocalRandom.current().nextInt(words.size()));
        String prompt = cfg.getBoolean("scramble", true)
                ? scramble(word)
                : "_ ".repeat(Math.max(1, word.length())).trim();

        return round(ChatGameType.WORD, Set.of(word.toLowerCase(Locale.ROOT)), word, prompt);
    }

    private Round buildMathRound(ConfigurationSection cfg) {
        ConfigurationSection math = cfg.getConfigurationSection("math");
        int max = Math.max(2, math == null ? 50 : math.getInt("max-number", 50));
        String operations = math == null ? "+-" : math.getString("operations", "+-");
        if (operations.isEmpty()) operations = "+-";

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int left = random.nextInt(1, max + 1);
        int right = random.nextInt(1, max + 1);
        char operation = operations.charAt(random.nextInt(operations.length()));

        int result;
        String symbol;
        switch (operation) {
            case '-' -> {
                // keep the answer non-negative
                int high = Math.max(left, right);
                int low = Math.min(left, right);
                left = high; right = low; result = high - low; symbol = "-";
            }
            case '*' -> { result = left * right; symbol = "×"; }
            case '/' -> {
                // Build the division backwards so it always divides evenly. The divisor is kept
                // small, otherwise max-number: 100 produced questions like "9801 ÷ 99".
                right = random.nextInt(2, Math.min(max, 12) + 1);
                result = random.nextInt(1, max + 1);
                left = result * right;
                symbol = "÷";
            }
            default -> { result = left + right; symbol = "+"; }
        }

        return round(ChatGameType.MATH, Set.of(String.valueOf(result)), String.valueOf(result),
                left + " " + symbol + " " + right);
    }

    private Round buildTriviaRound(ConfigurationSection cfg) {
        List<Map<?, ?>> questions = cfg.getMapList("trivia.questions");
        if (questions.isEmpty()) return null;

        Map<?, ?> entry = questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
        if (!(entry.get("question") instanceof String question)) return null;
        if (!(entry.get("answers") instanceof List<?> rawAnswers) || rawAnswers.isEmpty()) return null;

        // LinkedHashSet: the first configured answer is the one shown when the round is won.
        Set<String> answers = rawAnswers.stream()
                .map(String::valueOf)
                .map(answer -> answer.trim().toLowerCase(Locale.ROOT))
                .filter(answer -> !answer.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (answers.isEmpty()) return null;

        return round(ChatGameType.TRIVIA, answers, answers.iterator().next(), question);
    }

    /**
     * Builds a round. Both {@code %word%} and {@code %question%} are always defined and both
     * carry the <em>prompt</em> — the scrambled word, the sum, the trivia question — so a
     * template written with either one works for every game type. Defining only one of them
     * printed a literal "%word%" to players whenever a math round used a word-style template.
     * The answer only enters the map in {@link #announceWinner}, so no template leaks it early.
     */
    private Round round(ChatGameType type, Set<String> answers, String display, String prompt) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%word%", prompt);
        placeholders.put("%question%", prompt);
        return new Round(type, answers, display, placeholders);
    }

    private String typeLabel(ChatGameType type) {
        String path = "chat-games.type." + type.name().toLowerCase(Locale.ROOT);
        if (messages.has(path)) return messages.raw(path);
        // WORD keeps its legacy config-driven label.
        ConfigurationSection cfg = config();
        return type == ChatGameType.WORD && cfg != null ? cfg.getString("type", "Parole") : type.name();
    }

    // ------------------------------------------------------------- resolution

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Round round = current;
        if (round == null) return;

        String guess = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim().toLowerCase(Locale.ROOT);
        if (guess.isEmpty() || !round.answers().contains(guess)) return;

        if (claim(round, event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    /** Closes the round for exactly one winner; false when someone else got there first. */
    private synchronized boolean claim(Round round, String winner) {
        if (current != round) return false;
        current = null;
        roundEndsAt = 0L;
        cancelTask(timeoutTaskId);
        timeoutTaskId = -1;
        record("won", round.type().name(), winner + " -> " + round.display());

        // Chat events are async — rewards and broadcasts belong on the main thread.
        Bukkit.getScheduler().runTask(plugin, () -> announceWinner(round, winner));
        return true;
    }

    private void announceWinner(Round round, String winner) {
        ConfigurationSection cfg = config();
        String reward = cfg == null ? "" : cfg.getString("reward", "");
        if (!reward.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.replace("%player%", winner));
        }

        Map<String, String> placeholders = new LinkedHashMap<>(round.placeholders());
        placeholders.put("%word%", round.display());
        placeholders.put("%player%", winner);
        placeholders.put("%player_name%", winner);
        placeholders.put("%type%", typeLabel(round.type()));

        messages.broadcast("chat-games.message-win-player", placeholders);
        messages.broadcast("chat-games.message-win-player-chat.chatgames-win", placeholders);
        winSound.playToAll();
    }

    /** Expires {@code round} — ignored if it already ended, so a stale timer can't cut a later round short. */
    private synchronized void timeout(Round round) {
        if (current != round) return;

        current = null;
        roundEndsAt = 0L;
        timeoutTaskId = -1;
        record("timeout", round.type().name(), round.display());

        Map<String, String> placeholders = new LinkedHashMap<>(round.placeholders());
        placeholders.put("%word%", round.display()); // lets the timeout message reveal the answer
        messages.broadcast("chat-games.message-timeout", placeholders);
        timeoutSound.playToAll();
    }

    // ----------------------------------------------------------------- extras

    /** Shuffles the word, retrying so the "scrambled" prompt is never the original. */
    private static String scramble(String word) {
        if (word.length() < 2) return word;
        char[] chars = word.toCharArray();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            for (int i = chars.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                char swap = chars[i];
                chars[i] = chars[j];
                chars[j] = swap;
            }
            if (!new String(chars).equalsIgnoreCase(word)) break;
        }
        return new String(chars);
    }

    /** Ring buffer of the last rounds, shown by the web dashboard. */
    private synchronized void record(String event, String type, String detail) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(TIME));
        entry.put("event", event);
        entry.put("type", type);
        entry.put("detail", detail);
        recentGames.addFirst(entry);
        while (recentGames.size() > HISTORY_LIMIT) recentGames.removeLast();
    }

    @Override
    protected void onShutdown() {
        current = null;
        roundEndsAt = 0L;
        timeoutTaskId = -1;
        availableTypes.clear();
    }
}
