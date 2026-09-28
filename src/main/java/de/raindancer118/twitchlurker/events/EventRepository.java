package de.raindancer118.twitchlurker.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public class EventRepository {

    private static final RowMapper<LurkerEvent> ROW = (rs, i) -> new LurkerEvent(
            rs.getLong("id"), Instant.ofEpochMilli(rs.getLong("ts")), rs.getString("type"), rs.getString("login"),
            rs.getObject("amount") == null ? null : rs.getLong("amount"), rs.getString("detail"));

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LurkerEvent insert(LurkerEvent e) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO events (ts, type, login, amount, detail) VALUES (?, ?, ?, ?, ?)")
                .params(e.ts().toEpochMilli(), e.type(), e.login(), e.amount(), e.detail())
                .update(keys, "id");
        return e.withId(keys.getKey().longValue());
    }

    public List<LurkerEvent> recent(int limit) {
        return jdbc.sql("SELECT * FROM events ORDER BY ts DESC, id DESC LIMIT ?").param(limit).query(ROW).list();
    }

    public List<LurkerEvent> forLogin(String login, Instant since, int limit) {
        return jdbc.sql("SELECT * FROM events WHERE login = ? AND ts >= ? ORDER BY ts DESC, id DESC LIMIT ?")
                .params(login, since.toEpochMilli(), limit).query(ROW).list();
    }

    public long sumPoints(Instant since) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM events WHERE type = 'POINTS' AND ts >= ?")
                .param(since.toEpochMilli()).query(Long.class).single();
    }

    public long count(String type, Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM events WHERE type = ? AND ts >= ?")
                .params(type, since.toEpochMilli()).query(Long.class).single();
    }

    public Map<String, Long> pointsByLogin(Instant since) {
        var out = new LinkedHashMap<String, Long>();
        jdbc.sql("SELECT login, SUM(amount) AS s FROM events WHERE type = 'POINTS' AND ts >= ? GROUP BY login")
                .param(since.toEpochMilli())
                .query(rs -> {
                    out.put(rs.getString("login"), rs.getLong("s"));
                });
        return out;
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM events WHERE ts < ?").param(cutoff.toEpochMilli()).update();
    }
}
