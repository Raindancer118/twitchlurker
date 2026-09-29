package de.raindancer118.twitchlurker.miner;

import de.raindancer118.twitchlurker.config.StartupReport;
import de.raindancer118.twitchlurker.config.StartupReport.Stage;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.events.SnapshotRepository;
import de.raindancer118.twitchlurker.live.LiveBus;
import java.time.Clock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Translates the runner's JSON-lines protocol into state, stored events and live updates. */
public class MinerMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MinerMessageHandler.class);
    private static final Pattern STREAMER_REPR = Pattern.compile("Streamer\\(username=([a-z0-9_]+)");

    private final JsonMapper json;
    private final ObjectReader snapshotReader;
    private final ObjectReader dropsReader;
    private final ObjectReader catalogueReader;
    private final MinerState state;
    private final EventService events;
    private final SnapshotRepository snapshots;
    private final LiveBus bus;
    private final Clock clock;
    private final StartupReport startup;

    public MinerMessageHandler(JsonMapper json, MinerState state, EventService events, SnapshotRepository snapshots,
                               LiveBus bus, Clock clock) {
        this(json, state, events, snapshots, bus, clock, new StartupReport(clock, clock.instant(), line -> { }));
    }

    public MinerMessageHandler(JsonMapper json, MinerState state, EventService events, SnapshotRepository snapshots,
                               LiveBus bus, Clock clock, StartupReport startup) {
        this.startup = startup;
        this.json = json;
        this.snapshotReader = json.readerFor(MinerState.Snapshot.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.dropsReader = json.readerFor(MinerState.Drops.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.catalogueReader = json.readerFor(MinerState.Catalogue.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.state = state;
        this.events = events;
        this.snapshots = snapshots;
        this.bus = bus;
        this.clock = clock;
    }

    public void handleStdout(String line) {
        JsonNode node;
        try {
            node = json.readTree(line);
        } catch (JacksonException e) {
            raw("RAW", line);
            return;
        }
        if (node == null || !node.isObject()) {
            raw("RAW", line);
            return;
        }
        try {
            switch (node.path("t").asString("")) {
                case "state" -> onState(node);
                case "drops" -> onDrops(node);
                case "campaigns" -> {
                    MinerState.Catalogue parsed = catalogueReader.readValue(node);
                    var catalogue = new MinerState.Catalogue(parsed.campaigns(), parsed.access(), clock.instant());
                    state.update(catalogue);
                    bus.publish("campaigns", catalogue);
                    startup.reached(Stage.CATALOGUE);
                }
                case "event" -> onEvent(node);
                case "log" -> {
                    var entry = new MinerState.LogLine(clock.instant(), node.path("level").asString("INFO"),
                            node.path("logger").asString("miner"), node.path("msg").asString(""));
                    state.log(entry);
                    bus.publish("log", entry);
                    if ("runner".equals(entry.source())) {
                        log.info("runner: {}", entry.msg());
                    }
                }
                case "status" -> bus.publish("status", node);
                default -> raw("RAW", line);
            }
        } catch (RuntimeException e) {
            log.warn("Could not handle runner message {}: {}", line, e.toString());
            raw("RAW", line);
        }
    }

    public void handleStderr(String line) {
        raw("STDERR", line);
    }

    private void raw(String level, String line) {
        var entry = new MinerState.LogLine(clock.instant(), level, "runner", line);
        state.log(entry);
        bus.publish("log", entry);
    }

    private void onState(JsonNode node) {
        MinerState.Snapshot parsed = snapshotReader.readValue(node);
        var snap = new MinerState.Snapshot(parsed.user(), parsed.session(), parsed.startedAt(), parsed.streamers(), clock.instant());
        state.update(snap);
        var now = clock.instant();
        for (var s : snap.streamers()) {
            snapshots.recordIfChanged(s.login(), now, s.points());
        }
        bus.publish("state", snap);
        startup.reached(Stage.CHANNELS);
    }

    private void onDrops(JsonNode node) {
        MinerState.Drops parsed = dropsReader.readValue(node);
        var drops = new MinerState.Drops(parsed.campaigns(), parsed.claimed(), clock.instant());
        state.update(drops);
        bus.publish("drops", drops);
        startup.reached(Stage.DROPS);
    }

    private void onEvent(JsonNode node) {
        String login = node.path("login").asString(null);
        switch (node.path("event").asString("")) {
            case "POINTS" -> events.record("POINTS", login, node.path("amount").asLong(), node.path("reason").asString(null));
            case "BONUS" -> events.record("BONUS", login, null, null);
            case "MOMENT" -> events.record("MOMENT", login, null, null);
            case "RAID" -> events.record("RAID", login, null, node.path("target").asString(null));
            case "DROP" -> {
                String benefit = node.path("benefit").asString(null);
                events.record("DROP", null, null, benefit != null && !benefit.isBlank() ? benefit : node.path("name").asString(null));
            }
            case "STREAMER_ADDED" -> events.record("ADDED", login, null, node.path("source").asString(null));
            case "STREAMER_REMOVED" -> events.record("REMOVED", login, null, null);
            case "STREAMER_ONLINE" -> fromRepr(node, "ONLINE");
            case "STREAMER_OFFLINE" -> fromRepr(node, "OFFLINE");
            default -> {
                // GAIN_FOR_*, BONUS_CLAIM, DROP_CLAIM, JOIN_RAID duplicate the structured hook events above.
            }
        }
    }

    private void fromRepr(JsonNode node, String type) {
        var m = STREAMER_REPR.matcher(node.path("msg").asString(""));
        if (m.find()) {
            events.record(type, m.group(1), null, null);
        }
    }
}
