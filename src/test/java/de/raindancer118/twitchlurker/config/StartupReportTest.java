package de.raindancer118.twitchlurker.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer118.twitchlurker.config.StartupReport.Stage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StartupReportTest {

    private static final Instant JVM_START = Instant.parse("2026-09-29T16:00:00Z");
    private final MutableClock clock = new MutableClock(JVM_START);
    private final List<String> lines = new ArrayList<>();
    private final StartupReport report = new StartupReport(clock, JVM_START, lines::add);

    @Test
    void countsChannelsAndDropsIntoTheStartup() {
        clock.advance(4_900);
        report.reached(Stage.BACKEND);
        clock.advance(10_000);
        report.heartbeat();
        report.reached(Stage.CHANNELS);
        report.reached(Stage.CHANNELS);
        report.reached(Stage.DROPS);
        clock.advance(45_100);
        report.reached(Stage.CATALOGUE);
        report.heartbeat();
        report.reached(Stage.CATALOGUE);

        // The heartbeat only guards the time limit; the log gets a line only when the percentage moves.
        assertThat(lines).containsExactly("Starting ... 0%", "Starting ... 25%", "Starting ... 60%",
                "Starting ... 80%", "Twitchlurker is now UP! Starting took 1 min.");
    }

    @Test
    void stagesMayArriveInAnyOrder() {
        report.reached(Stage.DROPS);
        report.reached(Stage.BACKEND);
        assertThat(lines).endsWith("Starting ... 20%", "Starting ... 45%");
    }

    @Test
    void upRightAwayWhenTheMinerCannotStart() {
        clock.advance(5_000);
        report.reached(Stage.BACKEND);
        report.minerNotStarting("connect Twitch first");
        report.heartbeat();
        assertThat(lines).endsWith("Twitchlurker is now UP! Starting took 5.0 s. The miner is not running: connect Twitch first.");
    }

    @Test
    void givesUpWaitingForTheMinerAfterFiveMinutes() {
        report.reached(Stage.BACKEND);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1).toMillis());
        report.heartbeat();
        assertThat(report.isUp()).isTrue();
        assertThat(lines.getLast()).isEqualTo("Twitchlurker is now UP! Starting took 5 min 1 s. The miner is still loading.");
    }

    @Test
    void publishesProgressForTheStatusOnTopOfThePage() {
        List<StartupReport.Progress> seen = new ArrayList<>();
        var withStatus = new StartupReport(clock, JVM_START, lines::add, seen::add);
        assertThat(withStatus.progress()).isEqualTo(new StartupReport.Progress(0, false));
        withStatus.reached(Stage.BACKEND);
        withStatus.minerNotStarting("connect Twitch first");
        assertThat(seen).containsExactly(new StartupReport.Progress(25, false), new StartupReport.Progress(100, true));
        assertThat(withStatus.progress()).isEqualTo(new StartupReport.Progress(100, true));
    }

    @Test
    void channelLoadingMovesTheStatusButNotTheLog() {
        List<StartupReport.Progress> seen = new ArrayList<>();
        List<String> log = new ArrayList<>();
        var withStatus = new StartupReport(clock, JVM_START, log::add, seen::add);
        withStatus.reached(Stage.BACKEND);
        withStatus.channelsLoading(1, 200);
        withStatus.channelsLoading(2, 200);
        withStatus.channelsLoading(100, 200);
        withStatus.channelsLoading(199, 200);
        withStatus.reached(Stage.CHANNELS);
        assertThat(seen).extracting(StartupReport.Progress::percent).containsExactly(25, 42, 59, 60);
        assertThat(log).containsExactly("Starting ... 0%", "Starting ... 25%", "Starting ... 60%");
    }

    @Test
    void startupTimeIsGivenInTheUnitThatReadsBest() {
        assertThat(StartupReport.humanDuration(Duration.ofMillis(850))).isEqualTo("850 ms");
        assertThat(StartupReport.humanDuration(Duration.ofMillis(28_822))).isEqualTo("28.8 s");
        assertThat(StartupReport.humanDuration(Duration.ofMillis(59_970))).isEqualTo("1 min");
        assertThat(StartupReport.humanDuration(Duration.ofMillis(194_627))).isEqualTo("3 min 15 s");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
