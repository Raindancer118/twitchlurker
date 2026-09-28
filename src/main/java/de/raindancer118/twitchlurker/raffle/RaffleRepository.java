package de.raindancer118.twitchlurker.raffle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public class RaffleRepository {

    private static final RowMapper<RaffleEntry> ROW = (rs, i) -> new RaffleEntry(
            rs.getLong("id"), Instant.ofEpochMilli(rs.getLong("ts")), rs.getString("channel"), rs.getString("trigger_user"),
            rs.getString("trigger_message"), rs.getString("command"), RaffleEntry.Status.valueOf(rs.getString("status")),
            rs.getString("detail"), rs.getObject("sent_at") == null ? null : Instant.ofEpochMilli(rs.getLong("sent_at")));

    private final JdbcClient jdbc;

    public RaffleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Instant ts, String channel, String triggerUser, String triggerMessage, String command) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO raffles (ts, channel, trigger_user, trigger_message, command, status) VALUES (?, ?, ?, ?, ?, 'PENDING')")
                .params(ts.toEpochMilli(), channel, triggerUser, triggerMessage, command).update(keys, "id");
        return keys.getKey().longValue();
    }

    public void update(long id, RaffleEntry.Status status, String detail, Instant sentAt) {
        jdbc.sql("UPDATE raffles SET status = ?, detail = ?, sent_at = COALESCE(?, sent_at) WHERE id = ?")
                .params(status.name(), detail, sentAt == null ? null : sentAt.toEpochMilli(), id).update();
    }

    public Optional<RaffleEntry> find(long id) {
        return jdbc.sql("SELECT * FROM raffles WHERE id = ?").param(id).query(ROW).optional();
    }

    public List<RaffleEntry> recent(int limit) {
        return jdbc.sql("SELECT * FROM raffles ORDER BY ts DESC, id DESC LIMIT ?").param(limit).query(ROW).list();
    }

    public Optional<RaffleEntry> lastSent(String channel, Instant since) {
        return jdbc.sql("SELECT * FROM raffles WHERE channel = ? AND status IN ('JOINED', 'WON') AND sent_at >= ? ORDER BY sent_at DESC LIMIT 1")
                .params(channel, since.toEpochMilli()).query(ROW).optional();
    }

    public long count(RaffleEntry.Status status, Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM raffles WHERE status = ? AND ts >= ?")
                .params(status.name(), since.toEpochMilli()).query(Long.class).single();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM raffles WHERE ts < ?").param(cutoff.toEpochMilli()).update();
    }
}
