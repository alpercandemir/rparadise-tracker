#!/usr/bin/env python3
"""Import verified RP archive shards without changing tracker observations or counters.

Uses the PostgreSQL container's psql; no Python packages or host PostgreSQL tools
are required. A shard is one transaction. Interrupted shards roll back and retry.
"""

import argparse
import fcntl
import gzip
import hashlib
import json
import subprocess
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path


MIGRATION = Path(__file__).with_name("migrations") / "2026-11-12-add-rp-history-archive.sql"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False)


def sql_literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def copy_value(value):
    # PostgreSQL COPY text escaping is separate from JSON and SQL string escaping.
    return str(value).replace("\\", "\\\\").replace("\t", "\\t").replace(
        "\n", "\\n").replace("\r", "\\r")


def write_copy_row(stream, values):
    stream.write("\t".join(copy_value(value) for value in values) + "\n")


def source_play(record, channel):
    if record.get("schema_version") != 1 or str(record.get("channel")) != channel:
        raise ValueError("Unsupported schema version or mismatched channel")
    data = record.get("data")
    if not isinstance(data, dict):
        raise ValueError("Missing upstream song object")
    event_id, song_id = str(record.get("event_id", "")), str(record.get("song_id", ""))
    if not event_id.isascii() or not event_id.isdigit() or int(event_id) <= 0:
        raise ValueError("Invalid source event ID")
    if (str(data.get("event")) != event_id or str(data.get("song_id")) != song_id
            or str(data.get("chan")) != channel):
        raise ValueError("Normalized identity differs from upstream identity")
    try:
        milliseconds = data["play_time"]
        if isinstance(milliseconds, bool) or str(int(milliseconds)) != str(milliseconds):
            raise ValueError("Non-integer upstream timestamp")
        expected = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(
            milliseconds=int(milliseconds))
        broadcast = datetime.fromisoformat(record["broadcast_at"].replace("Z", "+00:00"))
        if broadcast.tzinfo is None or broadcast != expected:
            raise ValueError("Broadcast timestamp differs from upstream timestamp")
    except (KeyError, TypeError, OverflowError, ValueError, AttributeError) as error:
        raise ValueError("Invalid or inconsistent broadcast timestamp") from error
    if not song_id.isascii() or not song_id.isdigit():
        raise ValueError("Invalid RP song ID")
    issues = [f"missing_{field}" for field in ("artist", "title", "album")
              if not isinstance(data.get(field), str) or not data[field].strip()]
    if int(song_id) == 0:
        issues.append("nonpositive_song_id")
    # Fetch time and pagination URL are provenance, not source event identity.
    identity = [channel, event_id, song_id, expected.isoformat(), data]
    source_key = hashlib.sha256(canonical(identity).encode("utf-8")).hexdigest()
    return source_key, issues


class Database:
    def __init__(self, container, user, database):
        self.command = ["docker", "exec", "-i", container, "psql", "-X", "-qAt",
                        "-v", "ON_ERROR_STOP=1", "-U", user, "-d", database]

    def query(self, sql):
        return subprocess.run(self.command, input=sql, text=True, check=True,
                              stdout=subprocess.PIPE).stdout.strip()

    def execute_file(self, stream):
        stream.seek(0)
        subprocess.run(self.command, stdin=stream, check=True)


def verified_files(root):
    verification = json.loads((root / "verification.json").read_text())
    manifest = json.loads((root / "manifest.json").read_text())
    if verification.get("status") != "verified" or manifest.get("status") != "exhausted":
        raise ValueError("Import requires a verified, stopped, exhausted archive")
    for channel, state in manifest["channels"].items():
        if verification["channels"].get(channel, {}).get("plays") != state["plays"]:
            raise ValueError("Verification does not cover the current manifest")
    files = []
    for entry in verification["files"]:
        relative = Path(entry["path"])
        if len(relative.parts) != 3 or relative.parts[1] != "plays":
            continue
        if (relative.parts[0] not in ("channel-0", "channel-1", "channel-2")
                or relative.suffixes != [".jsonl", ".gz"]):
            raise ValueError(f"Invalid archive shard path: {relative}")
        path = root / relative
        if not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Archive path escapes its root")
        files.append(entry)
    expected_paths = {str(path.relative_to(root)) for path in root.glob("channel-*/plays/*.jsonl.gz")
                      if path.stat().st_size > 0}
    if len(files) != len(expected_paths) or {entry["path"] for entry in files} != expected_paths:
        raise ValueError("Verification must cover every playback shard exactly once")
    # Validate every compressed shard before any database writes.
    for entry in files:
        path = root / entry["path"]
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if path.stat().st_size != entry["bytes"] or digest != entry["sha256"]:
            raise ValueError(f"Archive checksum mismatch: {entry['path']}")
    return sorted(files, key=lambda entry: entry["path"])


