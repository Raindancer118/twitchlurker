package de.raindancer118.twitchlurker.miner;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.events.LurkerEvent;
import de.raindancer118.twitchlurker.events.SnapshotRepository;
import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.support.TestDb;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MinerMessageHandlerTest {

    @TempDir
    Path dir;

    MinerState state;
    EventRepository events;
    SnapshotRepository snapshots;
    MinerMessageHandler handler;

    @BeforeEach
    void setUp() {
        var jdbc = TestDb.migrated(dir);
        events = new EventRepository(jdbc);
        snapshots = new SnapshotRepository(jdbc);
        state = new MinerState();
        var clock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);
        handler = new MinerMessageHandler(JsonMapper.builder().build(), state, new EventService(events, new LiveBus(), clock),
                snapshots, new LiveBus(), clock);
    }

    @Test
    void stateLineUpdatesSnapshotAndRecordsPoints() {
        handler.handleStdout("""
                {"t":"state","user":"tomlurkt","session":"s1","startedAt":"2026-09-28T10:00:00","ts":1.0,"streamers":[
                 {"login":"papaplatte","channelId":"1","online":true,"watching":true,"points":1200,"game":"Just Chatting","title":"hi",
                  "viewers":20000,"onlineSince":1759000000.5,"minutesWatched":12.0,"streakPending":false,"dropsEligible":false,"multiplier":false,"source":"follow"},
                 {"login":"trymacs","channelId":"2","online":false,"watching":false,"points":50,"game":null,"title":null,
                  "viewers":0,"onlineSince":null,"minutesWatched":0,"streakPending":false,"dropsEligible":false,"multiplier":false,"source":"follow","futureField":1}
                ]}""".replace("\n", ""));
        var snap = state.snapshot().orElseThrow();
        assertThat(snap.user()).isEqualTo("tomlurkt");
        assertThat(snap.streamers()).hasSize(2);
        assertThat(state.onlineStreamers()).extracting(MinerState.Streamer::login).containsExactly("papaplatte");
        assertThat(snapshots.history("papaplatte", Instant.EPOCH)).hasSize(1);
    }

    @Test
    void runnerEventsArePersistedAndDuplicatesIgnored() {
        handler.handleStdout("{\"t\":\"event\",\"event\":\"POINTS\",\"login\":\"papaplatte\",\"amount\":10,\"reason\":\"WATCH\",\"balance\":1210}");
        handler.handleStdout("{\"t\":\"event\",\"event\":\"GAIN_FOR_WATCH\",\"msg\":\"+10 → Streamer(username=papaplatte...\"}");
        handler.handleStdout("{\"t\":\"event\",\"event\":\"BONUS\",\"login\":\"papaplatte\"}");
        handler.handleStdout("{\"t\":\"event\",\"event\":\"RAID\",\"login\":\"papaplatte\",\"target\":\"zarbex\"}");
        handler.handleStdout("{\"t\":\"event\",\"event\":\"DROP\",\"name\":\"Hazmat\",\"benefit\":\"Hazmat Suit\"}");
        handler.handleStdout("{\"t\":\"event\",\"event\":\"STREAMER_REMOVED\",\"login\":\"oldfollow\"}");
        handler.handleStdout("{\"t\":\"event\",\"event\":\"STREAMER_ONLINE\",\"msg\":\"Streamer(username=zarbex, channel_id=2, channel_points=1k) is Online!\"}");
        var recent = events.recent(10);
        assertThat(recent).extracting(LurkerEvent::type).containsExactly("ONLINE", "REMOVED", "DROP", "RAID", "BONUS", "POINTS");
        assertThat(recent.get(0).login()).isEqualTo("zarbex");
        assertThat(recent.get(1).login()).isEqualTo("oldfollow");
        assertThat(recent.get(2).detail()).isEqualTo("Hazmat Suit");
        assertThat(recent.get(3).detail()).isEqualTo("zarbex");
        assertThat(recent.get(5).amount()).isEqualTo(10L);
        assertThat(recent.get(5).detail()).isEqualTo("WATCH");
    }

    @Test
    void dropsAndLogsAndGarbage() {
        handler.handleStdout("{\"t\":\"drops\",\"campaigns\":[{\"id\":\"c\",\"name\":\"Rust\",\"game\":\"Rust\",\"image\":null,\"endsAt\":\"2026-10-01T00:00:00Z\",\"linked\":true,"
                + "\"drops\":[{\"id\":\"d\",\"name\":\"Suit\",\"image\":null,\"required\":120,\"watched\":60,\"claimed\":false}]}],\"claimed\":[]}");
        assertThat(state.drops().orElseThrow().campaigns().getFirst().drops().getFirst().watched()).isEqualTo(60);

        handler.handleStdout("{\"t\":\"campaigns\",\"campaigns\":[{\"id\":\"mc1\",\"name\":\"Minecraft Live\",\"game\":\"Minecraft\",\"gameId\":\"27471\","
                + "\"image\":null,\"status\":\"ACTIVE\",\"startAt\":\"2026-09-27T00:00:00Z\",\"endAt\":\"2026-10-05T00:00:00Z\",\"linked\":false,"
                + "\"linkUrl\":\"https://link\",\"channels\":[\"gronkh\"],\"rewards\":[{\"name\":\"Cape\",\"image\":null,\"minutes\":60}],\"watched\":true}],\"access\":\"missing\"}");
        var catalogue = state.catalogue().orElseThrow();
        assertThat(catalogue.campaigns()).hasSize(1);
        assertThat(catalogue.campaigns().getFirst().rewards().getFirst().minutes()).isEqualTo(60);
        assertThat(catalogue.campaigns().getFirst().watched()).isTrue();
        assertThat(catalogue.access()).isEqualTo("missing");

        handler.handleStdout("{\"t\":\"log\",\"level\":\"INFO\",\"logger\":\"x\",\"msg\":\"Loading data for 12 streamers\"}");
        handler.handleStdout("not json at all");
        handler.handleStderr("Traceback (most recent call last):");
        assertThat(state.logs(10)).extracting(MinerState.LogLine::msg)
                .containsExactly("Loading data for 12 streamers", "not json at all", "Traceback (most recent call last):");
        assertThat(state.logs(10).get(2).level()).isEqualTo("STDERR");
    }
}
