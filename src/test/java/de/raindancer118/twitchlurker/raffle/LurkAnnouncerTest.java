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
        var list = new ArrayList<MinerState.Streamer>();
        for (String l : logins) {
            list.add(new MinerState.Streamer(l, "1", true, true, 0, "g", "t", 1, onlineSince, 1, false, false, false,
                    l.startsWith("drops") ? "drops" : "follow"));
        }
        state.update(new MinerState.Snapshot("tomlurkt", "s", null, list, clock.instant()));
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
}
