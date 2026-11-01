package com.radioparadise.tracker.records;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RECORDS_DB_TESTS", matches = "true")
class TimestampMigrationPostgresTest {
    private Connection fixture() throws Exception {
        String url = System.getenv("DB_URL");
        assertThat(url).endsWith("/records_test");
        Connection c = DriverManager.getConnection(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        try (Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS timestamp_migration_test CASCADE");
            s.execute("CREATE SCHEMA timestamp_migration_test");
            s.execute("SET search_path TO timestamp_migration_test");
            s.execute("SET TIME ZONE 'America/New_York'");
            s.execute("CREATE TABLE song (id bigint PRIMARY KEY, first_played timestamp NOT NULL, last_played timestamp NOT NULL, play_count integer NOT NULL)");
            s.execute("CREATE TABLE playback_event (id bigint PRIMARY KEY, song_id bigint NOT NULL REFERENCES song(id), observed_at timestamptz NOT NULL)");
            s.execute("INSERT INTO song VALUES (1, '2026-11-01 13:00:00', '2026-11-01 13:10:00', 9)");
            s.execute("INSERT INTO playback_event VALUES (1, 1, '2026-11-01 10:03:00.123456+00'), (2, 1, '2026-11-01 10:07:00.654321+00')");
        }
        return c;
    }

    private String migration() throws Exception {
        return Files.readString(Path.of("scripts/migrations/2026-11-01-unify-playback-times.sql"));
    }

    @Test
    void migrationUsesEventsAsTruthAndCanRunTwiceInANonIstanbulSession() throws Exception {
        try (Connection c = fixture(); Statement s = c.createStatement()) {
            for (int run = 0; run < 2; run++) {
                s.execute(migration());
                try (var r = s.executeQuery("SELECT first_played = min(p.observed_at) AS first_matches, last_played = max(p.observed_at) AS last_matches, play_count = count(*) AS count_matches, pg_typeof(first_played)::text AS type FROM song JOIN playback_event p ON song.id = p.song_id GROUP BY song.id")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString("type")).isEqualTo("timestamp with time zone");
                    assertThat(r.getBoolean("first_matches")).isTrue();
                    assertThat(r.getBoolean("last_matches")).isTrue();
                    assertThat(r.getBoolean("count_matches")).isTrue();
                }
                try (var r = s.executeQuery("SELECT count(*), min(observed_at) AT TIME ZONE 'UTC' AS first_time, max(observed_at) AT TIME ZONE 'UTC' AS last_time FROM playback_event")) {
                    r.next();
                    assertThat(r.getInt(1)).isEqualTo(2);
                    assertThat(r.getString(2)).isEqualTo("2026-11-01 10:03:00.123456");
                    assertThat(r.getString(3)).isEqualTo("2026-11-01 10:07:00.654321");
                }
            }
        }
    }

    @Test
    void migrationRejectsUnrelatedSongsWithoutPartiallyChangingSchemaOrCounts() throws Exception {
        try (Connection c = fixture(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO song VALUES (2, '2026-11-01 13:00:00', '2026-11-01 13:00:00', 1)");
            assertThatThrownBy(() -> s.execute(migration())).hasMessageContaining("unrelated song");
            s.execute("ROLLBACK");
            try (var r = s.executeQuery("SELECT pg_typeof(first_played)::text, play_count FROM song WHERE id = 1")) {
                r.next();
                assertThat(r.getString(1)).isEqualTo("timestamp without time zone");
                assertThat(r.getInt(2)).isEqualTo(9);
            }
        }
    }
}
