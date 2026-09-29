package de.raindancer118.twitchlurker.config;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.SnapshotRepository;
import de.raindancer118.twitchlurker.miner.MinerSupervisor;
import de.raindancer118.twitchlurker.raffle.RaffleRepository;
import de.raindancer118.twitchlurker.raffle.RaffleService;
import de.raindancer118.twitchlurker.twitch.TwitchAuthService;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background duties: startup, token polling/validation, chat sync and data retention. */
@Component
public class Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(Lifecycle.class);
    private static final Duration EVENT_RETENTION = Duration.ofDays(365);
    private static final Duration RAFFLE_RETENTION = Duration.ofDays(365);
    private static final Duration SNAPSHOT_RETENTION = Duration.ofDays(400);

    private final MinerSupervisor supervisor;
    private final TwitchAuthService auth;
    private final RaffleService raffles;
    private final EventRepository events;
    private final SnapshotRepository snapshots;
    private final RaffleRepository raffleRepo;
    private final Clock clock;
    private final de.raindancer118.twitchlurker.raffle.LurkAnnouncer lurk;
    private final StartupReport startup;

    public Lifecycle(MinerSupervisor supervisor, TwitchAuthService auth, RaffleService raffles, EventRepository events,
                     SnapshotRepository snapshots, RaffleRepository raffleRepo, Clock clock,
                     de.raindancer118.twitchlurker.raffle.LurkAnnouncer lurk, StartupReport startup) {
        this.startup = startup;
        this.lurk = lurk;
        this.supervisor = supervisor;
        this.auth = auth;
        this.raffles = raffles;
        this.events = events;
        this.snapshots = snapshots;
        this.raffleRepo = raffleRepo;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        startup.reached(StartupReport.Stage.BACKEND);
        auth.revalidate();
        supervisor.restart();
        switch (supervisor.status()) {
            case NEEDS_LOGIN -> startup.minerNotStarting("connect Twitch first");
            case STOPPED -> startup.minerNotStarting("it is stopped in the dashboard");
            default -> { }
        }
        raffles.sync();
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    void reportStartup() {
        startup.heartbeat();
    }

    @EventListener
    void onTokenChanged(TwitchAuthService.TokenChanged event) {
        Thread.ofVirtual().name("token-changed").start(() -> {
            supervisor.onTokenChanged(event.token());
            raffles.sync();
        });
    }

    @Scheduled(fixedDelay = 2000)
    void pollDeviceLogin() {
        auth.pollIfDue();
    }

    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    void revalidateToken() {
        auth.revalidate();
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    void syncRaffleChat() {
        try {
            raffles.sync();
        } catch (RuntimeException e) {
            log.warn("Raffle chat sync failed", e);
        }
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 20_000)
    void greetLurkedChannels() {
        try {
            lurk.tick();
        } catch (RuntimeException e) {
            log.warn("Lurk greeting failed", e);
        }
    }

    @Scheduled(cron = "0 17 4 * * *", zone = "Europe/Berlin")
    void retention() {
        var now = clock.instant();
        int e = events.deleteOlderThan(now.minus(EVENT_RETENTION));
        int r = raffleRepo.deleteOlderThan(now.minus(RAFFLE_RETENTION));
        int s = snapshots.deleteOlderThan(now.minus(SNAPSHOT_RETENTION));
        log.info("Retention: removed {} events, {} raffles, {} snapshots", e, r, s);
    }
}
