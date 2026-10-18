package dev.lorenzzz.smplibrary.modules.halloween;

import dev.lorenzzz.smplibrary.Smp_library;
import dev.lorenzzz.smplibrary.commands.HalloweenCommands;
import dev.lorenzzz.smplibrary.modules.AbstractModule;
import dev.lorenzzz.smplibrary.text.Text;
import dev.lorenzzz.smplibrary.util.Log;
import dev.lorenzzz.smplibrary.util.SoundCue;
import dev.lorenzzz.smplibrary.util.Ticks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Halloween season: one rotating event per day plus the candy hunt that goes with it.
 *
 * <p>Three things make up the module. A <em>season window</em> ({@code season.start} /
 * {@code season.end}) so the whole thing switches itself on and off by date and can be left
 * {@code enabled: true} all year. A <em>daily event</em> — see {@link DailyEvent} — picked from
 * the calendar date rather than rolled at start-up, announced at midnight and remembered in
 * {@link CandyLedger}, so a restart never changes the event the server was told about. And the
 * <em>candies</em>: a counter per player, fed by mobs killed and pumpkins broken in the
 * configured worlds, paying a reward every {@code exchange.every} and crowning the best
 * collector of each day.
 *
 * <p>{@code /dolcetto} is the one thing a player does on purpose: once a day, a treat (candies
 * plus the configured reward) or a harmless trick. Everything else happens around them while
 * they play — including the ambience pass, which is per-player and muted from {@code /settings}
 * by anyone who would rather not be whispered at while mining.
 *
 * <p>Nothing here touches the world or the mobs outside {@code worlds}, and nothing is
 * destructive: the mobs only get a pumpkin on their head and the players only get particles,
 * sounds, a few seconds of nausea at worst, and candy.
 */
public final class HalloweenModule extends AbstractModule implements Listener {

    public static final String USE_PERMISSION = "smplibrary.halloween.use";
    public static final String ADMIN_PERMISSION = "smplibrary.halloween.admin";

    /** Per-player switch, shared with the {@code /settings} GUI. On unless turned off. */
    public static final String AMBIENCE_TOGGLE = "halloween_ambience";

    /** How often the rollover is checked. A minute is plenty for a date that changes daily. */
    private static final long CLOCK_PERIOD = Ticks.MINUTE;
    private static final long SAVE_PERIOD = 5 * Ticks.MINUTE;
    private static final long DEFAULT_AMBIENCE_PERIOD = 45 * Ticks.SECOND;
    private static final long MINIMUM_AMBIENCE_PERIOD = 5 * Ticks.SECOND;

    /** Lines of leaderboard shown by {@code /halloween}, and by {@code /halloween top}. */
    private static final int TOP_SHORT = 3;
    private static final int TOP_LONG = 10;
    private static final DateTimeFormatter STATS_TIME =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    /** Minecraft day time between dusk and the first light — "night" for the whole module. */
    private static final long NIGHT_START = 13_000L;
    private static final long NIGHT_END = 23_000L;

    /** How long a blood-moon mob glows: comfortably one night, and then gone. */
    private static final int GLOW_TICKS = 10 * (int) Ticks.MINUTE;

    /**
     * Salt of the daily pick. Together with the date it is the entire source of randomness for
     * which event a day gets, which is what makes the pick survive a restart. Changing it
     * reshuffles the whole calendar, so it is a constant and not a setting.
     */
    private static final long SEED_SALT = 0x48414C4CL; // "HALL"

    /** The night itself. Hard-coded because moving Halloween is not a server setting. */
    private static final MonthDay HALLOWEEN_NIGHT = MonthDay.of(10, 31);

    private static final SoundCue DEFAULT_CANDY = SoundCue.of("entity.item.pickup", 0.7f, 1.6f);
    private static final SoundCue DEFAULT_DAY = SoundCue.of("entity.wither.ambient", 0.7f, 0.8f);
    private static final SoundCue DEFAULT_TREAT = SoundCue.of("entity.player.levelup", 0.7f, 1.4f);
    private static final SoundCue DEFAULT_TRICK = SoundCue.of("entity.ghast.scream", 0.8f, 0.7f);
    private static final SoundCue DEFAULT_EXCHANGE = SoundCue.of("ui.toast.challenge_complete", 0.8f, 1.0f);

    private final CandyLedger ledger;
    private final NamespacedKey candyKey;
    private final NamespacedKey placedKey;

    private HalloweenCommands command;
    private HalloweenListener listener;

    // ------------------------------------------------------------ config cache

    private boolean force;
    private MonthDay seasonStart = MonthDay.of(10, 1);
    private MonthDay seasonEnd = MonthDay.of(11, 2);
    private final Set<String> worlds = new HashSet<>();
    private final List<DailyEvent> rotation = new ArrayList<>();

    /** Evento del giorno gia ricavato, con la data per cui vale. Vedi {@link #today()}. */
    private LocalDate cachedEventDay;
    private DailyEvent cachedEvent;

    private DailyEvent nightEvent = DailyEvent.BLOOD_MOON;
    private boolean announceTitle = true;
    private boolean announceOnJoin = true;
    private long joinDelayTicks = 3 * Ticks.SECOND;

    private double mobChance = 0.08;
    private double pumpkinChance = 0.10;
    private double luckyPumpkinChance = 0.55;
    private int dailyCap = 150;
    private boolean dropItem = true;
    private Material candyMaterial = Material.COOKIE;
    private PotionEffectType snackEffect = PotionEffectType.SPEED;
    private int snackSeconds = 20;
    private int snackAmplifier;
    private double decorChance = 0.35;

    private int exchangeEvery = 25;
    private List<String> exchangeCommands = List.of();
    private boolean exchangeBroadcast = true;