def prepare_shard(root, entry, stream):
    channel = Path(entry["path"]).parts[0].removeprefix("channel-")
    source_rows, rejected = 0, []
    stream.write("SET standard_conforming_strings = on;\nBEGIN;\n")
    # Serialize import writers. A second importer rechecks the ledger after acquiring the lock.
    stream.write("SELECT pg_advisory_xact_lock(127934, 1);\n")
    digest = sql_literal(entry["sha256"])
    stream.write(f"SELECT EXISTS (SELECT 1 FROM rp_history.import_file WHERE file_sha256={digest}) AS done \\gset\n")
    # Quit before COPY when a competing writer already committed this shard.
    # psql conditionals cannot skip inline COPY data safely.
    stream.write("\\if :done\nROLLBACK;\n\\quit\n\\endif\n")
    stream.write("CREATE TEMP TABLE stage_play (source_key text, file_line integer, "
                 "source_record jsonb, metadata_issues jsonb) ON COMMIT DROP;\n")
    stream.write("COPY stage_play FROM STDIN;\n")
    with gzip.open(root / entry["path"], "rt", encoding="utf-8") as rows:
        for line_number, line in enumerate(rows, 1):
            source_rows += 1
            try:
                record = json.loads(line)
                if not isinstance(record, dict):
                    raise ValueError("Playback row must be an object")
                key, issues = source_play(record, channel)
                record_json = canonical(record)
                # PostgreSQL JSONB/text cannot represent NUL characters.
                if "\\u0000" in record_json:
                    raise ValueError("Row contains a PostgreSQL-incompatible NUL character")
            except ValueError as error:
                rejected.append((line_number, line.rstrip("\n"), str(error)))
                continue
            write_copy_row(stream, [key, line_number, record_json, canonical(issues)])
    stream.write("\\.\n")
    stream.write("CREATE TEMP TABLE stage_rejected (file_line integer, raw_line text, reason text) ON COMMIT DROP;\n")
    stream.write("COPY stage_rejected FROM STDIN;\n")
    for row in rejected:
        write_copy_row(stream, row)
    stream.write("\\.\n")
    stream.write(f"""
WITH inserted AS (
    INSERT INTO rp_history.source_play
        (source_key, channel, event_id, rp_song_id, broadcast_at, source_record,
         metadata_issues, first_file_sha256, first_file_line)
    SELECT source_key, source_record->>'channel', source_record->>'event_id',
           source_record->>'song_id', (source_record->>'broadcast_at')::timestamptz,
           source_record, metadata_issues, {digest}, file_line
    FROM stage_play ORDER BY file_line
    ON CONFLICT (source_key) DO NOTHING
    RETURNING 1
)
INSERT INTO rp_history.import_file
    (file_sha256, relative_path, source_rows, inserted_rows, duplicate_rows, rejected_rows)
SELECT {digest}, {sql_literal(entry['path'])}, {source_rows}, count(*),
       {source_rows - len(rejected)} - count(*), {len(rejected)} FROM inserted;
INSERT INTO rp_history.rejected_row (file_sha256, file_line, raw_line, reason)
SELECT {digest}, file_line, raw_line, reason FROM stage_rejected;
COMMIT;
""")


def import_archive(root, database, max_files=0):
    with (root / "collector.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        files = verified_files(root)
        database.query(MIGRATION.read_text())
        completed = set(database.query("SELECT file_sha256 FROM rp_history.import_file;").splitlines())
        imported = 0
        for entry in files:
            if entry["sha256"] in completed:
                continue
            with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as stream:
                prepare_shard(root, entry, stream)
                database.execute_file(stream)
            imported += 1
            print(f"Committed {entry['path']} ({imported} new files)", flush=True)
            if max_files and imported >= max_files:
                break
    return {"verified_files": len(files), "previously_completed_files": len(
        [entry for entry in files if entry["sha256"] in completed]), "new_files": imported}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--container", default="rparadise-history-rehearsal")
    parser.add_argument("--database", default="history_rehearsal")
    parser.add_argument("--user", default="history_rehearsal")
    parser.add_argument("--max-files", type=int, default=0, help="Pilot limit; 0 imports all files")
    args = parser.parse_args()
    if args.max_files < 0:
        parser.error("--max-files must not be negative")
    database = Database(args.container, args.user, args.database)
    print(json.dumps(import_archive(args.archive.resolve(), database, args.max_files), indent=2))


if __name__ == "__main__":
    main()
