package de.raindancer118.twitchlurker.miner;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Latest snapshots reported by the runner, held in memory. */
public class MinerState {

    public record Streamer(String login, String channelId, boolean online, boolean watching, long points, String game,
                           String title, long viewers, Double onlineSince, double minutesWatched, boolean streakPending,
                           boolean dropsEligible, boolean multiplier, String source) {}

    public record Snapshot(String user, String session, String startedAt, List<Streamer> streamers, Instant receivedAt) {}

    public record Drop(String id, String name, String image, int required, int watched, boolean claimed) {}

    public record Campaign(String id, String name, String game, String image, String endsAt, boolean linked, List<Drop> drops) {}

    public record Claimed(String id, String name, String image, String at, String game) {}

    public record Drops(List<Campaign> campaigns, List<Claimed> claimed, Instant receivedAt) {}

    public record Reward(String name, String image, int minutes, Integer subs) {}

    public record CatalogueCampaign(String id, String name, String game, String gameId, String image, String status,
                                    String startAt, String endAt, Boolean linked, String linkUrl, List<String> channels,
                                    List<Reward> rewards, boolean watched, Boolean watchable) {}

    /** access: "ok" (Android token works), "missing" (no drops login yet), "rejected" (token no longer accepted). */
    public record Catalogue(List<CatalogueCampaign> campaigns, String access, Instant receivedAt) {}

    public record LogLine(Instant ts, String level, String source, String msg) {}

    private static final int LOG_CAPACITY = 1000;

    private volatile Snapshot snapshot;
    private volatile Drops drops;
    private volatile Catalogue catalogue;

    public Optional<Catalogue> catalogue() {
        return Optional.ofNullable(catalogue);
    }

    public void update(Catalogue c) {
        catalogue = c;
    }
    private final ArrayDeque<LogLine> logs = new ArrayDeque<>();

    public Optional<Snapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void update(Snapshot s) {
        snapshot = s;
    }

    public Optional<Drops> drops() {
        return Optional.ofNullable(drops);
    }

    public void update(Drops d) {
        drops = d;
    }

    public void clearSession() {
        snapshot = null;
    }

    public List<Streamer> onlineStreamers() {
        var s = snapshot;
        return s == null ? List.of() : s.streamers().stream().filter(Streamer::online).toList();
    }

    public synchronized void log(LogLine line) {
        if (logs.size() >= LOG_CAPACITY) {
            logs.removeFirst();
        }
        logs.addLast(line);
    }

    public synchronized List<LogLine> logs(int limit) {
        var all = new ArrayList<>(logs);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }
}
