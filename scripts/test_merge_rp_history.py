import copy
import json
import os
import tempfile
import unittest
from pathlib import Path

from test_import_rp_history import archive, playback, write_archive


MERGE_SQL = Path(__file__).with_name("merge-rp-history.sql")


@unittest.skipUnless(os.environ.get("HISTORY_DB_TESTS") == "true", "Disposable PostgreSQL only")
class MergePostgresTest(unittest.TestCase):
    def setUp(self):
        self.database = archive.Database(os.environ["HISTORY_TEST_CONTAINER"],
                                         "history_import_test", "history_import_test")
        self.assertEqual("history_import_test", self.database.query("SELECT current_database();"))
        self.database.query("""
            DROP SCHEMA IF EXISTS rp_history CASCADE;
            DROP TABLE IF EXISTS public.playback_event, public.song CASCADE;
            CREATE TABLE public.song (
                id bigserial PRIMARY KEY, channel varchar(255) NOT NULL,
                artist varchar(255) NOT NULL, title varchar(255) NOT NULL, album varchar(255) NOT NULL,
                release_year varchar(255), rp_song_id bigint, duration_seconds float8,
                cover_url varchar(500), cover_medium_url varchar(500), cover_small_url varchar(500),
                lyrics text, isrc varchar(12), recording_match_source varchar(500),
                first_played timestamptz NOT NULL, last_played timestamptz NOT NULL,
                play_count integer NOT NULL, UNIQUE(artist,title,album,channel));
            CREATE TABLE public.playback_event (
                id bigserial PRIMARY KEY, song_id bigint NOT NULL REFERENCES public.song(id),
                channel varchar(255) NOT NULL, observed_at timestamptz NOT NULL);
        """)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def stage(self, rows):
        write_archive(self.root, rows)
        archive.import_archive(self.root, self.database)

    def observe(self, row, at):
        data = row["data"]
        self.database.query(f"""
            WITH saved AS (
                INSERT INTO public.song
                    (channel,artist,title,album,rp_song_id,first_played,last_played,play_count,lyrics,isrc)
                VALUES ({archive.sql_literal(row['channel'])}, {archive.sql_literal(data['artist'])},
                        {archive.sql_literal(data['title'])}, {archive.sql_literal(data['album'])},
                        {int(row['song_id'])}, {archive.sql_literal(at)}, {archive.sql_literal(at)}, 1,
                        'saved lyrics', 'TEST12345678')
                RETURNING id
            )
            INSERT INTO public.playback_event (song_id,channel,observed_at)
            SELECT id,{archive.sql_literal(row['channel'])},{archive.sql_literal(at)} FROM saved;
        """)

    def merge(self, sql=None):
        return json.loads(self.database.query(sql or MERGE_SQL.read_text()))

    def test_historical_events_join_real_tables_without_overwriting_enrichment(self):
        row = playback()
        row["data"]["duration"] = "240000"
        row["data"]["cover"] = "covers/l/example.jpg"
        self.stage([row])
        self.observe(row, "2026-11-12T00:00:00Z")

        result = self.merge()

        self.assertEqual({"imported": 1}, result["outcomes"])
        self.assertEqual(1, result["songs"])
        self.assertEqual(2, result["playback_events"])
        self.assertEqual("2|saved lyrics|TEST12345678", self.database.query(
            "SELECT play_count,lyrics,isrc FROM public.song;"))
        self.assertEqual("2026-01-01 00:00:00+00|2026-11-12 00:00:00+00", self.database.query(
            "SELECT first_played,last_played FROM public.song;"))
        self.assertEqual("1", self.database.query(
            "SELECT count(*) FROM public.playback_event WHERE rp_source_key IS NOT NULL;"))
        repeated = self.merge()
        self.assertEqual(0, repeated["new_source_rows"])
        self.assertEqual(2, repeated["playback_events"])

    def test_new_songs_receive_real_broadcast_dates_and_resolved_artwork(self):
        row = playback()
        row["data"].update(duration="125000", cover="covers/l/example.jpg")
        self.stage([row])

        result = self.merge()

        self.assertEqual(1, result["new_songs"])
        self.assertEqual("1|125|https://img.radioparadise.com/covers/l/example.jpg|42",
                         self.database.query("SELECT play_count,duration_seconds,cover_url,rp_song_id FROM song;"))

    def test_observation_within_broadcast_is_counted_once_and_retains_its_timestamp(self):
        row = playback()
        row["data"]["duration"] = "240000"
        self.stage([row])
        self.observe(row, "2026-01-01T00:00:21Z")

        result = self.merge()

        self.assertEqual({"matched_observation": 1}, result["outcomes"])
        self.assertEqual(1, result["playback_events"])
        self.assertEqual("2026-01-01 00:00:21+00|", self.database.query(
            "SELECT observed_at,rp_source_key FROM playback_event;"))

    def test_conflicting_references_and_missing_metadata_are_held_for_review(self):
        first = playback()
        variant = copy.deepcopy(first)
        variant["broadcast_at"] = "2026-01-01T00:01:00Z"
        variant["data"]["play_time"] += 60000
        missing = copy.deepcopy(first)
        missing["event_id"] = missing["data"]["event"] = "124"
        missing["broadcast_at"] = "2026-01-02T00:00:00Z"
        missing["data"]["play_time"] += 86400000
        missing["data"]["title"] = None
        self.stage([first, variant, missing])

        result = self.merge()

        self.assertEqual({"ambiguous_event": 2, "invalid_metadata": 1}, result["outcomes"])
        self.assertEqual(0, result["songs"])
        self.assertEqual(0, result["playback_events"])

    def test_two_event_ids_at_the_same_channel_time_are_not_double_counted(self):
        first = playback()
        other = copy.deepcopy(first)
        other["event_id"] = other["data"]["event"] = "124"
        self.stage([first, other])
        self.assertEqual({"ambiguous_broadcast_time": 2}, self.merge()["outcomes"])

    def test_rp_identity_reconciles_an_observation_with_changed_metadata(self):
        row = playback()
        self.stage([row])
        observed = copy.deepcopy(row)
        observed["data"]["title"] = "Updated song title"
        self.observe(observed, "2026-01-01T00:00:21Z")
        result = self.merge()
        self.assertEqual({"matched_observation": 1}, result["outcomes"])
        self.assertEqual(1, result["songs"])
        self.assertEqual("Updated song title", self.database.query("SELECT title FROM song;"))

    def test_next_broadcast_bounds_the_match_when_the_same_song_replays(self):
        first = playback()
        first["data"]["duration"] = "1200000"
        second = copy.deepcopy(first)
        second["event_id"] = second["data"]["event"] = "124"
        second["broadcast_at"] = "2026-01-01T00:10:00Z"
        second["data"]["play_time"] += 600000
        self.stage([first, second])
        self.observe(second, "2026-01-01T00:12:00Z")
        result = self.merge()
        self.assertEqual({"imported": 1, "matched_observation": 1}, result["outcomes"])
        self.assertEqual(2, result["playback_events"])

    def test_close_observation_before_source_time_is_held_as_ambiguous(self):
        row = playback()
        self.stage([row])
        self.observe(row, "2025-12-31T23:59:40Z")
        result = self.merge()
        self.assertEqual({"ambiguous_observation": 1}, result["outcomes"])
        self.assertEqual(1, result["playback_events"])

    def test_archive_cannot_advance_an_existing_live_checkpoint(self):
        row = playback()
        self.stage([row])
        earlier = copy.deepcopy(row)
        earlier["data"]["title"] = "Another song"
        self.observe(earlier, "2025-12-01T00:00:00Z")
        with self.assertRaises(archive.subprocess.CalledProcessError):
            self.merge()
        self.assertEqual("1|1", self.database.query(
            "SELECT (SELECT count(*) FROM song),(SELECT count(*) FROM playback_event);"))

    def test_failure_rolls_back_real_table_inserts_and_allows_a_clean_retry(self):
        self.stage([playback()])
        broken = MERGE_SQL.read_text().replace("\nCOMMIT;", "\nSELECT 1/0;\nCOMMIT;")
        with self.assertRaises(archive.subprocess.CalledProcessError):
            self.merge(broken)
        self.assertEqual("0|0", self.database.query(
            "SELECT (SELECT count(*) FROM song),(SELECT count(*) FROM playback_event);"))
        self.assertEqual(1, self.merge()["playback_events"])


if __name__ == "__main__":
    unittest.main()
