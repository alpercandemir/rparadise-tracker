"""Offline behavior tests for the resumable public playlist archive."""

import copy
import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "rp_archive", Path(__file__).with_name("archive-rp-history.py")
)
archive = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(archive)


def song(event, channel="0", timestamp=1767225600123):
    return {"event": str(event), "chan": int(channel), "song_id": "42",
            "play_time": timestamp, "duration": "240000", "artist": "Björk",
            "title": "Repeated song", "album": "Album", "unknown_field": {"keep": True}}


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.state = archive.initial_channel_state()

    def commit(self, songs, channel="0", state=None):
        state = self.state if state is None else state
        response = {"song": songs, "cover_base_url": "https://img.radioparadise.com/",
                    "extra_top_level": "preserved"}
        result = archive.commit_page(
            self.root, channel, state, response, {"http_status": 200},
            archive.request_url(channel, state["cursor"]), 3,
        )
        if channel == "0":
            self.state = result
        return result

    def read_records(self, kind, channel="0"):
        records = []
        for path in sorted((self.root / f"channel-{channel}" / kind).glob("*.gz")):
            with gzip.open(path, "rt", encoding="utf-8") as stream:
                records.extend(json.loads(line) for line in stream)
        return records

    def test_preserves_unknown_fields_unicode_and_millisecond_times(self):
        original = song(100)
        self.commit([original])
        record = self.read_records("plays")[0]
        self.assertEqual(original, record["data"])
        self.assertEqual("2026-01-01T00:00:00.123Z", record["broadcast_at"])
        self.assertEqual("preserved", self.read_records("raw-pages")[0]["response"]["extra_top_level"])

    def test_keeps_repeated_broadcasts_of_same_song_and_per_channel_event_ids(self):
        self.commit([song(100), song(99)])
        other = self.commit([song(100, "1")], "1", archive.initial_channel_state())
        self.assertEqual(2, self.state["plays"])
        self.assertEqual(1, other["plays"])

    def test_cursor_is_last_event_not_page_length(self):
        self.commit([song(100), song(95)])
        self.assertEqual("95", self.state["cursor"])
        self.assertIn("list_offset=95", archive.request_url("0", self.state["cursor"]))
        self.commit([song(94)])
        self.assertEqual("94", self.state["cursor"])

    def test_rejects_duplicate_cursors_and_preserves_rejected_response(self):
        self.commit([song(100)])
        with self.assertRaisesRegex(ValueError, "strictly backward"):
            self.commit([song(100)])
        self.assertEqual(1, self.state["pages"])
        self.assertEqual(1, len(list((self.root / "rejected").glob("*.json"))))

    def test_preserves_same_event_with_different_broadcast_times(self):
        self.commit([song(100)])
        variants = [song(99, timestamp=1780420600000), song(99, timestamp=1780421360000)]
        self.commit(variants + [song(98)])
        records = self.read_records("plays")
        self.assertEqual(["100", "99", "99", "98"], [row["event_id"] for row in records])
        self.assertEqual(variants, [row["data"] for row in records[1:3]])
        self.assertEqual("98", self.state["cursor"])
        self.assertEqual(1, self.state["repeated_event_rows"])
        archive.verify(SimpleNamespace(output=self.root, channels=["0"]))

    def test_rejects_increasing_events_without_skipping_source_rows(self):
        with self.assertRaisesRegex(ValueError, "increase within"):
            self.commit([song(100), song(101)])
        self.assertEqual(0, self.state["plays"])
        self.assertEqual(1, len(list((self.root / "rejected").glob("*.json"))))

    def test_resumes_older_checkpoint_without_repeated_event_counter(self):
        self.commit([song(100)])
        self.state.pop("repeated_event_rows")
        self.commit([song(99), song(99)])
        self.assertEqual(1, self.state["repeated_event_rows"])

    def test_rejects_mismatched_channel(self):
        with self.assertRaisesRegex(ValueError, "channel"):
            self.commit([song(100, "1")])

    def test_preserves_bad_timestamps_instead_of_fabricating_them(self):
        self.commit([song(100, timestamp="bad")])
        self.assertIsNone(self.read_records("plays")[0]["broadcast_at"])
        self.assertEqual(1, self.state["invalid_timestamps"])
        for value in (None, True, -1, "0", "1.5", 10 ** 30):
            self.assertIsNone(archive.broadcast_time(value))

    def test_requires_three_empty_responses_to_mark_api_boundary(self):
        self.commit([song(100)])
        self.commit([])
        self.commit([])
        self.assertEqual("running", self.state["status"])
        self.commit([])
        self.assertEqual("exhausted", self.state["status"])
        self.assertEqual("100", self.state["cursor"])

    def test_recovers_appends_after_checkpoint_without_duplicate_plays(self):
        self.commit([song(100)])
        checkpoint = copy.deepcopy(self.state)
        self.commit([song(99)])
        archive.recover_streams(self.root, "0", checkpoint)
        self.state = checkpoint
        self.commit([song(99)])
        self.assertEqual(["100", "99"], [row["event_id"] for row in self.read_records("plays")])

    def test_recovers_crash_after_shard_rotation(self):
        with patch.object(archive, "PAGES_PER_SHARD", 1):
            self.commit([song(100)])
            checkpoint = copy.deepcopy(self.state)
            self.commit([song(99)])
            archive.recover_streams(self.root, "0", checkpoint)
            self.state = checkpoint
            self.commit([song(99)])
        self.assertEqual(["100", "99"], [row["event_id"] for row in self.read_records("plays")])

    def test_verification_compares_raw_and_normalized_records(self):
        self.commit([song(100), song(99)])
        args = type("Args", (), {"output": self.root, "channels": ["0"]})()
        archive.verify(args)
        report = json.loads((self.root / "verification.json").read_text())
        self.assertEqual("verified", report["status"])
        path = next((self.root / "channel-0" / "plays").glob("*.gz"))
        rows = self.read_records("plays")
        rows[0]["data"]["artist"] = "Changed metadata"
        path.write_bytes(gzip.compress(b"".join(archive.json_bytes(row) for row in rows)))
        with self.assertRaisesRegex(ValueError, "differ from raw"):
            archive.verify(args)

    def test_refuses_truncated_committed_archive(self):
        self.commit([song(100)])
        path = next((self.root / "channel-0" / "plays").glob("*.gz"))
        path.write_bytes(b"")
        with self.assertRaisesRegex(RuntimeError, "shorter"):
            archive.recover_streams(self.root, "0", self.state)

    def test_honors_retry_after(self):
        self.assertGreaterEqual(archive.retry_delay({"retry-after": "120"}, 1), 120)
        self.assertEqual(4, archive.retry_delay({}, 2))

    def test_retries_transient_http_failure_and_preserves_response_metadata(self):
        attempts = []

        def fake_curl(command, **_kwargs):
            attempts.append(command)
            headers = Path(command[command.index("--dump-header") + 1])
            body = Path(command[command.index("--output") + 1])
            if len(attempts) == 1:
                headers.write_text("HTTP/2 429\nRetry-After: 120\n")
                body.write_bytes(b"Try again later")
                return SimpleNamespace(returncode=0, stdout="429", stderr="")
            headers.write_text("HTTP/2 200\nContent-Type: application/json\n")
            body.write_bytes(archive.json_bytes({"song": [song(100)]}))
            return SimpleNamespace(returncode=0, stdout="200", stderr="")

        with patch.object(archive.subprocess, "run", side_effect=fake_curl):
            with patch.object(archive, "interruptible_sleep") as sleep:
                response, metadata = archive.PublicApi(1).fetch(archive.request_url("0", None))
        self.assertEqual(2, len(attempts))
        self.assertEqual("100", response["song"][0]["event"])
        self.assertEqual(200, metadata["http_status"])
        self.assertEqual(64, len(metadata["response_sha256"]))
        sleep.assert_any_call(120)

    def test_resuming_collection_does_not_reset_playback_identity(self):
        args = SimpleNamespace(output=self.root, channels=["0"], interval=1,
                               max_pages=1, empty_confirmations=3)
        with patch.object(archive.PublicApi, "fetch", return_value=({"song": [song(100)]}, {})):
            archive.collect(args)
        with patch.object(archive.PublicApi, "fetch", return_value=({"song": [song(99)]}, {})) as fetch:
            archive.collect(args)
        self.assertIn("list_offset=100", fetch.call_args.args[0])
        self.assertEqual(["100", "99"], [row["event_id"] for row in self.read_records("plays")])
        manifest = json.loads((self.root / "manifest.json").read_text())
        self.assertEqual(2, manifest["total_plays"])

    def test_running_archive_cannot_be_verified_or_collected_twice(self):
        self.commit([song(100)])
        args = SimpleNamespace(output=self.root, channels=["0"])
        with (self.root / "collector.lock").open("a+") as lock:
            archive.fcntl.flock(lock, archive.fcntl.LOCK_EX | archive.fcntl.LOCK_NB)
            with self.assertRaisesRegex(RuntimeError, "Pause the collector"):
                archive.verify(args)
            with self.assertRaisesRegex(RuntimeError, "Another collector"):
                archive.collect(args)


if __name__ == "__main__":
    unittest.main()
