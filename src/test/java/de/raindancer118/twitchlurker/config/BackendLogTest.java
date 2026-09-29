package de.raindancer118.twitchlurker.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.miner.MinerState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BackendLogTest {

    @Test
    void backendMessagesShowUpInTheWebsiteLogAndTheServerLog(CapturedOutput output) {
        var state = new MinerState();
        var clock = Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), ZoneOffset.UTC);
        new BackendLog(state, new LiveBus(), clock).info("Starting ... 25%");
        assertThat(state.logs(10)).containsExactly(
                new MinerState.LogLine(Instant.parse("2026-09-29T16:00:00Z"), "INFO", "backend", "Starting ... 25%"));
        assertThat(output).contains("Starting ... 25%");
    }
}
