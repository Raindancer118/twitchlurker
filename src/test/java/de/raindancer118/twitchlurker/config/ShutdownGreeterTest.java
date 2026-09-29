package de.raindancer118.twitchlurker.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.miner.MinerState;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class ShutdownGreeterTest {

    @Test
    void saysGoodbyeWhenTheBackendShutsDown(CapturedOutput output) {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            new ShutdownGreeter(new BackendLog(new MinerState(), new LiveBus(), Clock.systemUTC())).onApplicationEvent(new ContextClosedEvent(context));
        }
        assertThat(output).contains("Backend shutting down. Goodbye & thank you for using twitchlurker!");
    }
}
