package de.raindancer118.twitchlurker.web;

import de.raindancer118.twitchlurker.config.LurkerProperties;
import de.raindancer118.twitchlurker.config.StartupReport;
import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.LurkerEvent;
import de.raindancer118.twitchlurker.events.SnapshotRepository;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.miner.MinerSupervisor;
import de.raindancer118.twitchlurker.raffle.RaffleEntry;
import de.raindancer118.twitchlurker.raffle.RaffleRepository;
import de.raindancer118.twitchlurker.raffle.RaffleService;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.twitch.TwitchAuthService;
import de.raindancer118.twitchlurker.twitch.TwitchAuthStatus;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

    /** startup: percent while the backend is still loading, null once it is up. */
    public record Bot(MinerSupervisor.Status status, Instant startedAt, int restarts, String user, Integer startup) {}

    public record Stats(long pointsToday, long pointsWeek, long pointsTotal, long bonusesToday, long rafflesToday,
                        long rafflesWon, long dropsToday, long dropsTotal) {}

    public record Slot(int index, String pinned, boolean pinnedOnline, MinerState.Streamer streamer) {}

    public record Overview(Bot bot, TwitchAuthStatus twitch, RaffleService.Status raffle, List<MinerState.Streamer> watching,
                           List<Slot> slots, int online, int tracked, Stats stats, List<LurkerEvent> feed) {}

    public record Channel(String login, boolean online, boolean watching, long points, String game, String title, long viewers,
                          boolean streakPending, boolean dropsEligible, String source, long gainedToday, long gainedWeek,
                          List<Long> spark, boolean raffles, boolean blacklisted, int rank, int slot) {}

    public record ChannelDetail(Channel channel, List<SnapshotRepository.Point> history, List<LurkerEvent> events) {}

    public record Drops(List<MinerState.Campaign> campaigns, List<MinerState.Claimed> claimed, Instant updatedAt,
                        List<MinerState.Streamer> scouted, boolean scoutEnabled, List<MinerState.CatalogueCampaign> catalogue,
                        Instant catalogueUpdatedAt, List<String> watchGames, String catalogueAccess) {}

    public record Raffles(RaffleService.Status status, List<RaffleEntry> entries, long joinedToday, long wonTotal) {}

    private static final int SPARK_POINTS = 32;

    private final MinerState state;
    private final MinerSupervisor supervisor;
    private final TwitchAuthService auth;
    private final RaffleService raffleService;
    private final EventRepository events;
    private final SnapshotRepository snapshots;
    private final RaffleRepository raffles;
    private final SettingsStore settings;
    private final LurkerProperties props;
    private final Clock clock;
    private final StartupReport startup;

    public DashboardService(MinerState state, MinerSupervisor supervisor, TwitchAuthService auth, RaffleService raffleService,
                            EventRepository events, SnapshotRepository snapshots, RaffleRepository raffles, SettingsStore settings,
                            LurkerProperties props, Clock clock, StartupReport startup) {
        this.startup = startup;
        this.state = state;
        this.supervisor = supervisor;
        this.auth = auth;
        this.raffleService = raffleService;
        this.events = events;
        this.snapshots = snapshots;
        this.raffles = raffles;
        this.settings = settings;
        this.props = props;
        this.clock = clock;
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(props.zone())).atStartOfDay(props.zone()).toInstant();
    }

    private Instant startOfWeek() {
        return LocalDate.now(clock.withZone(props.zone())).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(props.zone()).toInstant();
    }

    public Bot bot() {
        return new Bot(supervisor.status(), supervisor.startedAt().orElse(null), supervisor.restarts(),
                state.snapshot().map(MinerState.Snapshot::user).orElse(auth.status().login()),
                startup.isUp() ? null : startup.progress().percent());
    }

    public Overview overview() {
        var streamers = state.snapshot().map(MinerState.Snapshot::streamers).orElse(List.of());
        Instant today = startOfToday();
        var stats = new Stats(events.sumPoints(today), events.sumPoints(startOfWeek()), events.sumPoints(Instant.EPOCH),
                events.count("BONUS", today), events.count("RAFFLE", today), events.count("RAFFLE_WON", Instant.EPOCH),
                events.count("DROP", today), events.count("DROP", Instant.EPOCH));
        var watching = streamers.stream().filter(MinerState.Streamer::watching).toList();
        return new Overview(bot(), auth.status(), raffleService.status(), watching, slots(streamers, watching),
                (int) streamers.stream().filter(MinerState.Streamer::online).count(), streamers.size(), stats, events.recent(40));
    }

    /** Pinned channels keep their slot while watched; free slots show whatever the bot picked. */
    List<Slot> slots(List<MinerState.Streamer> streamers, List<MinerState.Streamer> watching) {
        var pins = settings.get().slots();
        var remaining = new ArrayList<>(watching);
        var shown = new MinerState.Streamer[2];
        for (int i = 0; i < 2; i++) {
            String pin = pins.get(i);
            if (pin == null) {
                continue;
            }
            for (var w : remaining) {
                if (w.login().equals(pin)) {
                    shown[i] = w;
                    remaining.remove(w);
                    break;
                }
            }
        }
        for (int i = 0; i < 2; i++) {
            if (shown[i] == null && !remaining.isEmpty()) {
                shown[i] = remaining.removeFirst();
            }
        }
        var out = new ArrayList<Slot>(2);
        for (int i = 0; i < 2; i++) {
            String pin = pins.get(i);
            boolean pinOnline = pin != null && streamers.stream().anyMatch(st -> st.login().equals(pin) && st.online());
            out.add(new Slot(i + 1, pin, pinOnline, shown[i]));
        }
        return out;
    }

    public List<Channel> channels() {
        var s = settings.get();
        Set<String> rafflesOff = Set.copyOf(s.raffle().disabledChannels());
        Set<String> blacklist = Set.copyOf(s.blacklist());
        var today = events.pointsByLogin(startOfToday());
        var week = events.pointsByLogin(clock.instant().minus(Duration.ofDays(7)));
        var history = snapshots.historyAll(clock.instant().minus(Duration.ofDays(7)));

        var byLogin = new LinkedHashMap<String, MinerState.Streamer>();
        state.snapshot().ifPresent(snap -> snap.streamers().forEach(st -> byLogin.put(st.login(), st)));
        // Bot stopped: still show channels we have history for.
        history.forEach((login, points) -> byLogin.computeIfAbsent(login, l -> new MinerState.Streamer(l, null, false, false,
                points.getLast().points(), null, null, 0, null, 0, false, false, false, "follow", null, null)));

        // The saved order is authoritative (the runner may lag a cycle behind); unranked channels keep the runner's order.
        var ordered = new ArrayList<>(byLogin.values());
        var rankOf = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < s.order().size(); i++) {
            rankOf.putIfAbsent(s.order().get(i), i);
        }
        ordered.sort(java.util.Comparator.comparingInt(st -> rankOf.getOrDefault(st.login(), Integer.MAX_VALUE)));
        var slots = s.slots();
        var out = new ArrayList<Channel>();
        int rank = 1;
        for (var st : ordered) {
            int slot = st.login().equals(slots.get(0)) ? 1 : st.login().equals(slots.get(1)) ? 2 : 0;
            out.add(toChannel(st, today, week, history.getOrDefault(st.login(), List.of()), rafflesOff, blacklist, rank++, slot));
        }
        return out;
    }

    private Channel toChannel(MinerState.Streamer st, Map<String, Long> today, Map<String, Long> week,
                              List<SnapshotRepository.Point> history, Set<String> rafflesOff, Set<String> blacklist,
                              int rank, int slot) {
        return new Channel(st.login(), st.online(), st.watching(), st.points(), st.game(), st.title(), st.viewers(),
                st.streakPending(), st.dropsEligible(), st.source(), today.getOrDefault(st.login(), 0L),
                week.getOrDefault(st.login(), 0L), spark(history), !rafflesOff.contains(st.login()), blacklist.contains(st.login()), rank, slot);
    }

    static List<Long> spark(List<SnapshotRepository.Point> history) {
        if (history.size() <= SPARK_POINTS) {
            return history.stream().map(SnapshotRepository.Point::points).toList();
        }
        var out = new ArrayList<Long>(SPARK_POINTS);
        for (int i = 0; i < SPARK_POINTS; i++) {
            int idx = (int) Math.round((double) i * (history.size() - 1) / (SPARK_POINTS - 1));
            out.add(history.get(idx).points());
        }
        return out;
    }

    public ChannelDetail channel(String login, int days) {
        var since = clock.instant().minus(Duration.ofDays(days));
        var channel = channels().stream().filter(c -> c.login().equals(login)).findFirst().orElse(null);
        return new ChannelDetail(channel, snapshots.history(login, since), events.forLogin(login, since, 200));
    }

    public Drops drops() {
        var d = state.drops();
        var scouted = state.snapshot().map(s -> s.streamers().stream().filter(st -> "drops".equals(st.source())).toList())
                .orElse(List.of());
        return new Drops(d.map(MinerState.Drops::campaigns).orElse(List.of()), d.map(MinerState.Drops::claimed).orElse(List.of()),
                d.map(MinerState.Drops::receivedAt).orElse(null), scouted, settings.get().dropScout().enabled(),
                state.catalogue().map(MinerState.Catalogue::campaigns).orElse(List.of()),
                state.catalogue().map(MinerState.Catalogue::receivedAt).orElse(null), settings.get().dropScout().games(),
                state.catalogue().map(MinerState.Catalogue::access).orElse(null));
    }

    public Raffles raffles() {
        return new Raffles(raffleService.status(), raffles.recent(200), raffles.count(RaffleEntry.Status.JOINED, startOfToday())
                + raffles.count(RaffleEntry.Status.WON, startOfToday()), raffles.count(RaffleEntry.Status.WON, Instant.EPOCH));
    }
}
