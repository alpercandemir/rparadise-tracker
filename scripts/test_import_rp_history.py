import copy
import concurrent.futures
import gzip
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location(
    "rp_import", Path(__file__).with_name("import-rp-history.py"))
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


def playback():
    return {"schema_version": 1, "channel": "0", "event_id": "123", "song_id": "42",
            "broadcast_at": "2026-01-01T00:00:00.000Z", "fetched_at": "2026-11-11T00:00:00Z",
            "source_url": "https://example.com/page", "data": {
                "chan": 0, "event": "123", "song_id": "42", "play_time": 1767225600000,
                "artist": "O'Brien\t\\.\n雪", "title": "A song", "album": "An album"}}


def write_archive(root, rows):
    shard = root / "channel-0/plays/000000.jsonl.gz"
    shard.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(shard, "wt", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    verification = {"status": "verified", "channels": {"0": {"plays": len(rows)}},
                    "files": [{"path": str(shard.relative_to(root)), "bytes": shard.stat().st_size,
                               "sha256": hashlib.sha256(shard.read_bytes()).hexdigest()}]}
    (root / "verification.json").write_text(json.dumps(verification))
    (root / "manifest.json").write_text(json.dumps({
        "status": "exhausted", "channels": {"0": {"plays": len(rows)}}}))
    return verification["files"][0]


class SourcePlayTest(unittest.TestCase):
    def test_fetch_provenance_does_not_change_identity(self):
        row = playback()
        original = archive.source_play(row, "0")
        row["fetched_at"] = "2026-11-12T00:00:00Z"
        row["source_url"] = "https://example.com/another-page"
        self.assertEqual(original, archive.source_play(row, "0"))

    def test_same_event_with_different_broadcast_time_is_preserved(self):
        row = playback()
        original, _ = archive.source_play(row, "0")
        row["broadcast_at"] = "2026-01-01T00:01:00.000Z"
        row["data"]["play_time"] += 60000
        self.assertNotEqual(original, archive.source_play(row, "0")[0])

    def test_payload_variant_is_preserved(self):
        row = playback()
        original, _ = archive.source_play(row, "0")
        row["data"]["title"] = "Changed title"
        self.assertNotEqual(original, archive.source_play(row, "0")[0])

    def test_missing_metadata_is_flagged_without_fabricating_a_song(self):
        row = playback()
        row["data"]["artist"] = ""
        row["data"]["title"] = None
        row["song_id"] = row["data"]["song_id"] = "0"
        self.assertEqual(["missing_artist", "missing_title", "nonpositive_song_id"],
                         archive.source_play(row, "0")[1])

    def test_mismatched_identity_and_timestamp_are_rejected(self):
        for field, value in (("channel", "1"), ("event_id", "124"),
                             ("broadcast_at", None), ("broadcast_at", "2026-01-02T00:00:00Z"),
                             ("broadcast_at", "2026-01-01T00:00:00")):
            with self.subTest(field=field, value=value):
                row = playback()
                row[field] = value
                with self.assertRaises(ValueError):
                    archive.source_play(row, "0")

    def test_modified_or_unverified_shard_is_rejected_before_database_access(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = write_archive(root, [playback()])
            (root / entry["path"]).write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                archive.verified_files(root)

    def test_verification_must_cover_all_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = write_archive(root, [playback()])
            (root / "channel-0/plays/000001.jsonl.gz").write_bytes((root / entry["path"]).read_bytes())
            with self.assertRaisesRegex(ValueError, "every playback shard"):
                archive.verified_files(root)


@unittest.skipUnless(os.environ.get("HISTORY_DB_TESTS") == "true", "Disposable PostgreSQL only")
class ImportPostgresTest(unittest.TestCase):
    def setUp(self):
        self.database = archive.Database(os.environ["HISTORY_TEST_CONTAINER"],
                                         "history_import_test", "history_import_test")
        self.assertEqual("history_import_test", self.database.query("SELECT current_database();"))
        self.database.query("DROP SCHEMA IF EXISTS rp_history CASCADE;")
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_import_preserves_variants_and_provenance_then_repeats_without_changes(self):
        first = playback()
        variant = copy.deepcopy(first)
        variant["broadcast_at"] = "2026-01-01T00:01:00.000Z"
        variant["data"]["play_time"] += 60000
        missing = copy.deepcopy(first)
        missing["data"]["artist"] = ""
        invalid = copy.deepcopy(first)
        invalid["broadcast_at"] = None
        write_archive(self.root, [first, first, variant, missing, invalid])

        self.assertEqual(1, archive.import_archive(self.root, self.database)["new_files"])
        self.assertEqual("3", self.database.query("SELECT count(*) FROM rp_history.source_play;"))
        self.assertEqual("5|3|1|1", self.database.query(
            "SELECT source_rows, inserted_rows, duplicate_rows, rejected_rows FROM rp_history.import_file;"))
        stored = json.loads(self.database.query(
            "SELECT source_record FROM rp_history.source_play WHERE first_file_line=1;"))
        self.assertEqual(first, stored)
        self.assertEqual("1", self.database.query("SELECT count(*) FROM rp_history.rejected_row;"))
        self.assertEqual(0, archive.import_archive(self.root, self.database)["new_files"])
        self.assertEqual("3", self.database.query("SELECT count(*) FROM rp_history.source_play;"))

    def test_failed_shard_rolls_back_rows_and_ledger_then_resumes(self):
        entry = write_archive(self.root, [playback()])
        self.database.query(archive.MIGRATION.read_text())
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as stream:
            archive.prepare_shard(self.root, entry, stream)
            stream.seek(0)
            sql = stream.read().replace("\nCOMMIT;", "\nSELECT 1/0;\nCOMMIT;")
        with self.assertRaises(archive.subprocess.CalledProcessError):
            self.database.query(sql)
        self.assertEqual("0|0", self.database.query(
            "SELECT (SELECT count(*) FROM rp_history.source_play), "
            "(SELECT count(*) FROM rp_history.import_file);"))
        self.assertEqual(1, archive.import_archive(self.root, self.database)["new_files"])
        self.assertEqual("1", self.database.query("SELECT count(*) FROM rp_history.source_play;"))

    def test_two_writers_recheck_completion_after_acquiring_the_import_lock(self):
        entry = write_archive(self.root, [playback()])
        self.database.query(archive.MIGRATION.read_text())
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as stream:
            archive.prepare_shard(self.root, entry, stream)
            stream.seek(0)
            sql = stream.read()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as writers:
            futures = [writers.submit(self.database.query, sql) for _ in range(2)]
            for future in futures:
                future.result()
        self.assertEqual("1|1", self.database.query(
            "SELECT (SELECT count(*) FROM rp_history.source_play), "
            "(SELECT count(*) FROM rp_history.import_file);"))


if __name__ == "__main__":
    unittest.main()
