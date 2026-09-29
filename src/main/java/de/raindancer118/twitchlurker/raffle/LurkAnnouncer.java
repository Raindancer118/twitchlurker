package de.raindancer118.twitchlurker.raffle;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/** Says "!lurk" (or the configured text) once when the bot starts watching a stream, optionally again at an interval. */
public class LurkAnnouncer {

    private final SettingsStore settings;
    private final MinerState state;
    private final ChatConnection chat;
    private final EventService events;
    private final EventRepository eventRepository;
    private final RaffleService.Delayer delayer;
    private final Clock clock;
    private final RandomGenerator random;
    private final Map<String, String> sessionGreeted = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> loaded = ConcurrentHashMap.newKeySet();

    public LurkAnnouncer(SettingsStore settings, MinerState state, ChatConnection chat, EventService events,
                         EventRepository eventRepository, RaffleService.Delayer delayer, Clock clock, RandomGenerator random) {
        this.settings = settings;
        this.state = state;
        this.chat = chat;
        this.events = events;
        this.eventRepository = eventRepository;
        this.delayer = delayer;
        this.clock = clock;
        this.random = random;
    }

    /** Twitch's broadcast id when known. The miner's online time is only a fallback: it resets on every restart. */
    private static String session(MinerState.Streamer s) {
        if (s.streamId() != null) {
            return s.login() + "@b" + s.streamId();
        }
        return s.login() + "@" + (s.onlineSince() == null ? "?" : String.valueOf(s.onlineSince().longValue()));
    }

    private static Instant startedAt(MinerState.Streamer s) {
        try {
            return s.streamStartedAt() == null ? null : Instant.parse(s.streamStartedAt());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public synchronized void tick() {
        var cfg = settings.get().lurk();
        if (!cfg.enabled() || !chat.isConnected()) {
            return;
        }
        var snapshot = state.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (var s : snapshot.get().streamers()) {
            String login = s.login();
            if (!s.watching() || "drops".equals(s.source()) || !chat.channels().contains(login) || pending.contains(login)) {
                continue;
            }
            if (loaded.add(login)) {
                // After a restart, remember what was already said so a running stream isn't greeted twice.
                eventRepository.last("LURK", login).ifPresent(e -> {
                    sessionGreeted.put(login, e.detail());
                    lastSent.put(login, e.ts());
                });
            }
            String session = session(s);
            Instant last = lastSent.get(login);
            Instant started = startedAt(s);
            if (s.streamId() != null && started == null && last != null && !session.equals(sessionGreeted.get(login))) {
                // Can't tell a restart from a new broadcast yet; the runner reports the start time shortly.
                continue;
            }
            // Greeted if this exact broadcast was greeted, or anything was said after it started (covers restarts
            // and greetings stored before broadcast ids were known).
            boolean newStream = !session.equals(sessionGreeted.get(login)) && (started == null || last == null || last.isBefore(started));
            boolean repeatDue = cfg.repeatMinutes() > 0 && last != null
                    && !now.isBefore(last.plus(Duration.ofMinutes(cfg.repeatMinutes())));
            if (newStream || repeatDue) {
                pending.add(login);
                delayer.schedule(() -> send(login, session), Duration.ofSeconds(5 + random.nextInt(26)));
            }
        }
    }

    private void send(String login, String session) {
        try {
            var cfg = settings.get().lurk();
            if (!cfg.enabled() || !chat.isConnected() || !chat.channels().contains(login)) {
                return;
            }
            chat.send(login, cfg.message());
            events.record("LURK", login, null, session);
            sessionGreeted.put(login, session);
            lastSent.put(login, clock.instant());
        } finally {
            pending.remove(login);
        }
    }
}
