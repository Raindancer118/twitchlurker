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

    /** percent is 100 once up. */
    public record Progress(int percent, boolean up) {}

    private static final Duration MAX_WAIT = Duration.ofMinutes(5);

    private final Clock clock;
    private final Instant start;
    private final Consumer<String> out;
    private final Consumer<Progress> onChange;
    private final Set<Stage> reached = EnumSet.noneOf(Stage.class);
    private boolean up;
    private int channelPart;

    public StartupReport(Clock clock, Instant start, Consumer<String> out) {
        this(clock, start, out, progress -> { });
    }

    public StartupReport(Clock clock, Instant start, Consumer<String> out, Consumer<Progress> onChange) {
        this.clock = clock;
        this.start = start;
        this.out = out;
        this.onChange = onChange;
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
            onChange.accept(progress());
        }
    }

    /** Progress within the channel stage; moves the status on top of the page, the log only gets whole stages. */
    public synchronized void channelsLoading(int done, int total) {
        if (up || total <= 0 || reached.contains(Stage.CHANNELS)) {
            return;
        }
        int before = percent();
        channelPart = Stage.CHANNELS.weight * Math.clamp(done, 0, total) / total;
        if (percent() != before) {
            onChange.accept(progress());
        }
    }

    public synchronized void heartbeat() {
        if (up) {
            return;
        }
        if (Duration.between(start, clock.instant()).compareTo(MAX_WAIT) > 0) {
            reportUp(" The miner is still loading.");
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

    public synchronized Progress progress() {
        return up ? new Progress(100, true) : new Progress(percent(), false);
    }

    private int percent() {
        int part = reached.contains(Stage.CHANNELS) ? 0 : channelPart;
        return Math.min(99, reached.stream().mapToInt(s -> s.weight).sum() + part);
    }

    private void reportUp(String suffix) {
        up = true;
        out.accept("Twitchlurker is now UP! Starting took " + Duration.between(start, clock.instant()).toMillis() + " ms." + suffix);
        onChange.accept(progress());
    }
}
