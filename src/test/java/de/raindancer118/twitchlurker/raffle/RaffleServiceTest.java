package de.raindancer118.twitchlurker.raffle;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.events.LurkerEvent;
import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.support.MutableClock;
import de.raindancer118.twitchlurker.support.TestDb;
import de.raindancer118.twitchlurker.twitch.TwitchToken;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RaffleServiceTest {

    @TempDir
    Path dir;

    record Scheduled(Runnable task, Duration delay) {}

    final List<Scheduled> scheduled = new ArrayList<>();
    final FakeChat chat = new FakeChat();
    final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T18:00:00Z"));
    final AtomicReference<Optional<TwitchToken>> token = new AtomicReference<>(
            Optional.of(new TwitchToken("tok", "tomlurkt", "4711", List.of("chat:read", "chat:edit"), Instant.now())));
    MinerState state;
    SettingsStore settings;
    RaffleRepository raffles;
    EventRepository events;
    RaffleService service;

    @BeforeEach
    void setUp() {
        var jdbc = TestDb.migrated(dir);
        raffles = new RaffleRepository(jdbc);
        events = new EventRepository(jdbc);
        state = new MinerState();
        settings = new SettingsStore(dir, JsonMapper.builder().build());
        service = new RaffleService(settings, token::get, state, raffles, new EventService(events, new LiveBus(), clock), chat,
                (task, delay) -> scheduled.add(new Scheduled(task, delay)), clock, new Random(1));
        online("papaplatte", "follow");
    }

    private void online(String login, String source) {
        var streamers = new ArrayList<MinerState.Streamer>(state.snapshot().map(MinerState.Snapshot::streamers).orElse(List.of()));
        streamers.add(new MinerState.Streamer(login, "1", true, false, 0, "x", "t", 1, null, 0, false, false, false, source));
        state.update(new MinerState.Snapshot("tomlurkt", "s", null, streamers, clock.instant()));
    }

    private void announce(String channel) {
        chat.receive("@badges=moderator/1;mod=1 :streamelements!streamelements@x PRIVMSG #" + channel
                + " :A Raffle has begun for 500 Points it will end in 60 Seconds. Enter by typing !join");
    }

    @Test
    void joinsOnlineFollowedChannelsOnly() {
        online("dropsguy", "drops");
        online("zarbex", "extra");
        service.sync();
        assertThat(chat.connected).isTrue();
        assertThat(chat.login).isEqualTo("tomlurkt");
        assertThat(chat.channels).containsExactlyInAnyOrder("papaplatte", "zarbex");
    }

    @Test
    void entersRaffleAfterRandomDelay() {
        service.sync();
        announce("papaplatte");
        assertThat(chat.sent).isEmpty();
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.getFirst().delay()).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(25));
        assertThat(raffles.recent(5).getFirst().status()).isEqualTo(RaffleEntry.Status.PENDING);

        scheduled.getFirst().task().run();
        assertThat(chat.sent).containsExactly("#papaplatte !join");
        var entry = raffles.recent(5).getFirst();
        assertThat(entry.status()).isEqualTo(RaffleEntry.Status.JOINED);
        assertThat(entry.triggerUser()).isEqualTo("streamelements");
        assertThat(events.recent(5)).extracting(LurkerEvent::type).containsExactly("RAFFLE");
    }

    @Test
    void cooldownSuppressesRepeatedAnnouncements() {
        service.sync();
        announce("papaplatte");
        announce("papaplatte");
        assertThat(scheduled).hasSize(1);
        scheduled.getFirst().task().run();
        clock.advance(Duration.ofSeconds(60));
        announce("papaplatte");
        assertThat(scheduled).hasSize(1);
        clock.advance(Duration.ofSeconds(200));
        announce("papaplatte");
        assertThat(scheduled).hasSize(2);
    }

    @Test
    void skipsWhenChannelWentOfflineBeforeSending() {
        service.sync();
        announce("papaplatte");
        state.update(new MinerState.Snapshot("tomlurkt", "s", null, List.of(), clock.instant()));
        service.sync();
        scheduled.getFirst().task().run();
        assertThat(chat.sent).isEmpty();
        assertThat(raffles.recent(1).getFirst().status()).isEqualTo(RaffleEntry.Status.SKIPPED);
    }

    @Test
    void recordsWinAndFailureNotices() {
        service.sync();
        announce("papaplatte");
        scheduled.getFirst().task().run();
        chat.receive(":streamelements!streamelements@x PRIVMSG #papaplatte :The raffle has ended and tomlurkt won 500 points");
        assertThat(raffles.recent(1).getFirst().status()).isEqualTo(RaffleEntry.Status.WON);
        assertThat(events.recent(1).getFirst().type()).isEqualTo("RAFFLE_WON");

        clock.advance(Duration.ofMinutes(10));
        announce("papaplatte");
        scheduled.get(1).task().run();
        chat.receive("@msg-id=msg_followersonly :tmi.twitch.tv NOTICE #papaplatte :This room is in 10 minutes followers-only mode.");
        var failed = raffles.recent(1).getFirst();
        assertThat(failed.status()).isEqualTo(RaffleEntry.Status.FAILED);
        assertThat(failed.detail()).contains("followers-only");
    }

    @Test
    void disabledGloballyOrPerChannelOrWithoutChatScope() {
        var s = settings.get();
        settings.save(s.withRaffle(new de.raindancer118.twitchlurker.settings.LurkerSettings.Raffle(true, null, null,
                List.of("papaplatte"), null, null, null)));
        service.sync();
        assertThat(chat.channels).isEmpty();

        settings.save(s.withRaffle(new de.raindancer118.twitchlurker.settings.LurkerSettings.Raffle(false, null, null, null, null, null, null)));
        service.sync();
        assertThat(chat.connected).isFalse();

        settings.save(s);
        token.set(Optional.of(new TwitchToken("tok", "tomlurkt", "4711", List.of("chat:read"), Instant.now())));
        service.sync();
        assertThat(chat.connected).isFalse();
        assertThat(service.status().problem()).contains("chat:edit");
    }
}
