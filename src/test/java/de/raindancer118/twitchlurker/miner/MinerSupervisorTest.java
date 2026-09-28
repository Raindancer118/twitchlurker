package de.raindancer118.twitchlurker.miner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.events.SnapshotRepository;
import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.support.TestDb;
import de.raindancer118.twitchlurker.twitch.TwitchToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MinerSupervisorTest {

    @TempDir
    Path dir;

    final JsonMapper json = JsonMapper.builder().build();
    final AtomicReference<Optional<TwitchToken>> token = new AtomicReference<>(Optional.empty());
    MinerState state;
    SettingsStore settings;
    MinerSupervisor supervisor;

    @BeforeEach
    void setUp() throws Exception {
        Path runner = dir.resolve("fake_runner.py");
        Files.copy(getClass().getResourceAsStream("/fake_runner.py"), runner);
        Path tokenFile = dir.resolve("twitch-token.json");
        json.writeValue(tokenFile.toFile(), new TwitchToken("tok", "tomlurkt", "4711", List.of(), Instant.now()));
        var jdbc = TestDb.migrated(dir);
        state = new MinerState();
        var clock = Clock.systemUTC();
        var handler = new MinerMessageHandler(json, state, new EventService(new EventRepository(jdbc), new LiveBus(), clock),
                new SnapshotRepository(jdbc), new LiveBus(), clock);
        settings = new SettingsStore(dir, json);
        supervisor = new MinerSupervisor(dir, "python3", runner, tokenFile, settings, token::get, handler, state, json,
                Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        supervisor.shutdown();
    }

    private List<String> logMessages() {
        return state.logs(100).stream().map(MinerState.LogLine::msg).toList();
    }

    @Test
    void refusesToStartWithoutToken() {
        supervisor.start();
        assertThat(supervisor.status()).isEqualTo(MinerSupervisor.Status.NEEDS_LOGIN);
        assertThat(settings.get().autostart()).isTrue();
    }

    @Test
    void startsRunsAcceptsCommandsAndStopsCleanly() throws Exception {
        settings.save(settings.get().withStreamers(List.of("zarbex")));
        token.set(Optional.of(new TwitchToken("tok", "tomlurkt", "4711", List.of(), Instant.now())));
        supervisor.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> supervisor.status() == MinerSupervisor.Status.RUNNING);
        assertThat(logMessages()).contains("config streamers=zarbex login=tomlurkt");

        Map<String, Object> cfg = json.readValue(dir.resolve("miner-config.json").toFile(), new tools.jackson.core.type.TypeReference<>() {});
        assertThat(cfg).doesNotContainKey("accessToken");
        assertThat(Files.readString(dir.resolve("miner-config.json"))).doesNotContain("\"tok\"");

        supervisor.addChannelLive("trymacs");
        await().atMost(Duration.ofSeconds(5)).until(() -> logMessages().contains("cmd add trymacs"));

        supervisor.stop();
        assertThat(supervisor.status()).isEqualTo(MinerSupervisor.Status.STOPPED);
        assertThat(logMessages()).contains("bye");
        assertThat(settings.get().autostart()).isFalse();
        assertThat(state.snapshot()).isEmpty();
    }

    @Test
    void crashedRunnerIsRestartedWithBackoff() throws Exception {
        Files.createDirectories(dir.resolve("miner"));
        Files.writeString(dir.resolve("miner/crash"), "");
        token.set(Optional.of(new TwitchToken("tok", "tomlurkt", "4711", List.of(), Instant.now())));
        supervisor.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> logMessages().stream().filter("boom"::equals).count() >= 2);
        assertThat(supervisor.restarts()).isGreaterThanOrEqualTo(1);
        Files.delete(dir.resolve("miner/crash"));
        await().atMost(Duration.ofSeconds(15)).until(() -> supervisor.status() == MinerSupervisor.Status.RUNNING);
    }
}