    private boolean trickOrTreatEnabled = true;
    private double treatChance = 0.65;
    private int treatCandies = 3;
    private List<String> treatCommands = List.of();
    private int trickSeconds = 8;

    private boolean dailyTopAnnounce = true;
    private List<String> dailyTopCommands = List.of();
    private int dailyTopMinimum = 5;

    private boolean ambienceEnabled = true;
    private double ambienceChance = 0.35;
    private double whisperChance = 0.25;
    private boolean ambienceOnlyAtNight = true;
    private long ambiencePeriod = DEFAULT_AMBIENCE_PERIOD;
    private final List<SoundCue> ambienceSounds = new ArrayList<>();
    private List<String> whispers = List.of();

    private SoundCue candySound = DEFAULT_CANDY;
    private SoundCue daySound = DEFAULT_DAY;
    private SoundCue treatSound = DEFAULT_TREAT;
    private SoundCue trickSound = DEFAULT_TRICK;
    private SoundCue exchangeSound = DEFAULT_EXCHANGE;

    public HalloweenModule(Smp_library plugin) {
        super(plugin);
        this.ledger = new CandyLedger(plugin);
        this.candyKey = new NamespacedKey(plugin, "halloween_candy");
        this.placedKey = new NamespacedKey(plugin, "halloween_placed");
    }

    @Override public String id() { return "halloween"; }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        loadCache(config());
        // A module with no config section at all would otherwise run with no world to run in.
        if (worlds.isEmpty()) worlds.add("world");
        if (rotation.isEmpty()) rotation.addAll(List.of(DailyEvent.values()));
        ledger.load();

        listener = new HalloweenListener(this, placedKey);
        listen(listener);
        listen(this);

        command = new HalloweenCommands();
        if (commands != null) commands.register(command);

        // Straight away, not in a minute: this is what rolls the day over after a restart that
        // spanned midnight, and what records the current day for a plain reload.
        clock();

        repeat(this::clock, CLOCK_PERIOD, CLOCK_PERIOD);
        repeat(this::saveLedger, SAVE_PERIOD, SAVE_PERIOD);
        if (ambienceEnabled && !ambienceSounds.isEmpty()) {
            repeat(this::ambienceTick, ambiencePeriod, ambiencePeriod);
        }

