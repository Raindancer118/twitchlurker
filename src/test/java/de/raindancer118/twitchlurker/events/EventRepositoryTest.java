package de.raindancer118.twitchlurker.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer118.twitchlurker.support.TestDb;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventRepositoryTest {

    @TempDir
    Path dir;

    EventRepository events;
    SnapshotRepository snapshots;
    final Instant now = Instant.parse("2026-09-28T12:00:00Z");

    @BeforeEach
    void setUp() {
        var jdbc = TestDb.migrated(dir);
        events = new EventRepository(jdbc);
        snapshots = new SnapshotRepository(jdbc);
    }

    @Test
    void storesAndAggregatesPoints() {
        events.insert(new LurkerEvent(0, now.minus(2, ChronoUnit.DAYS), "POINTS", "papaplatte", 50L, "WATCH"));
        events.insert(new LurkerEvent(0, now.minus(1, ChronoUnit.HOURS), "POINTS", "papaplatte", 10L, "WATCH"));
        events.insert(new LurkerEvent(0, now.minus(30, ChronoUnit.MINUTES), "POINTS", "zarbex", 50L, "CLAIM"));
        var bonus = events.insert(new LurkerEvent(0, now.minus(30, ChronoUnit.MINUTES), "BONUS", "zarbex", null, null));
        assertThat(bonus.id()).isPositive();

        Instant today = now.truncatedTo(ChronoUnit.DAYS);
        assertThat(events.sumPoints(today)).isEqualTo(60);
        assertThat(events.sumPoints(now.minus(7, ChronoUnit.DAYS))).isEqualTo(110);
        assertThat(events.count("BONUS", today)).isEqualTo(1);
        assertThat(events.pointsByLogin(today)).containsEntry("papaplatte", 10L).containsEntry("zarbex", 50L);
        assertThat(events.recent(2)).extracting(LurkerEvent::type).containsExactly("BONUS", "POINTS");
        assertThat(events.forLogin("papaplatte", now.minus(7, ChronoUnit.DAYS), 10)).hasSize(2);

        assertThat(events.deleteOlderThan(now.minus(1, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(events.sumPoints(Instant.EPOCH)).isEqualTo(60);
    }

    @Test
    void snapshotsOnlyWhenChanged() {
        assertThat(snapshots.recordIfChanged("papaplatte", now.minusSeconds(1200), 1000)).isTrue();
        assertThat(snapshots.recordIfChanged("papaplatte", now.minusSeconds(600), 1000)).isFalse();
        assertThat(snapshots.recordIfChanged("papaplatte", now, 1010)).isTrue();
        assertThat(snapshots.history("papaplatte", now.minusSeconds(3600))).extracting(SnapshotRepository.Point::points)
                .containsExactly(1000L, 1010L);
        assertThat(snapshots.historyAll(now.minusSeconds(3600))).containsKey("papaplatte");
    }
}
