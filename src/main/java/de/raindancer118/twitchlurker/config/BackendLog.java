package de.raindancer118.twitchlurker.config;

import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.miner.MinerState;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Backend messages meant for the user: server log plus the website's bot log. */
public class BackendLog {

    private static final Logger log = LoggerFactory.getLogger(BackendLog.class);

    private final MinerState state;
    private final LiveBus bus;
    private final Clock clock;

    public BackendLog(MinerState state, LiveBus bus, Clock clock) {
        this.state = state;
        this.bus = bus;
        this.clock = clock;
    }

    public void info(String msg) {
        log.info(msg);
        var entry = new MinerState.LogLine(clock.instant(), "INFO", "backend", msg);
        state.log(entry);
        bus.publish("log", entry);
    }
}
