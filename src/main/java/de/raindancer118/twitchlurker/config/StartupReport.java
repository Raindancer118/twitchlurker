package de.raindancer118.twitchlurker.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/** Startup counts as done once the miner has delivered channels, drops and the drop catalogue, not when Spring is ready. */
public class StartupReport {

    public enum Stage {
        BACKEND(25), CHANNELS(35), DROPS(20), CATALOGUE(20);

        private final int weight;

        Stage(int weight) {
            this.weight = weight;
        }
    }

    private static final Duration MAX_WAIT = Duration.ofMinutes(5);

    private final Clock clock;
    private final Instant start;
    private final Consumer<String> out;
    private final Set<Stage> reached = EnumSet.noneOf(Stage.class);
    private boolean up;

    public StartupReport(Clock clock, Instant start, Consumer<String> out) {
        this.clock = clock;
        this.start = start;
        this.out = out;
        out.accept("Starting ... 0%");
    }

    public synchronized void reached(Stage stage) {
        if (up || !reached.add(stage)) {
            return;
        }
        if (reached.size() == Stage.values().length) {
            reportUp("");
        } else {
            out.accept("Starting ... " + percent() + "%");
        }
    }

    public synchronized void heartbeat() {
        if (up) {
            return;
        }
        if (Duration.between(start, clock.instant()).compareTo(MAX_WAIT) > 0) {
            reportUp(" The miner is still loading.");
        } else {
            out.accept("Starting ... " + percent() + "%");
        }
    }

    public synchronized void minerNotStarting(String why) {
        if (!up) {
            reportUp(" The miner is not running: " + why + ".");
        }
    }

    public synchronized boolean isUp() {
        return up;
    }

    private int percent() {
        return Math.min(99, reached.stream().mapToInt(s -> s.weight).sum());
    }

    private void reportUp(String suffix) {
        up = true;
        out.accept("Twitchlurker is now UP! Starting took " + Duration.between(start, clock.instant()).toMillis() + " ms." + suffix);
    }
}