        DailyEvent today = today();
        Log.info("Halloween " + (seasonActive() ? "attivo" : "fuori stagione")
                + " — evento di oggi: " + (today == null ? "nessuno" : today.key())
                + ", mondi: " + (worlds.isEmpty() ? "nessuno" : String.join(", ", worlds)) + ".");
    }

    @Override
    protected void onShutdown() {
        if (commands != null && command != null) commands.unregister(command);
        command = null;
        listener = null;
        // flush() e non flushIfDirty(): se il salvataggio periodico ha appena preso il flag
        // ma sta ancora scrivendo, flushIfDirty() non farebbe nulla e lo spegnimento passerebbe
        // sopra a una scrittura a metà. flush() aspetta quella in corso e riscrive lo stato
        // finale. Lo stato in memoria NON viene svuotato: load() lo rifà da zero al prossimo
        // enable, mentre svuotarlo qui poteva far scrivere un file senza giocatori.
        ledger.flush();
        worlds.clear();
        rotation.clear();
        ambienceSounds.clear();
    }

    private void loadCache(ConfigurationSection cfg) {
        // La rotazione e l evento della notte di Halloween stanno per essere riletti: quello
        // che era stato ricavato per oggi non vale piu.
        cachedEventDay = null;
        cachedEvent = null;
        if (cfg == null) return;

        ConfigurationSection season = cfg.getConfigurationSection("season");
        force = season != null && season.getBoolean("force", false);
        seasonStart = monthDay(season == null ? null : season.getString("start"), MonthDay.of(10, 1));
        seasonEnd = monthDay(season == null ? null : season.getString("end"), MonthDay.of(11, 2));

        worlds.clear();
        for (String world : cfg.getStringList("worlds")) {
            if (!world.isBlank()) worlds.add(world.trim().toLowerCase(Locale.ROOT));
        }
        if (worlds.isEmpty()) worlds.add("world");

        rotation.clear();
        for (String name : cfg.getStringList("daily-events")) {
            DailyEvent event = DailyEvent.parse(name);
            if (event == null) {
                Log.warning("halloween: evento giornaliero sconosciuto '" + name + "' — ignorato.");
            } else if (!rotation.contains(event)) {
                rotation.add(event);
            }
        }
        if (rotation.isEmpty()) rotation.addAll(List.of(DailyEvent.values()));

        // Blank means "treat the 31st like any other day"; an unknown name is a typo worth saying.
        String night = cfg.getString("halloween-night", "blood-moon");
        nightEvent = night == null || night.isBlank() ? null : DailyEvent.parse(night);
        if (nightEvent == null && night != null && !night.isBlank()) {
            Log.warning("halloween: halloween-night '" + night + "' sconosciuto — il 31 ottobre"
                    + " seguira' la rotazione.");
        }

        ConfigurationSection announce = cfg.getConfigurationSection("announce");
        announceTitle = announce == null || announce.getBoolean("title", true);
        announceOnJoin = announce == null || announce.getBoolean("on-join", true);
        joinDelayTicks = Math.max(Ticks.SECOND,
                (announce == null ? 3 : announce.getInt("join-delay-seconds", 3)) * Ticks.SECOND);

        ConfigurationSection candy = cfg.getConfigurationSection("candy");
        if (candy != null) {
            mobChance = chance(candy.getDouble("mob-chance", 0.08));
            pumpkinChance = chance(candy.getDouble("pumpkin-chance", 0.10));
            luckyPumpkinChance = chance(candy.getDouble("lucky-chance", 0.55));
            dailyCap = Math.max(0, candy.getInt("daily-cap", 150));
            dropItem = candy.getBoolean("drop-item", true);
            candyMaterial = material(candy.getString("material"), Material.COOKIE);
            ConfigurationSection snack = candy.getConfigurationSection("snack");
            if (snack != null) {
                snackEffect = effect(snack.getString("effect"), PotionEffectType.SPEED);
                snackSeconds = Math.max(1, snack.getInt("seconds", 20));
                snackAmplifier = Math.max(0, snack.getInt("amplifier", 0));
            }
        }

        ConfigurationSection mobs = cfg.getConfigurationSection("mobs");
        if (mobs != null) decorChance = chance(mobs.getDouble("decor-chance", 0.35));

        ConfigurationSection exchange = cfg.getConfigurationSection("exchange");
        if (exchange != null) {
            exchangeEvery = Math.max(0, exchange.getInt("every", 25));
            exchangeCommands = List.copyOf(exchange.getStringList("commands"));
            exchangeBroadcast = exchange.getBoolean("broadcast", true);
        }

        ConfigurationSection trick = cfg.getConfigurationSection("trick-or-treat");
        if (trick != null) {
            trickOrTreatEnabled = trick.getBoolean("enabled", true);
            treatChance = chance(trick.getDouble("treat-chance", 0.65));
            ConfigurationSection treat = trick.getConfigurationSection("treat");
            if (treat != null) {
                treatCandies = Math.max(0, treat.getInt("candies", 3));
                treatCommands = List.copyOf(treat.getStringList("commands"));
            }
            ConfigurationSection prank = trick.getConfigurationSection("trick");
            if (prank != null) trickSeconds = Math.max(1, prank.getInt("seconds", 8));
        }

        ConfigurationSection dailyTop = cfg.getConfigurationSection("daily-top");
        if (dailyTop != null) {
            dailyTopAnnounce = dailyTop.getBoolean("announce", true);
            dailyTopCommands = List.copyOf(dailyTop.getStringList("reward-commands"));
            dailyTopMinimum = Math.max(1, dailyTop.getInt("minimum-candies", 5));
        }

        loadAmbience(cfg.getConfigurationSection("ambience"));
        loadSounds(cfg.getConfigurationSection("sounds"));
    }

    private void loadAmbience(ConfigurationSection cfg) {
        ambienceSounds.clear();
        whispers = messages == null ? List.of() : messages.rawList("halloween.whispers");
        if (cfg == null) return;

        ambienceEnabled = cfg.getBoolean("enabled", true);
        ambienceChance = chance(cfg.getDouble("chance", 0.35));
        whisperChance = chance(cfg.getDouble("whisper-chance", 0.25));
        ambienceOnlyAtNight = cfg.getBoolean("only-at-night", true);
        ambiencePeriod = Ticks.parseAtLeast(cfg.getString("every"),
                DEFAULT_AMBIENCE_PERIOD, MINIMUM_AMBIENCE_PERIOD);
        for (String name : cfg.getStringList("sounds")) {
            SoundCue cue = SoundCue.of(name, 0.6f, 0.8f);
            if (!cue.silent()) ambienceSounds.add(cue);
        }
    }

    private void loadSounds(ConfigurationSection cfg) {
        if (cfg == null) return;
        candySound = SoundCue.from(cfg.getConfigurationSection("candy"), DEFAULT_CANDY);
        daySound = SoundCue.from(cfg.getConfigurationSection("day-start"), DEFAULT_DAY);
        treatSound = SoundCue.from(cfg.getConfigurationSection("treat"), DEFAULT_TREAT);
        trickSound = SoundCue.from(cfg.getConfigurationSection("trick"), DEFAULT_TRICK);
        exchangeSound = SoundCue.from(cfg.getConfigurationSection("exchange"), DEFAULT_EXCHANGE);
    }

    // ----------------------------------------------------------- the calendar

    /** True when the season is running today — either inside the window or forced on. */
    public boolean seasonActive() {
        return force || withinSeason(LocalDate.now());
    }

    /** Inclusive on both ends, and happy to wrap across the new year (12-20 -> 01-06). */
    private boolean withinSeason(LocalDate date) {
        MonthDay day = MonthDay.from(date);
        if (!seasonEnd.isBefore(seasonStart)) {
            return !day.isBefore(seasonStart) && !day.isAfter(seasonEnd);
        }
        return !day.isBefore(seasonStart) || !day.isAfter(seasonEnd);
    }

    /**
     * Today's event: the one an admin pinned, otherwise the one the date works out to.
     *
     * <p>Derived from {@link LocalDate#toEpochDay()} and nothing else, so every call during the
     * day — a join message, a mob dying, a restart at three in the morning — answers the same.
     */
    public DailyEvent today() {
        DailyEvent pinned = ledger.override();
        if (pinned != null) return pinned;

        // Memorizzato per giornata. La risposta e identica per tutto il giorno per costruzione,
        // ma ricavarla costa due shuffle della rotazione con le loro due allocazioni, e questo
        // metodo viene chiamato a ogni mob che nasce, a ogni mob che muore, a ogni zucca rotta
        // e a ogni lettura del placeholder (che una tablist rivaluta per player a ogni tick).
        LocalDate now = LocalDate.now();
        if (!now.equals(cachedEventDay)) {
            cachedEvent = eventOf(now);
            cachedEventDay = now;
        }
        return cachedEvent;
    }

    /**
     * The event a given date works out to.
     *
     * <p>Not a draw per day: the days are grouped into blocks as long as the rotation, each
     * block is a shuffle of the whole rotation, and a day takes its place in that shuffle. So
     * every event comes round exactly once per block — a plain per-day draw left Blood Moon
     * missing for two weeks and then gave it three days out of four, which is the kind of thing
     * players notice and nobody can explain.
     *
     * <p>The one repeat a block shuffle can still produce is across the seam, where the last
     * event of a block and the first of the next are the same. That is checked against the
     * previous block's shuffle and fixed by swapping the first two days of this one — a change
     * to positions 0 and 1 only, which is why the previous block's <em>last</em> day can be read
     * without the fix having to be applied recursively.
     */
    private DailyEvent eventOf(LocalDate date) {
        int size = rotation.size();
        if (size == 0) return null;
        // The 31st is the one evening of the year this module exists for: it gets the event the
        // server picked for it rather than whatever the shuffle landed on.
        if (nightEvent != null && isHalloweenNight(date)) return nightEvent;
        if (size == 1) return rotation.get(0);
        // With two events the no-repeat rule leaves no freedom at all: they alternate.
        if (size == 2) {
            return rotation.get((int) Math.floorMod(date.toEpochDay(), 2L));
        }

        long epochDay = date.toEpochDay();
        long block = Math.floorDiv(epochDay, size);
        int offset = (int) Math.floorMod(epochDay, (long) size);

        int[] order = shuffle(block);
        if (order[0] == shuffle(block - 1)[size - 1]) {
            int first = order[0];
            order[0] = order[1];
            order[1] = first;
        }
        return rotation.get(order[offset]);
    }

    /** The rotation's order for one block of days: a Fisher-Yates shuffle seeded by the block. */
    private int[] shuffle(long block) {
        int size = rotation.size();
        int[] order = new int[size];
        for (int i = 0; i < size; i++) order[i] = i;

        long seed = scramble(SEED_SALT ^ block);
        for (int i = size - 1; i > 0; i--) {
            seed = scramble(seed);
            int j = (int) Math.floorMod(seed, (long) (i + 1));
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        return order;
    }

    /**
     * splitmix64's finalizer, the source of randomness for the whole calendar.
     *
     * <p>A {@code java.util.Random} seeded with the day was not up to this job: its first value
     * barely moves when the seed moves by one, so the calendar came out in runs of the same
     * event. This mixes properly and, unlike a {@code Random} instance, is a pure function of
     * its input — which is what lets every call on the same date agree.
     */
    private static long scramble(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Days left in the season, 0 when it is not running. */
    public int daysLeft() {
        if (!seasonActive()) return 0;
        LocalDate today = LocalDate.now();
        LocalDate end = seasonEnd.atYear(today.getYear());
        if (end.isBefore(today)) end = seasonEnd.atYear(today.getYear() + 1);
        // Inclusive of today: on the last day of the season the answer is 1, not 0.
        return (int) ChronoUnit.DAYS.between(today, end) + 1;
    }

    /**
     * Rollover check. Everything date-driven hangs off here: crowning yesterday's collector,
     * clearing the daily counters and announcing the new event.
     */
    private void clock() {
        LocalDate today = LocalDate.now();
        openSeasonIfNew(today);
        // The stored day *is* the record of the announcement: once it matches, today has already
        // been announced — before a restart, if that is when it happened — and nothing more is
        // due until tomorrow.
        if (today.equals(ledger.day())) return;

        boolean firstEver = ledger.day() == null;
        if (!firstEver && dailyTopAnnounce) crownYesterday();
        ledger.rollTo(today);
        ledger.flushIfDirty();

        if (!seasonActive()) return;
        DailyEvent event = today();
        if (event != null) announceDay(event);
    }

    /**
     * Starts a new run of the season the first time it is live in a year it has not run in yet.
     *
     * <p>Lazily, on the first day of the season and not on the 1st of January, so last year's
     * totals stay readable in the file for as long as nothing is using it.
     */
    private void openSeasonIfNew(LocalDate today) {
        if (!seasonActive()) return;
        int year = seasonYear(today);
        if (ledger.seasonYear() == year) return;
        ledger.startSeason(year);
        ledger.flushIfDirty();
    }

    /**
     * Which run of the season a date belongs to. A window that wraps the new year is one season,
     * not two: its January days belong to the year it started in.
     */
    private int seasonYear(LocalDate date) {
        boolean wraps = seasonEnd.isBefore(seasonStart);
        return wraps && MonthDay.from(date).isBefore(seasonStart) ? date.getYear() - 1 : date.getYear();
    }

    /** Announces yesterday's best collector and pays them, before the counters are cleared. */
    private void crownYesterday() {
        List<CandyLedger.Standing> top = ledger.topToday(1);
        if (top.isEmpty()) return;

        CandyLedger.Standing best = top.get(0);
        if (best.candies() < dailyTopMinimum) return;

        broadcast( "halloween.daily-top", Map.of(
                "player", best.name(), "candies", String.valueOf(best.candies())));
        runCommands(dailyTopCommands, best.name());
    }

    /** True on the 31st of October — the night gets its own announcement. */
    public boolean isHalloweenNight(LocalDate date) {
        return MonthDay.from(date).equals(HALLOWEEN_NIGHT);
    }

    private void announceDay(DailyEvent event) {
        String key = isHalloweenNight(LocalDate.now()) && messages.has("halloween.night-start")
                ? "halloween.night-start" : "halloween.day-start";
        broadcast( key, placeholders(event, null));
        daySound.playToAll();
        if (!announceTitle) return;

        boolean hasTitle = messages.has("halloween.day-title");
        boolean hasSubtitle = messages.has("halloween.day-subtitle");
        if (!hasTitle && !hasSubtitle) return;

        Map<String, String> values = placeholders(event, null);
        Component title = hasTitle
                ? render( "halloween.day-title", values) : Component.empty();
        Component subtitle = hasSubtitle
                ? render( "halloween.day-subtitle", values) : Component.empty();
        Title card = Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600)));
        for (Player player : Bukkit.getOnlinePlayers()) player.showTitle(card);
    }

    // ---------------------------------------------------------------- candies

    /** True when the module is live in this world: season running and world on the list. */
    public boolean isLive(World world) {
        return world != null && seasonActive()
                && worlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    public boolean isNight(World world) {
        long time = world.getTime();
        return time >= NIGHT_START && time <= NIGHT_END;
    }

    /** Chance that a hostile mob killed right now leaves a candy. */
    public double mobCandyChance() {
        return chance(mobChance * multiplier());
    }

    /** Chance that a broken pumpkin holds a candy — much higher on a lucky-pumpkins day. */
    public double pumpkinCandyChance() {
        DailyEvent event = today();
        if (event != null && event.has(DailyEvent.Trait.LUCKY_PUMPKINS)) return luckyPumpkinChance;
        return chance(pumpkinChance * multiplier());
    }

    /** How many candies one pumpkin is worth: a handful on a lucky-pumpkins day, one otherwise. */
    public int pumpkinCandies() {
        DailyEvent event = today();
        if (event == null || !event.has(DailyEvent.Trait.LUCKY_PUMPKINS)) return 1;
        return ThreadLocalRandom.current().nextInt(1, 4);
    }

    private double multiplier() {
        DailyEvent event = today();
        return event == null ? 1.0 : event.candyMultiplier();
    }

    /**
     * Whether this player can still collect today.
     *
     * <p>The cap is what keeps the event out of the farms. Candy comes from mobs a player
     * killed and pumpkins a player broke, and both of those are things this server has
     * machines for: a zombie farm and a pumpkin farm would otherwise turn
     * {@code exchange.every} into an afternoon's worth of free crate keys. A few hundred
     * candies a day is far more than anyone collects by playing, and a hard ceiling anyway.
     *
     * <p>Checked by the listeners before anything is dropped, so a capped player does not get
     * an item they were not credited for.
     */
    public boolean capReached(Player player) {
        // The clock ticks once a minute: without this, candies found just after midnight landed
        // on yesterday's counter and were wiped by the rollover, outside both days' cap.
        if (!LocalDate.now().equals(ledger.day())) clock();
        return dailyCap > 0 && ledger.today(player.getUniqueId()) >= dailyCap;
    }

    /**
     * Credits candies, tells the player, and pays the milestone reward if this is the find
     * that crossed one.
     *
     * @return how many were actually credited — fewer than asked when the daily cap trims the
     *         amount, 0 when the basket is already full — so the caller drops a matching item
     */
    public int creditCandy(Player player, int amount, Location where) {
        if (amount <= 0) return 0;
        if (dailyCap > 0) {
            int room = dailyCap - ledger.today(player.getUniqueId());
            if (room <= 0) return 0;
            amount = Math.min(amount, room);
        }
        int total = ledger.add(player.getUniqueId(), player.getName(), amount);

        tell(player, "halloween.candy-found", Map.of(
                "amount", String.valueOf(amount),
                "candies", String.valueOf(total),
                "today", String.valueOf(ledger.today(player.getUniqueId()))));
        candySound.play(player);
        if (where != null) sparkle(player, where, Particle.WITCH, 8);
        checkExchange(player, total);

        // Said once, on the find that fills the basket, and not on every kill after it.
        if (capReached(player)) {
            tell(player, "halloween.cap-reached", Map.of(
                    "candies", String.valueOf(dailyCap)));
        }
        return amount;
    }

    /** Runs the exchange reward once per milestone crossed, never twice for the same one. */
    private void checkExchange(Player player, int total) {
        if (exchangeEvery <= 0) return;
        int milestone = total / exchangeEvery * exchangeEvery;
        if (milestone <= 0) return;

        CandyLedger.Season season = ledger.of(player.getUniqueId(), player.getName());
        if (season.paidMilestone() >= milestone) return;
        // Not saved, not paid: the next find retries, where paying now could pay twice after a crash.
        if (!ledger.paidMilestone(player.getUniqueId(), player.getName(), milestone)) return;

        runCommands(exchangeCommands, player.getName());
        Map<String, String> values = Map.of(
                "player", player.getName(), "candies", String.valueOf(milestone));
        if (exchangeBroadcast) {
            broadcast( "halloween.exchange", values);
        } else {
            tell(player, "halloween.exchange", values);
        }
        exchangeSound.play(player);
    }

    /** The physical candy, or {@code null} when {@code candy.drop-item} is off. */
    public ItemStack candyItem() {
        if (!dropItem) return null;

        ItemStack item = new ItemStack(candyMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        if (messages.has("halloween.candy-item-name")) {
            meta.displayName(render( "halloween.candy-item-name", Map.of()));
        }
        if (messages.has("halloween.candy-item-lore")) {
            meta.lore(List.of(render( "halloween.candy-item-lore", Map.of())));
        }
        meta.getPersistentDataContainer().set(candyKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Whether this stack is one of ours — a cookie crafted by a player must not count. */
    public boolean isCandy(ItemStack item) {
        if (item == null || item.getType() != candyMaterial || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(candyKey, PersistentDataType.BYTE);
    }

    /**
     * The buff for eating one. The counter is deliberately left alone: candies collected are a
     * score, the item is a snack, and eating your score would be a strange rule to explain.
     */
    public void eatCandy(Player player) {
        player.addPotionEffect(new PotionEffect(
                snackEffect, snackSeconds * (int) Ticks.SECOND, snackAmplifier, false, false, true));
        tell(player, "halloween.candy-eaten", Map.of(
                "seconds", String.valueOf(snackSeconds)));
        sparkle(player, player.getLocation(), Particle.HEART, 3);
    }

    // -------------------------------------------------------------- mob decor

    /**
     * Dresses a mob that just spawned, when the day calls for it. Only the helmet slot and a
     * glow are ever touched, and the helmet is pinned to a 0% drop chance so the event cannot
     * turn into a carved-pumpkin farm.
     *
     * <p>Worth knowing about the pumpkin, because it is vanilla behaviour and not something this
     * can switch off: anything worn on the head shields an undead mob from the sun, so a
     * pumpkin-headed zombie walks into the morning instead of burning. That is why only a share
     * of the mobs get one ({@code mobs.decor-chance}) and only during the night — and why a
     * server that would rather not have day-walkers drops {@code spooky-night} from the
     * rotation. A carved pumpkin has no durability, so it will not wear out and let them burn.
     *
     * <p>The glow is given a real duration rather than {@link PotionEffect#INFINITE_DURATION}:
     * it is meant to last the blood-moon night, not to leave a mob glowing next week.
     */
    public void decorate(LivingEntity mob) {
        // Guardie a buon mercato per prime: su un mondo non coperto o di giorno un mob che
        // nasce non deve costare altro che due confronti.
        if (!isLive(mob.getWorld()) || !isNight(mob.getWorld())) return;
        if (ThreadLocalRandom.current().nextDouble() >= decorChance) return;
        DailyEvent event = today();
        if (event == null) return;

        if (event.has(DailyEvent.Trait.PUMPKIN_HEADS)) {
            EntityEquipment equipment = mob.getEquipment();
            ItemStack helmet = equipment == null ? null : equipment.getHelmet();
            if (equipment != null && (helmet == null || helmet.getType().isAir())) {
                equipment.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
                equipment.setHelmetDropChance(0f);
            }
        }
        if (event.has(DailyEvent.Trait.GLOWING_MOBS)) {
            mob.addPotionEffect(new PotionEffect(
                    PotionEffectType.GLOWING, GLOW_TICKS, 0, false, false, false));
        }
    }

    /** The souls a dying mob leaves behind on a soul-harvest day. */
    public void soulTrail(Location where) {
        World world = where.getWorld();
        if (world == null) return;
        world.spawnParticle(Particle.SOUL, where.clone().add(0, 0.5, 0), 12, 0.3, 0.5, 0.3, 0.02);
    }

    // -------------------------------------------------------- trick or treat

    /** {@code /dolcetto}: one treat or trick per player per day. */
    public void trickOrTreat(Player player) {
        // Same rollover as the listeners: a dolcetto just after midnight belongs to the new day.
        if (!LocalDate.now().equals(ledger.day())) clock();
        if (!trickOrTreatEnabled) {
            tell(player, "halloween.trick-or-treat-off", Map.of());
            return;
        }
        if (!seasonActive()) {
            tell(player, "halloween.off-season", Map.of());
            return;
        }

        LocalDate today = LocalDate.now();
        if (!ledger.canClaim(player.getUniqueId(), today)) {
            tell(player, "halloween.already-claimed",
                    Map.of("time", Ticks.countdown(untilMidnight())));
            return;
        }

        // The dolcetto is spent inside treat()/trick(), by a claim() that has to reach the disk.
        if (ThreadLocalRandom.current().nextDouble() < treatChance) {
            treat(player, today);
        } else {
            trick(player, today);
        }
    }

    private void treat(Player player, LocalDate today) {
        // The daily cap counts here too, trimmed the way creditCandy() trims a lucky handful.
        boolean capped = capReached(player);
        int amount = capped ? 0
                : dailyCap > 0 ? Math.min(treatCandies, dailyCap - ledger.today(player.getUniqueId()))
                : treatCandies;
        if (amount > 0) ledger.add(player.getUniqueId(), player.getName(), amount);

        // claim() writes the candies and the spent dolcetto in one go: on disk, both or neither.
        if (!ledger.claim(player.getUniqueId(), player.getName(), today)) {
            if (amount > 0) ledger.undoAdd(player.getUniqueId(), amount);
            tell(player, "halloween.save-failed", Map.of());
            return;
        }
        int total = ledger.candies(player.getUniqueId());

        if (capped) {
            tell(player, "halloween.cap-reached", Map.of("candies", String.valueOf(dailyCap)));
        } else {
            tell(player, "halloween.treat", Map.of(
                    "amount", String.valueOf(amount),
                    "candies", String.valueOf(total)));
        }
        treatSound.play(player);
        sparkle(player, player.getLocation(), Particle.TOTEM_OF_UNDYING, 30);
        runCommands(treatCommands, player.getName());
        checkExchange(player, total);
    }

    /** The tricks are all cosmetic and short — a scare, not a punishment. */
    private void trick(Player player, LocalDate today) {
        if (!ledger.claim(player.getUniqueId(), player.getName(), today)) {
            tell(player, "halloween.save-failed", Map.of());
            return;
        }
        String[] kinds = {"nausea", "blindness", "scare"};
        String kind = kinds[ThreadLocalRandom.current().nextInt(kinds.length)];
        int ticks = trickSeconds * (int) Ticks.SECOND;

        switch (kind) {
            case "nausea" -> player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NAUSEA, ticks, 0, false, false, true));
            case "blindness" -> player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, Math.min(ticks, 5 * (int) Ticks.SECOND),
                    0, false, false, true));
            default -> { /* scare: the sound and the title are the whole trick */ }
        }

        tell(player, "halloween.tricks." + kind,
                Map.of("seconds", String.valueOf(trickSeconds)));
        trickSound.play(player);
        sparkle(player, player.getLocation(), Particle.SMOKE, 20);

        if (messages.has("halloween.trick-title")) {
            player.showTitle(Title.title(
                    render( "halloween.trick-title", Map.of()),
                    messages.has("halloween.trick-subtitle")
                            ? render( "halloween.trick-subtitle", Map.of())
                            : Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(400))));
        }
    }

    private static long untilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis();
    }

    // -------------------------------------------------------------- ambience

    /**
     * One pass of atmosphere: a distant sound and a few particles for some of the players who
     * are out in a Halloween world, and now and then a whisper over the hotbar.
     *
     * <p>Everything here is sent to one player at a time on purpose — the sound is placed a few
     * blocks away from them, which only makes sense from where they are standing, and a player
     * who muted the ambience in {@code /settings} is simply skipped.
     */
    private void ambienceTick() {
        if (!seasonActive() || ambienceSounds.isEmpty()) return;

        DailyEvent event = today();
        double chance = ambienceChance
                * (event != null && event.has(DailyEvent.Trait.LOUD_AMBIENCE) ? 2.0 : 1.0);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isLive(player.getWorld())) continue;
            if (ambienceOnlyAtNight && !isNight(player.getWorld())) continue;
            if (!ambienceOn(player)) continue;
            if (random.nextDouble() >= chance) continue;
            haunt(player, random);
        }
    }

    private void haunt(Player player, ThreadLocalRandom random) {
        Location at = player.getLocation().add(
                random.nextDouble(-8.0, 8.0), random.nextDouble(-1.0, 3.0), random.nextDouble(-8.0, 8.0));

        ambienceSounds.get(random.nextInt(ambienceSounds.size())).playTo(player, at);
        player.spawnParticle(Particle.SOUL, at, 6, 0.6, 0.6, 0.6, 0.01);

        if (!whispers.isEmpty() && random.nextDouble() < whisperChance) {
            player.sendActionBar(Text.mini(whispers.get(random.nextInt(whispers.size()))));
        }
    }

    /** False only for a player who turned the ambience off in {@code /settings}. */
    private boolean ambienceOn(Player player) {
        return toggles() == null || toggles().get(player.getUniqueId(), AMBIENCE_TOGGLE, true);
    }

    // ---------------------------------------------------------------- players

    /** Reminds whoever joins what today's event is — a few seconds in, not mid-loading screen. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!announceOnJoin || !seasonActive()) return;
        Player player = event.getPlayer();
        later(() -> {
            if (!player.isOnline() || !seasonActive()) return;
            DailyEvent today = today();
            if (today == null) return;
            tell(player, "halloween.join", placeholders(today, player));
        }, joinDelayTicks);
    }

    // --------------------------------------------------------------- commands

    /** {@code /halloween}: the season, today's event, your candies and the top collectors. */
    public void showStatus(CommandSender sender) {
        DailyEvent event = today();
        Map<String, String> values = placeholders(event,
                sender instanceof Player player ? player : null);

        if (!seasonActive() || event == null) {
            tell(sender, "halloween.status-off", values);
            return;
        }
        tell(sender, "halloween.status", values);
        showTop(sender, TOP_SHORT);
    }

    /** {@code /halloween top}: the season leaderboard. */
    public void showTop(CommandSender sender, int limit) {
        List<CandyLedger.Standing> top = ledger.topSeason(limit);
        if (top.isEmpty()) {
            tell(sender, "halloween.top-empty", Map.of());
            return;
        }
        int rank = 1;
        for (CandyLedger.Standing standing : top) {
            tell(sender, "halloween.top-entry", Map.of(
                    "rank", String.valueOf(rank++),
                    "player", standing.name(),
                    "candies", String.valueOf(standing.candies())));
        }
    }

    /** {@code /halloween stats}: your candy balance and your last credits. */
    public void showStats(Player player) {
        tell(player, "halloween.stats-header", Map.of(
                "candies", String.valueOf(ledger.candies(player.getUniqueId())),
                "today", String.valueOf(ledger.today(player.getUniqueId()))));
        List<CandyLedger.Credit> recent = ledger.recent(player.getUniqueId());
        if (recent.isEmpty()) {
            tell(player, "halloween.stats-empty", Map.of());
            return;
        }
        for (CandyLedger.Credit credit : recent) {
            tell(player, "halloween.stats-entry", Map.of(
                    "amount", String.valueOf(credit.amount()),
                    "when", STATS_TIME.format(Instant.ofEpochMilli(credit.at()))));
        }
    }

    public int topLong() {
        return TOP_LONG;
    }

    /**
     * Pins today's event by hand and announces it. Cleared by the next rollover, so a pinned
     * event never outlives the day it was pinned for.
     */
    public void pinEvent(CommandSender sender, String name) {
        DailyEvent event = DailyEvent.parse(name);
        if (event == null) {
            tell(sender, "halloween.unknown-event", Map.of(
                    "event", name == null ? "" : name,
                    "events", String.join(", ", eventKeys())));
            return;
        }
        ledger.override(event);
        ledger.flushIfDirty();
        announceDay(event);
        tell(sender, "halloween.pinned", placeholders(event, null));
    }

    private static List<String> eventKeys() {
        List<String> keys = new ArrayList<>();
        for (DailyEvent event : DailyEvent.values()) keys.add(event.key());
        return keys;
    }

    // --------------------------------------------------------------- messaging

    /**
     * Sends a {@code halloween.*} MiniMessage text with its action-bar companion.
     *
     * <p>Rendered through {@link Text#mini}: these lines use MiniMessage tags. Values arrive
     * as {@code unparsed} placeholders so a player name like {@code <red>} can't recolor the
     * line, and {@code <prefix>} is filled from {@code venrix.prefix}.
     */
    private void tell(CommandSender target, String key, Map<String, String> values) {
        if (messages == null) return;
        String chat = messages.raw(key);
        if (chat != null && !chat.isBlank()) {
            target.sendMessage(Text.mini(withPrefix(chat), resolver(values)));
        }
        if (target instanceof Player player) {
            String bar = messages.raw(key + "-actionbar");
            if (bar != null && !bar.isBlank()) {
                player.sendActionBar(Text.mini(withPrefix(bar), resolver(values)));
            }
        }
    }

    /**
     * {@link #tell} to every online player, rendered once.
     *
     * <p>The values do not depend on who is reading, so neither does the component. One pass
     * through tell() per player was a yaml lookup, a prefix splice, a new TagResolver and an
     * uncached MiniMessage parse per player, for both the chat line and the action bar.
     */
    private void broadcast(String key, Map<String, String> values) {
        if (messages == null) return;
        TagResolver resolver = resolver(values);
        Component chat = rendered(key, resolver);
        Component bar = rendered(key + "-actionbar", resolver);
        if (chat == null && bar == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (chat != null) player.sendMessage(chat);
            if (bar != null) player.sendActionBar(bar);
        }
    }

    /** One message key as a finished component, or {@code null} when the key is blank. */
    private Component rendered(String key, TagResolver resolver) {
        String template = messages.raw(key);
        return template == null || template.isBlank() ? null : Text.mini(withPrefix(template), resolver);
    }

    /** Renders a {@code halloween.*} line to a component (titles, item names, lore). */
    private Component render(String key, Map<String, String> values) {
        if (messages == null) return Component.empty();
        String template = messages.raw(key);
        if (template == null || template.isBlank()) return Component.empty();
        return Text.mini(withPrefix(template), resolver(values));
    }

    private String withPrefix(String template) {
        String prefix = messages == null ? "" : messages.raw("venrix.prefix");
        return template.replace("<prefix>", prefix == null ? "" : prefix);
    }

    private static TagResolver resolver(Map<String, String> values) {
        TagResolver.Builder builder = TagResolver.builder();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                builder.resolver(Placeholder.unparsed(entry.getKey(),
                        entry.getValue() == null ? "" : entry.getValue()));
            }
        }
        return builder.build();
    }

    // --------------------------------------------------------------- the glue

    /**
     * The values every message of the module may use. Event names and descriptions are plain
     * text in messages.yml on purpose: they travel through MiniMessage as unparsed placeholders
     * — which is what keeps a player name from injecting tags — so a {@code <gold>} written
     * inside one would reach the player as literal text. The templates do the colouring.
     */
    private Map<String, String> placeholders(DailyEvent event, Player player) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("event", eventName(event));
        values.put("description", eventDescription(event));
        values.put("days", String.valueOf(daysLeft()));
        values.put("candies", player == null ? "0" : String.valueOf(ledger.candies(player.getUniqueId())));
        values.put("today", player == null ? "0" : String.valueOf(ledger.today(player.getUniqueId())));
        values.put("player", player == null ? "" : player.getName());
        values.put("next", String.valueOf(nextMilestone(player)));
        values.put("trick", player == null ? "" : messages.raw(
                ledger.canClaim(player.getUniqueId(), LocalDate.now())
                        ? "halloween.trick-ready" : "halloween.trick-used"));
        return values;
    }

    /** Candies still missing before the next exchange reward; 0 when there is no exchange. */
    private int nextMilestone(Player player) {        if (player == null || exchangeEvery <= 0) return 0;
        int total = ledger.candies(player.getUniqueId());
        return exchangeEvery - total % exchangeEvery;
    }

    /** Plain-text name of an event, for messages and for PlaceholderAPI. */
    public String eventName(DailyEvent event) {
        if (event == null) return messages.raw("halloween.no-event");
        String path = "halloween.events." + event.key() + ".name";
        return messages.has(path) ? messages.raw(path) : event.key();
    }

    public String eventDescription(DailyEvent event) {
        if (event == null) return "";
        String path = "halloween.events." + event.key() + ".description";
        return messages.has(path) ? messages.raw(path) : "";
    }

    /** The season total, for PlaceholderAPI. */
    public int candies(Player player) {
        return ledger.candies(player.getUniqueId());
    }

    /** The candies collected today, for PlaceholderAPI. */
    public int candiesToday(Player player) {
        return ledger.today(player.getUniqueId());
    }

    /**
     * The periodic save. The file is small but it is still disk I/O, and the counters are
     * written from a repeating task — off the main thread it is, which the ledger's volatile
     * fields and concurrent map are built for.
     */
    private void saveLedger() {
        if (!plugin.isEnabled()) {
            ledger.flushIfDirty();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, ledger::flushIfDirty);
    }

    private void runCommands(List<String> templates, String playerName) {
        for (String template : templates) {
            if (template == null || template.isBlank()) continue;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    template.replace("%player%", playerName));
        }
    }

    /** Particles for one player only: this is their find, not a firework for the server. */
    private void sparkle(Player player, Location where, Particle particle, int count) {
        player.spawnParticle(particle, where.clone().add(0, 0.8, 0), count, 0.4, 0.4, 0.4, 0.02);
    }

    private static double chance(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static MonthDay monthDay(String raw, MonthDay fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return MonthDay.parse("--" + raw.trim());
        } catch (DateTimeParseException malformed) {
            Log.warning("halloween: data '" + raw + "' non valida (serve MM-DD) — uso "
                    + fallback + ".");
            return fallback;
        }
    }

    private static Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            Log.warning("halloween: materiale '" + raw + "' non valido — uso " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private static PotionEffectType effect(String raw, PotionEffectType fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = value.contains(":")
                ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
        PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
        if (type == null) {
            Log.warning("halloween: effetto '" + raw + "' sconosciuto — uso " + fallback.getKey() + ".");
            return fallback;
        }
        return type;
    }
}
