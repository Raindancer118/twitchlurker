package de.raindancer118.twitchlurker.raffle;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.settings.LurkerSettings;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.support.MutableClock;
import de.raindancer118.twitchlurker.support.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LurkAnnouncerTest {

    @TempDir
    Path dir;

    final FakeChat chat = new FakeChat();
    final List<Runnable> scheduled = new ArrayList<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T18:00:00Z"));
    MinerState state;
    SettingsStore settings;
    EventRepository events;
    LurkAnnouncer lurk;

    @BeforeEach
    void setUp() {
        var jdbc = TestDb.migrated(dir);
        events = new EventRepository(jdbc);
        state = new MinerState();
        settings = new SettingsStore(dir, JsonMapper.builder().build());
        lurk = new LurkAnnouncer(settings, state, chat, new EventService(events, new LiveBus(), clock), events,
                (task, delay) -> scheduled.add(task), clock, new Random(3));
        chat.open("tomlurkt", "tok", m -> {});
        chat.setChannels(Set.of("papaplatte", "zarbex", "dropsguy"));
    }

    private void watching(double onlineSince, String... logins) {
        watchingStream(onlineSince, null, null, logins);
    }

    private void watchingStream(double onlineSince, String streamId, String startedAt, String... logins) {
        var list = new ArrayList<MinerState.Streamer>();
        for (String l : logins) {
            list.add(new MinerState.Streamer(l, "1", true, true, 0, "g", "t", 1, onlineSince, 1, false, false, false,
                    l.startsWith("drops") ? "drops" : "follow", streamId, startedAt));
        }
        state.update(new MinerState.Snapshot("tomlurkt", "s", null, list, clock.instant()));
    }

    private LurkAnnouncer restarted() {
        return new LurkAnnouncer(settings, state, chat, new EventService(events, new LiveBus(), clock), events,
                (task, delay) -> scheduled.add(task), clock, new Random(3));
    }

    private void runScheduled() {
        var copy = new ArrayList<>(scheduled);
        scheduled.clear();
        copy.forEach(Runnable::run);
    }

    @Test
    void saysLurkOncePerStreamInWatchedChannelsOnly() {
        watching(1000.0, "papaplatte", "dropsguy");
        lurk.tick();
        lurk.tick();
        runScheduled();
        assertThat(chat.sent).containsExactly("#papaplatte !lurk");

        clock.advance(Duration.ofHours(3));
        lurk.tick();
        runScheduled();
        assertThat(chat.sent).hasSize(1);

        // New stream (different start time): greet again.
        watching(99999.0, "papaplatte");
        lurk.tick();
        runScheduled();
        assertThat(chat.sent).containsExactly("#papaplatte !lurk", "#papaplatte !lurk");
    }

    @Test
    void survivesRestartWithoutRepeatingAndRespectsRepeatAndDisable() {
        watching(1000.0, "zarbex");
        lurk.tick();
        runScheduled();
        var fresh = new LurkAnnouncer(settings, state, chat, new EventService(events, new LiveBus(), clock), events,
                (task, delay) -> scheduled.add(task), clock, new Random(3));
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).hasSize(1);

        settings.save(settings.get().withLurk(new LurkerSettings.Lurk(true, "!lurk bin nur im Hintergrund", 60)));
        clock.advance(Duration.ofMinutes(61));
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).last().isEqualTo("#zarbex !lurk bin nur im Hintergrund");

        settings.save(settings.get().withLurk(new LurkerSettings.Lurk(false, null, null)));
        clock.advance(Duration.ofMinutes(61));
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).hasSize(2);
    }

    @Test
    void waitsUntilChatHasTheChannel() {
        chat.setChannels(Set.of());
        watching(1000.0, "papaplatte");
        lurk.tick();
        runScheduled();
        assertThat(chat.sent).isEmpty();
        chat.setChannels(Set.of("papaplatte"));
        lurk.tick();
        runScheduled();
        assertThat(chat.sent).containsExactly("#papaplatte !lurk");
    }

    @Test
    void redeployDuringTheSameBroadcastDoesNotGreetAgain() {
        watchingStream(1000.0, "318232697560", "2026-09-28T15:33:50Z", "papaplatte");
        lurk.tick();
        runScheduled();
        // The miner restarts: it sees the stream "come online" again, so its own online time changes.
        clock.advance(Duration.ofMinutes(20));
        watchingStream(5000.0, "318232697560", "2026-09-28T15:33:50Z", "papaplatte");
        var fresh = restarted();
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).containsExactly("#papaplatte !lurk");
    }

    @Test
    void greetingsStoredBeforeBroadcastIdsCountForTheRunningStream() {
        // Sent by the old version, keyed by the miner's online time only.
        watching(1000.0, "zarbex");
        lurk.tick();
        runScheduled();
        clock.advance(Duration.ofMinutes(5));
        watchingStream(7000.0, "42", "2026-09-28T17:30:00Z", "zarbex");
        var fresh = restarted();
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).hasSize(1);

        // A broadcast that started after the last greeting is a new stream.
        clock.advance(Duration.ofHours(4));
        watchingStream(9000.0, "43", "2026-09-28T21:00:00Z", "zarbex");
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).containsExactly("#zarbex !lurk", "#zarbex !lurk");
    }

    @Test
    void waitsForTheStreamStartBeforeDecidingAfterARestart() {
        watching(1000.0, "papaplatte");
        lurk.tick();
        runScheduled();
        watchingStream(5000.0, "77", null, "papaplatte");
        var fresh = restarted();
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).hasSize(1);
        watchingStream(5000.0, "77", "2026-09-28T17:00:00Z", "papaplatte");
        fresh.tick();
        runScheduled();
        assertThat(chat.sent).hasSize(1);
    }
}
