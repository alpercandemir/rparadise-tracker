package com.radioparadise.tracker.records;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RECORDS_DB_TESTS", matches = "true")
class SongIndexMigrationPostgresTest {
    @Test
    void concurrentMigrationCanRunTwiceWithoutChangingExistingSongs() throws Exception {
        String url = System.getenv("DB_URL");
        assertThat(url).endsWith("/records_test");
        String migration = Files.readString(
                Path.of("scripts/migrations/2026-11-02-add-song-recency-indexes.sql"));

        try (var connection = DriverManager.getConnection(
                url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA song_index_migration_test");
            statement.execute("SET search_path TO song_index_migration_test");
            statement.execute("CREATE TABLE song (id bigint PRIMARY KEY, channel varchar NOT NULL, "
                    + "last_played timestamptz NOT NULL)");
            statement.execute("INSERT INTO song VALUES (1, '0', '2026-11-02 10:00:00+00')");

            // Each statement needs its own transaction for CREATE INDEX CONCURRENTLY.
            for (int run = 0; run < 2; run++) {
                applyMigration(statement, migration);
                assertRecencyIndexes(statement);
            }
            try (var songs = statement.executeQuery("SELECT id, channel, last_played FROM song")) {
                assertThat(songs.next()).isTrue();
                assertThat(songs.getLong("id")).isEqualTo(1L);
                assertThat(songs.getString("channel")).isEqualTo("0");
                assertThat(songs.getTimestamp("last_played").toInstant().toString())
                        .isEqualTo("2026-11-02T10:00:00Z");
                assertThat(songs.next()).isFalse();
            }
        }
    }

    @Test
    void fuzzySearchIndexMigrationCanRunTwiceWithoutChangingExistingSongs() throws Exception {
        String url = System.getenv("DB_URL");
        assertThat(url).endsWith("/records_test");
        String migration = Files.readString(
                Path.of("scripts/migrations/2026-11-09-add-song-fuzzy-search-indexes.sql"));

        try (var connection = DriverManager.getConnection(
                url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA song_fuzzy_index_migration_test");
            statement.execute("SET search_path TO song_fuzzy_index_migration_test");
            statement.execute("CREATE TABLE song (id bigint PRIMARY KEY, title varchar NOT NULL, "
                    + "artist varchar NOT NULL, album varchar NOT NULL)");
            statement.execute("INSERT INTO song VALUES (1, 'Sheep', 'Pink Floyd', 'Animals')");

            for (int run = 0; run < 2; run++) {
                applyMigration(statement, migration);
                try (var indexes = statement.executeQuery(
                        "SELECT c.relname, i.indisvalid, i.indisready "
                                + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                                + "WHERE i.indrelid = 'song'::regclass AND NOT i.indisprimary "
                                + "ORDER BY c.relname")) {
                    assertThat(indexes.next()).isTrue();
                    assertThat(indexes.getString("relname")).isEqualTo("song_search_phonetic_idx");
                    assertThat(indexes.getBoolean("indisvalid")).isTrue();
                    assertThat(indexes.getBoolean("indisready")).isTrue();
                    assertThat(indexes.next()).isTrue();
                    assertThat(indexes.getString("relname")).isEqualTo("song_search_trgm_idx");
                    assertThat(indexes.getBoolean("indisvalid")).isTrue();
                    assertThat(indexes.getBoolean("indisready")).isTrue();
                    assertThat(indexes.next()).isFalse();
                }
            }
        }
    }

    private void applyMigration(Statement statement, String migration) throws Exception {
        for (String sql : migration.split(";")) {
            if (!sql.isBlank()) {
                statement.execute(sql);
            }
        }
    }

    private void assertRecencyIndexes(Statement statement) throws Exception {
        try (var indexes = statement.executeQuery(
                "SELECT c.relname, i.indisvalid, i.indisready, "
                        + "pg_get_indexdef(i.indexrelid) AS definition "
                        + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE i.indrelid = 'song'::regclass AND NOT i.indisprimary "
                        + "ORDER BY c.relname")) {
            assertIndex(indexes, "song_channel_last_played_id_idx", "channel, last_played DESC, id DESC");
            assertIndex(indexes, "song_last_played_id_idx", "last_played DESC, id DESC");
            assertThat(indexes.next()).isFalse();
        }
    }

    private void assertIndex(ResultSet indexes, String name, String columns) throws Exception {
        assertThat(indexes.next()).isTrue();
        assertThat(indexes.getString("relname")).isEqualTo(name);
        assertThat(indexes.getBoolean("indisvalid")).isTrue();
        assertThat(indexes.getBoolean("indisready")).isTrue();
        assertThat(indexes.getString("definition")).endsWith("USING btree (" + columns + ")");
    }
}
