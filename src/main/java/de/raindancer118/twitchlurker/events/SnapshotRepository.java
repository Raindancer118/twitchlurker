package de.raindancer118.twitchlurker.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Channel-point balances over time, written only on change to keep the table small. */
public class SnapshotRepository {

    public record Point(Instant ts, long points) {}

    private final JdbcClient jdbc;

    public SnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean recordIfChanged(String login, Instant ts, long points) {
        var last = jdbc.sql("SELECT points FROM points_snapshots WHERE login = ? ORDER BY ts DESC LIMIT 1")
                .param(login).query(Long.class).optional();
        if (last.isPresent() && last.get() == points) {
            return false;
        }
        jdbc.sql("INSERT OR REPLACE INTO points_snapshots (ts, login, points) VALUES (?, ?, ?)")
                .params(ts.toEpochMilli(), login, points).update();
        return true;
    }

    public List<Point> history(String login, Instant since) {
        return jdbc.sql("SELECT ts, points FROM points_snapshots WHERE login = ? AND ts >= ? ORDER BY ts")
                .params(login, since.toEpochMilli())
                .query((rs, i) -> new Point(Instant.ofEpochMilli(rs.getLong("ts")), rs.getLong("points"))).list();
    }

    public Map<String, List<Point>> historyAll(Instant since) {
        var out = new LinkedHashMap<String, List<Point>>();
        jdbc.sql("SELECT login, ts, points FROM points_snapshots WHERE ts >= ? ORDER BY login, ts")
                .param(since.toEpochMilli())
                .query(rs -> {
                    out.computeIfAbsent(rs.getString("login"), k -> new ArrayList<>())
                            .add(new Point(Instant.ofEpochMilli(rs.getLong("ts")), rs.getLong("points")));
                });
        return out;
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM points_snapshots WHERE ts < ?").param(cutoff.toEpochMilli()).update();
    }
}
