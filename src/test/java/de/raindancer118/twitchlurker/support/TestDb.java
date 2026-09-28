package de.raindancer118.twitchlurker.support;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

public final class TestDb {

    private TestDb() {}

    public static JdbcClient migrated(Path dir) {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return JdbcClient.create((DataSource) ds);
    }
}
