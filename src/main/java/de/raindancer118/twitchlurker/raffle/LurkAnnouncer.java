package de.raindancer118.twitchlurker.raffle;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    private static String session(MinerState.Streamer s) {
        return s.login() + "@" + (s.onlineSince() == null ? "?" : String.valueOf(s.onlineSince().longValue()));
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
            boolean newStream = !session.equals(sessionGreeted.get(login));
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
