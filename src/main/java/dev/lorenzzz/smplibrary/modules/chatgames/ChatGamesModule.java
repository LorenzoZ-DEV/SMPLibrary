package dev.lorenzzz.smplibrary.modules.chatgames;

import dev.lorenzzz.smplibrary.C;
import dev.lorenzzz.smplibrary.manager.CommandManager;
import dev.lorenzzz.smplibrary.manager.MessagesManager;
import dev.lorenzzz.smplibrary.manager.StorageManager;
import dev.lorenzzz.smplibrary.modules.Module;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ChatGamesModule implements Module, Listener {

    private final JavaPlugin plugin;
    private ConfigurationSection cfg;
    private MessagesManager messages;
    private StorageManager storage;
    private CommandManager commands;

    private volatile String answer;
    private volatile ChatGameType currentType;
    private int taskId = -1;
    private int timeoutTaskId = -1;

    private final List<ChatGameType> availableTypes = new ArrayList<>();

    public ChatGamesModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "chatgames"; }

    @Override
    public void onEnable() {
        cfg = plugin.getConfig().getConfigurationSection("MODULES.chatgames");
        if (cfg == null) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);

        List<String> types = cfg.getStringList("game-types");
        if (types.isEmpty()) types = List.of("WORD");
        for (String t : types) {
            try { availableTypes.add(ChatGameType.valueOf(t.toUpperCase())); }
            catch (IllegalArgumentException e) { C.warning("Unknown chat game type: " + t); }
        }
        if (availableTypes.isEmpty()) availableTypes.add(ChatGameType.WORD);

        long periodTicks = Math.max(parseTicks(cfg.getString("task-every", "30m")), 20L * 60);
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::startGame, periodTicks, periodTicks).getTaskId();
    }

    private void startGame() {
        ChatGameType type = availableTypes.get(ThreadLocalRandom.current().nextInt(availableTypes.size()));
        currentType = type;

        int timeLimit = cfg.getInt("time-limit", 3);

        switch (type) {
            case WORD -> startWordGame(timeLimit);
            case MATH -> startMathGame(timeLimit);
            case TRIVIA -> startTriviaGame(timeLimit);
        }

        timeoutTaskId = Bukkit.getScheduler().runTaskLater(plugin, this::timeout, timeLimit * 60L * 20L).getTaskId();
    }

    private void startWordGame(int timeLimit) {
        List<String> words = cfg.getStringList("words");
        if (words.isEmpty()) return;

        String word = words.get(ThreadLocalRandom.current().nextInt(words.size()));
        boolean scramble = cfg.getBoolean("scramble", true);
        String prompt = scramble ? scramble(word) : "_ ".repeat(Math.max(1, word.length())).trim();
        this.answer = word.toLowerCase();

        broadcastGame("Parole", timeLimit, "&aIndovina la parola! &fScrivi la parola giusta in chat.",
                Map.of("%word%", prompt));
    }

    private void startMathGame(int timeLimit) {
        Map<String, Object> mathCfg = cfg.getConfigurationSection("math") != null
                ? cfg.getConfigurationSection("math").getValues(false) : new HashMap<>();

        int max = (int) mathCfg.getOrDefault("max-number", 50);
        String ops = (String) mathCfg.getOrDefault("operations", "+-");

        int a = ThreadLocalRandom.current().nextInt(1, max + 1);
        int b = ThreadLocalRandom.current().nextInt(1, Math.max(a, max) + 1);
        char op = ops.charAt(ThreadLocalRandom.current().nextInt(ops.length()));

        int result;
        String symbol;
        switch (op) {
            case '+' -> { result = a + b; symbol = "+"; }
            case '-' -> { result = Math.max(a, b) - Math.min(a, b); a = Math.max(a, b); b = Math.min(a, b); symbol = "-"; }
            case '*' -> { result = a * b; symbol = "×"; }
            case '/' -> { if (b == 0) b = 1; a = a * b; result = a / b; symbol = "÷"; }
            default -> { result = a + b; symbol = "+"; }
        }

        this.answer = String.valueOf(result);

        broadcastGame("Matematica", timeLimit, "&aQuanto fa &e" + a + " " + symbol + " " + b + "&a? &fScrivi il risultato in chat.",
                Map.of("%question%", a + " " + symbol + " " + b));
    }

    private void startTriviaGame(int timeLimit) {
        List<Map<?, ?>> questions = cfg.getMapList("trivia.questions");
        if (questions.isEmpty()) {
            this.answer = null;
            currentType = null;
            return;
        }

        Map<?, ?> q = questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
        String question = (String) q.get("question");
        List<String> answers = (List<String>) q.get("answers");
        if (question == null || answers == null || answers.isEmpty()) {
            this.answer = null;
            currentType = null;
            return;
        }

        this.answer = String.join("|", answers.stream().map(String::toLowerCase).toList());

        broadcastGame("Trivia", timeLimit, "&a" + question + " &fScrivi la risposta in chat.",
                Map.of("%question%", question));
    }

    private void broadcastGame(String type, int timeLimit, String instruction, Map<String, String> extra) {
        Map<String, String> ph = new LinkedHashMap<>(Map.of(
                "%type%", type,
                "%time_left%", String.valueOf(timeLimit)
        ));
        ph.putAll(extra);

        Component box = messages.component("chat-games.message-box", null, ph);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(box);
            C.sendChatAndAction(p, "&d&lCHATGAMES &8&l» &f" + messages.raw("chat-games.message-box").split("\n")[0]);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(C.parse(instruction, p, Map.of()));
            C.sendChatAndAction(p, instruction);
        }
    }

    private void timeout() {
        if (this.answer == null) return;
        this.answer = null;
        this.currentType = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(messages.component("chat-games.message-timeout", null, Map.of()));
            C.sendChatAndAction(p, "&d&lCHATGAMES &8&l» &cNessuno ha risposto in tempo!");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String ans = this.answer;
        if (ans == null) return;
        String guess = event.getMessage().trim().toLowerCase();
        if (guess.isEmpty()) return;

        boolean correct = false;
        if (currentType == ChatGameType.TRIVIA) {
            String[] possible = ans.split("\\|");
            for (String a : possible) {
                if (guess.equals(a.trim())) { correct = true; break; }
            }
        } else {
            correct = guess.equals(ans);
        }

        if (!correct) return;

        Player winner = event.getPlayer();
        if (!claim(winner)) return;

        event.setCancelled(true);
    }

    private synchronized boolean claim(Player winner) {
        if (this.answer == null) return false;
        String word = this.answer;
        ChatGameType type = this.currentType;
        this.answer = null;
        this.currentType = null;
        if (timeoutTaskId != -1) Bukkit.getScheduler().cancelTask(timeoutTaskId);

        Bukkit.getScheduler().runTask(plugin, () -> {
            String reward = cfg.getString("reward", "");
            String typeLabel = switch (type != null ? type : ChatGameType.WORD) {
                case WORD -> cfg.getString("type", "Parole");
                case MATH -> "Matematica";
                case TRIVIA -> "Trivia";
            };

            if (!reward.isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.replace("%player%", winner.getName()));
            }

            Map<String, String> ph = Map.of(
                    "%word%", word.contains("|") ? word.split("\\|")[0] : word,
                    "%player_name%", winner.getName(),
                    "%player%", winner.getName(),
                    "%type%", typeLabel
            );

            Component win = messages.component("chat-games.message-win-player", winner, ph);
            Component chatWin = messages.component("chat-games.message-win-player-chat.chatgames-win", winner, ph);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(win);
                C.sendChatAndAction(p, "&d&lCHATGAMES &8&l» &a" + winner.getName() + " &fvince!");
            }
            if (chatWin != Component.empty()) {
                for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(chatWin);
            }
        });
        return true;
    }

    private static String scramble(String word) {
        if (word.length() < 2) return word;
        char[] chars = word.toCharArray();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int tries = 0; tries < 8; tries++) {
            for (int i = chars.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                char t = chars[i]; chars[i] = chars[j]; chars[j] = t;
            }
            if (!new String(chars).equalsIgnoreCase(word)) break;
        }
        return new String(chars);
    }

    private static long parseTicks(String period) {
        if (period == null || period.isBlank()) return 20L * 60 * 60;
        period = period.trim().toLowerCase();
        char unit = period.charAt(period.length() - 1);
        long n;
        try { n = Long.parseLong(period.substring(0, period.length() - 1)); }
        catch (NumberFormatException e) { return 20L * 60 * 60; }
        long seconds = switch (unit) {
            case 's' -> n;
            case 'm' -> n * 60;
            case 'h' -> n * 60 * 60;
            case 'd' -> n * 60 * 60 * 24;
            default -> n;
        };
        return seconds * 20L;
    }

    @Override
    public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        if (timeoutTaskId != -1) Bukkit.getScheduler().cancelTask(timeoutTaskId);
        taskId = -1;
        timeoutTaskId = -1;
        answer = null;
        currentType = null;
    }

    @Override public Module storage(StorageManager storage) { this.storage = storage; return this; }
    @Override public Module commands(CommandManager commands) { this.commands = commands; return this; }
    @Override public Module messages(MessagesManager messages) { this.messages = messages; return this; }
}
