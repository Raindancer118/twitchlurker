package de.raindancer118.twitchlurker.raffle;

import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.twitch.TwitchToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/** Watches chat of live channels and enters giveaways announced by bots, mods or the broadcaster. */
public class RaffleService {

    @FunctionalInterface
    public interface Delayer {
        void schedule(Runnable task, Duration delay);
    }

    public record Status(boolean enabled, boolean connected, Set<String> channels, String problem) {}

    private static final int MAX_CHANNELS = 100;
    private static final Duration WIN_WINDOW = Duration.ofMinutes(30);
    private static final Duration NOTICE_WINDOW = Duration.ofSeconds(15);

    private final SettingsStore settings;
    private final Supplier<Optional<TwitchToken>> token;
    private final MinerState state;
    private final RaffleRepository raffles;
    private final EventService events;
    private final ChatConnection chat;
    private final Delayer delayer;
    private final Clock clock;
    private final RandomGenerator random;
    private final Map<String, Instant> lastTrigger = new ConcurrentHashMap<>();

    private volatile RaffleDetector detector;
    private String openedFor;

    public RaffleService(SettingsStore settings, Supplier<Optional<TwitchToken>> token, MinerState state, RaffleRepository raffles,
                         EventService events, ChatConnection chat, Delayer delayer, Clock clock, RandomGenerator random) {
        this.settings = settings;
        this.token = token;
        this.state = state;
        this.raffles = raffles;
        this.events = events;
        this.chat = chat;
        this.delayer = delayer;
        this.clock = clock;
        this.random = random;
    }

    public Status status() {
        String p = currentProblem();
        return new Status(settings.get().raffle().enabled(), chat.isConnected(), chat.channels(), p != null ? p : chat.problem());
    }

    private String currentProblem() {
        if (!settings.get().raffle().enabled()) {
            return null;
        }
        var t = token.get();
        if (t.isEmpty()) {
            return "Kein gültiger Twitch-Login.";
        }
        if (!t.get().canChat()) {
            return "Der Twitch-Token hat kein chat:edit. Bitte Twitch einmal neu verbinden.";
        }
        return null;
    }

    public synchronized void sync() {
        var cfg = settings.get().raffle();
        var t = token.get();
        if (!cfg.enabled() || currentProblem() != null) {
            closeChat();
            return;
        }
        detector = new RaffleDetector(t.get().login(), cfg.bots(), cfg.joinCommands());
        String key = t.get().login() + ":" + t.get().accessToken().hashCode();
        if (!key.equals(openedFor)) {
            closeChat();
            chat.open(t.get().login(), t.get().accessToken(), this::onMessage);
            openedFor = key;
        }
        var disabled = Set.copyOf(cfg.disabledChannels());
        chat.setChannels(state.onlineStreamers().stream()
                .filter(s -> !"drops".equals(s.source()))
                .map(MinerState.Streamer::login)
                .filter(l -> !disabled.contains(l))
                .limit(MAX_CHANNELS)
                .collect(Collectors.toUnmodifiableSet()));
    }

    private void closeChat() {
        if (openedFor != null) {
            chat.close();
            openedFor = null;
        }
    }

    void onMessage(IrcMessage m) {
        String channel = m.channel();
        if (channel == null || !chat.channels().contains(channel)) {
            return;
        }
        switch (m.command()) {
            case "PRIVMSG" -> onPrivmsg(channel, m);
            case "NOTICE" -> onNotice(channel, m);
            default -> {
                // JOIN/PART/USERSTATE etc. are irrelevant here
            }
        }
    }

    private void onPrivmsg(String channel, IrcMessage m) {
        var d = detector;
        if (d == null) {
            return;
        }
        if (d.isWinFor(m)) {
            raffles.lastSent(channel, clock.instant().minus(WIN_WINDOW)).ifPresent(e -> {
                if (e.status() != RaffleEntry.Status.WON) {
                    raffles.update(e.id(), RaffleEntry.Status.WON, abbreviate(m.text()), null);
                    events.record("RAFFLE_WON", channel, null, abbreviate(m.text()));
                }
            });
            return;
        }
        d.detectRaffle(m).ifPresent(trigger -> onTrigger(channel, m, trigger));
    }

    private void onNotice(String channel, IrcMessage m) {
        String msgId = m.tag("msg-id");
        if (msgId == null || !msgId.startsWith("msg_")) {
            return;
        }
        raffles.lastSent(channel, clock.instant().minus(NOTICE_WINDOW))
                .filter(e -> e.status() == RaffleEntry.Status.JOINED)
                .ifPresent(e -> raffles.update(e.id(), RaffleEntry.Status.FAILED, abbreviate(m.text()), null));
    }

    private void onTrigger(String channel, IrcMessage m, RaffleDetector.Trigger trigger) {
        var cfg = settings.get().raffle();
        Instant now = clock.instant();
        Instant last = lastTrigger.get(channel);
        if (last != null && now.isBefore(last.plusSeconds(cfg.cooldownSeconds()))) {
            return;
        }
        lastTrigger.put(channel, now);
        long id = raffles.insert(now, channel, m.sender(), abbreviate(m.text()), trigger.command());
        int min = cfg.minDelaySeconds();
        int max = cfg.maxDelaySeconds();
        var delay = Duration.ofSeconds(min + random.nextInt(max - min + 1));
        delayer.schedule(() -> fire(id, channel, trigger.command()), delay);
    }

    private void fire(long id, String channel, String command) {
        if (!settings.get().raffle().enabled() || !chat.isConnected() || !chat.channels().contains(channel)) {
            raffles.update(id, RaffleEntry.Status.SKIPPED, "Kanal nicht mehr live oder Chat getrennt", null);
            return;
        }
        chat.send(channel, command);
        raffles.update(id, RaffleEntry.Status.JOINED, null, clock.instant());
        events.record("RAFFLE", channel, null, command);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 499) + "…";
    }
}
