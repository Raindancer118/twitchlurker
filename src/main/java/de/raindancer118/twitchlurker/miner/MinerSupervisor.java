package de.raindancer118.twitchlurker.miner;

import de.raindancer118.twitchlurker.settings.LurkerSettings;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.twitch.TwitchToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Owns the Python runner process: start, stop, crash restarts with exponential backoff. */
public class MinerSupervisor {

    public enum Status { STOPPED, NEEDS_LOGIN, STARTING, RUNNING, BACKOFF }

    private static final Logger log = LoggerFactory.getLogger(MinerSupervisor.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final Duration STABLE_RUN = Duration.ofMinutes(10);

    private final Path workDir;
    private final Path configFile;
    private final String python;
    private final Path runnerScript;
    private final Path tokenFile;
    private final SettingsStore settings;
    private final Supplier<Optional<TwitchToken>> token;
    private final MinerMessageHandler handler;
    private final MinerState state;
    private final JsonMapper json;
    private final Duration baseBackoff;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("miner-supervisor").daemon().factory());

    private Process process;
    private Writer stdin;
    private Thread stdoutReader;
    private Instant startedAt;
    private boolean stopping;
    private ScheduledFuture<?> pendingRestart;
    private int consecutiveFailures;
    private int restarts;

    public MinerSupervisor(Path dataDir, String python, Path runnerScript, Path tokenFile, SettingsStore settings,
                           Supplier<Optional<TwitchToken>> token, MinerMessageHandler handler, MinerState state,
                           JsonMapper json, Duration baseBackoff) {
        this.workDir = dataDir.resolve("miner");
        this.configFile = dataDir.resolve("miner-config.json");
        this.python = python;
        this.runnerScript = runnerScript;
        this.tokenFile = tokenFile;
        this.settings = settings;
        this.token = token;
        this.handler = handler;
        this.state = state;
        this.json = json;
        this.baseBackoff = baseBackoff;
    }

    public synchronized Status status() {
        if (process != null && process.isAlive()) {
            var snap = state.snapshot();
            return snap.isPresent() && !snap.get().receivedAt().isBefore(startedAt) ? Status.RUNNING : Status.STARTING;
        }
        if (pendingRestart != null && !pendingRestart.isDone()) {
            return Status.BACKOFF;
        }
        return settings.get().autostart() && token.get().isEmpty() ? Status.NEEDS_LOGIN : Status.STOPPED;
    }

    public synchronized Optional<Instant> startedAt() {
        return process != null && process.isAlive() ? Optional.of(startedAt) : Optional.empty();
    }

    public synchronized int restarts() {
        return restarts;
    }

    /** User intent: keep the miner running (also across app restarts). */
    public synchronized void start() {
        if (!settings.get().autostart()) {
            settings.save(settings.get().withAutostart(true));
        }
        consecutiveFailures = 0;
        launchIfPossible();
    }

    /** User intent: stop and stay stopped. */
    public void stop() {
        synchronized (this) {
            if (settings.get().autostart()) {
                settings.save(settings.get().withAutostart(false));
            }
        }
        terminate();
    }

    public void restart() {
        terminate();
        synchronized (this) {
            consecutiveFailures = 0;
            launchIfPossible();
        }
    }

    /** Stop the process on app shutdown without changing the user's intent. */
    public void shutdown() {
        terminate();
        scheduler.shutdownNow();
    }

    public void onTokenChanged(Optional<TwitchToken> newToken) {
        if (newToken.isEmpty()) {
            terminate();
        } else if (settings.get().autostart()) {
            restart();
        }
    }

    public void onSettingsChanged(LurkerSettings before, LurkerSettings after) {
        if (startedAt().isEmpty()) {
            return;
        }
        if (!before.slots().equals(after.slots())) {
            sendCommand(Map.of("cmd", "slots", "slots", after.slots()));
        }
        if (!before.order().equals(after.order())) {
            sendCommand(Map.of("cmd", "order", "order", after.order()));
        }
        if (!before.minerRelevantDiff(after)) {
            return;
        }
        boolean onlyAdditions = before.streamers().stream().allMatch(after.streamers()::contains)
                && before.withStreamers(after.streamers()).equals(after);
        if (onlyAdditions) {
            after.streamers().stream().filter(s -> !before.streamers().contains(s)).forEach(this::addChannelLive);
        } else {
            restart();
        }
    }

    public void addChannelLive(String login) {
        sendCommand(Map.of("cmd", "add", "login", login));
    }

    private synchronized void sendCommand(Map<String, ?> command) {
        if (stdin == null) {
            return;
        }
        try {
            stdin.write(json.writeValueAsString(command) + "\n");
            stdin.flush();
        } catch (IOException e) {
            log.warn("Could not send command to runner: {}", e.getMessage());
        }
    }

    private void launchIfPossible() {
        if (process != null && process.isAlive()) {
            return;
        }
        cancelPendingRestart();
        if (token.get().isEmpty() || !settings.get().autostart()) {
            return;
        }
        try {
            writeConfig(settings.get());
            var pb = new ProcessBuilder(python, "-u", runnerScript.toAbsolutePath().toString(), configFile.toAbsolutePath().toString())
                    .directory(runnerScript.toAbsolutePath().getParent().toFile());
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            var p = pb.start();
            process = p;
            startedAt = Instant.now();
            stopping = false;
            stdin = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
            stdoutReader = pump(p.getInputStream(), handler::handleStdout, "miner-stdout");
            pump(p.getErrorStream(), handler::handleStderr, "miner-stderr");
            p.onExit().thenAccept(this::onExit);
            log.info("Miner runner started (pid {})", p.pid());
        } catch (IOException e) {
            handler.handleStderr("Runner konnte nicht gestartet werden: " + e.getMessage());
            scheduleRestart();
        }
    }

    private Thread pump(InputStream in, Consumer<String> sink, String name) {
        return Thread.ofVirtual().name(name).start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        sink.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // stream closed with the process
            }
        });
    }

    private synchronized void onExit(Process p) {
        if (p != process) {
            return;
        }
        int code = p.exitValue();
        Duration ran = Duration.between(startedAt, Instant.now());
        process = null;
        stdin = null;
        state.clearSession();
        if (stopping) {
            return;
        }
        handler.handleStderr("Runner beendet mit Code " + code + " nach " + ran.toSeconds() + " s");
        if (ran.compareTo(STABLE_RUN) > 0) {
            consecutiveFailures = 0;
        }
        consecutiveFailures++;
        if (settings.get().autostart() && token.get().isPresent()) {
            scheduleRestart();
        }
    }

    private void scheduleRestart() {
        long factor = 1L << Math.min(consecutiveFailures - 1, 12);
        Duration delay = baseBackoff.multipliedBy(Math.max(1, factor));
        if (delay.compareTo(MAX_BACKOFF) > 0) {
            delay = MAX_BACKOFF;
        }
        cancelPendingRestart();
        pendingRestart = scheduler.schedule(() -> {
            synchronized (this) {
                restarts++;
                pendingRestart = null;
                launchIfPossible();
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void cancelPendingRestart() {
        if (pendingRestart != null) {
            pendingRestart.cancel(false);
            pendingRestart = null;
        }
    }

    private void terminate() {
        Process p;
        Thread reader;
        synchronized (this) {
            cancelPendingRestart();
            p = process;
            reader = stdoutReader;
            if (p == null) {
                return;
            }
            stopping = true;
            try {
                stdin.close();
            } catch (IOException ignored) {
                // process may already be gone
            }
        }
        try {
            if (!p.waitFor(25, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            }
            if (reader != null) {
                reader.join(Duration.ofSeconds(2));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (process == p) {
                process = null;
                stdin = null;
            }
            state.clearSession();
        }
    }

    private void writeConfig(LurkerSettings s) throws IOException {
        Files.createDirectories(workDir);
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("workDir", workDir.toAbsolutePath().toString());
        cfg.put("tokenFile", tokenFile.toAbsolutePath().toString());
        cfg.put("followers", s.followers());
        cfg.put("streamers", s.streamers());
        cfg.put("blacklist", s.blacklist());
        cfg.put("priority", s.priority());
        cfg.put("followRaid", s.followRaid());
        cfg.put("claimMoments", s.claimMoments());
        cfg.put("watchStreak", s.watchStreak());
        cfg.put("order", s.order());
        cfg.put("slots", s.slots());
        cfg.put("dropScout", Map.of("enabled", s.dropScout().enabled(), "channelsPerGame", s.dropScout().channelsPerGame(),
                "requireLinked", s.dropScout().requireLinked()));
        Path tmp = configFile.resolveSibling("miner-config.json.tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        json.writeValue(tmp.toFile(), cfg);
        Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

}
