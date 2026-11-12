#!/usr/bin/env python3
"""Archive public RP playlists as resumable, lossless gzip JSON Lines files."""

import argparse
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urlencode


BASE_URL = "https://api.radioparadise.com/api/"
CHANNELS = {"0": "Main", "1": "Mellow", "2": "Rock"}
SCHEMA_VERSION = 1
PAGES_PER_SHARD = 500
STOP_REQUESTED = False


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode()


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("wb") as stream:
        stream.write((json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode())
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    # Persist the rename as well as its contents before advancing a checkpoint.
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def request_url(channel, cursor):
    if cursor is None:
        return BASE_URL + "nowplaying_list_v2022?" + urlencode(
            {"mode": "wip-channel", "chan": channel}
        )
    return BASE_URL + "nowplaying_list_more_v2022?" + urlencode(
        {"chan": channel, "list_offset": cursor}
    )


def retry_delay(headers, attempt):
    value = headers.get("retry-after", "")
    if value.isdigit():
        return max(float(value), min(300, 2 ** attempt))
    if value:
        try:
            seconds = (parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds()
            return max(seconds, min(300, 2 ** attempt))
        except (ValueError, TypeError):
            pass
    return min(300, 2 ** attempt)


def interruptible_sleep(seconds):
    deadline = time.monotonic() + seconds
    while not STOP_REQUESTED and time.monotonic() < deadline:
        time.sleep(min(0.5, max(0, deadline - time.monotonic())))


class PublicApi:
    def __init__(self, interval, attempts=8):
        self.interval = interval
        self.attempts = attempts
        self.last_request = 0

    def fetch(self, url):
        for attempt in range(1, self.attempts + 1):
            interruptible_sleep(max(0, self.last_request + self.interval - time.monotonic()))
            if STOP_REQUESTED:
                raise InterruptedError("Stop requested")
            self.last_request = time.monotonic()
            with tempfile.TemporaryDirectory(prefix="rp-archive-http-") as temporary:
                headers_path = Path(temporary) / "headers"
                body_path = Path(temporary) / "body"
                result = subprocess.run(
                    ["curl", "--silent", "--show-error", "--location", "--max-time", "60",
                     "--connect-timeout", "10", "--user-agent", "RPHistoryArchive/1.0",
                     "--dump-header", str(headers_path), "--output", str(body_path),
                     "--write-out", "%{http_code}", url],
                    capture_output=True, text=True, timeout=70,
                )
                headers = {}
                if headers_path.exists():
                    for line in headers_path.read_text(errors="replace").splitlines():
                        if ":" in line:
                            key, value = line.split(":", 1)
                            headers[key.lower()] = value.strip()
                status = int(result.stdout) if result.stdout.isdigit() else 0
                body = body_path.read_bytes() if body_path.exists() else b""
            if result.returncode == 0 and status == 200:
                try:
                    response = json.loads(body)
                    if not isinstance(response, dict) or not isinstance(response.get("song"), list):
                        raise ValueError("Expected an object with a song array")
                    return response, {
                        "http_status": status,
                        "response_sha256": hashlib.sha256(body).hexdigest(),
                        "headers": {key: headers[key] for key in
                                    ("date", "content-type", "etag", "last-modified")
                                    if key in headers},
                    }
                except (ValueError, UnicodeError) as error:
                    reason = str(error)
            else:
                reason = f"HTTP {status}; curl {result.returncode}: {result.stderr.strip()}"
                if status and status not in (408, 425, 429) and status < 500:
                    raise RuntimeError(f"Request rejected: {reason}; {url}")
            if attempt == self.attempts:
                raise RuntimeError(f"Request failed after {attempt} attempts: {reason}; {url}")
            delay = retry_delay(headers, attempt)
            print(f"{utc_now()} retry {attempt}: {reason}; waiting {delay:.0f}s", flush=True)
            interruptible_sleep(delay)
        raise AssertionError("Unreachable")


def initial_channel_state():
    return {
        "cursor": None, "pages": 0, "plays": 0, "shard": 0, "shard_pages": 0,
        "raw_offset": 0, "plays_offset": 0, "empty_pages": 0,
        "earliest_broadcast_at": None, "latest_broadcast_at": None,
        "invalid_timestamps": 0, "repeated_event_rows": 0, "status": "pending",
    }


def shard_path(root, channel, kind, shard):
    return root / f"channel-{channel}" / kind / f"{shard:06d}.jsonl.gz"


def recover_streams(root, channel, state):
    for kind, offset_key in (("raw-pages", "raw_offset"), ("plays", "plays_offset")):
        directory = root / f"channel-{channel}" / kind
        directory.mkdir(parents=True, exist_ok=True)
        for path in directory.glob("*.jsonl.gz"):
            number = int(path.name.split(".")[0])
            offset = state[offset_key] if number == state["shard"] else 0
            if number < state["shard"]:
                continue
            if path.stat().st_size < offset:
                raise RuntimeError(f"Archive is shorter than its checkpoint: {path}")
            with path.open("r+b") as stream:
                stream.truncate(offset)
        current = shard_path(root, channel, kind, state["shard"])
        if state[offset_key] and not current.exists():
            raise RuntimeError(f"Checkpoint references a missing file: {current}")


def append_gzip(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("ab") as stream:
        stream.write(gzip.compress(contents, compresslevel=6, mtime=0))
        stream.flush()
        os.fsync(stream.fileno())
        return stream.tell()


def broadcast_time(value):
    if isinstance(value, bool):
        return None
    try:
        milliseconds = int(value)
        if milliseconds <= 0 or str(milliseconds) != str(value):
            return None
        return datetime.fromtimestamp(milliseconds / 1000, timezone.utc).isoformat(
            timespec="milliseconds"
        ).replace("+00:00", "Z")
    except (ValueError, TypeError, OverflowError, OSError):
        return None


def normalize_page(response, channel, cursor, url, fetched_at):
    if "chan" in response and str(response["chan"]) != channel:
        raise ValueError("Response channel differs from requested channel")
    records = []
    previous_event = None
    requested_cursor = int(cursor) if cursor is not None else None
    for song in response["song"]:
        if not isinstance(song, dict):
            raise ValueError("Song row is not an object")
        if str(song.get("chan")) != channel:
            raise ValueError("Song channel differs from requested channel")
        event = str(song.get("event", ""))
        if not event.isdigit() or int(event) <= 0:
            raise ValueError("Missing or invalid event cursor")
        if requested_cursor is not None and int(event) >= requested_cursor:
            raise ValueError("Events do not advance strictly backward from the requested cursor")
        if previous_event is not None and int(event) > previous_event:
            raise ValueError("Events increase within the returned page")
        # The API can return the same event twice with different play_time values.
        # Preserve both source rows; the cursor still advances to the oldest event.
        previous_event = int(event)
        records.append({
            "schema_version": SCHEMA_VERSION, "channel": channel,
            "event_id": event, "song_id": str(song.get("song_id", "")),
            "broadcast_at": broadcast_time(song.get("play_time")),
            "fetched_at": fetched_at, "source_url": url, "data": song,
        })
    return records


def commit_page(root, channel, state, response, metadata, url, empty_confirmations):
    fetched_at = utc_now()
    try:
        records = normalize_page(response, channel, state["cursor"], url, fetched_at)
    except ValueError:
        error_path = root / "rejected" / f"channel-{channel}-{time.time_ns()}.json"
        atomic_json(error_path, {"source_url": url, "fetched_at": fetched_at,
                                "response": response, **metadata})
        raise
    if state["shard_pages"] >= PAGES_PER_SHARD:
        state = dict(state, shard=state["shard"] + 1, shard_pages=0,
                     raw_offset=0, plays_offset=0)
    envelope = {
        "schema_version": SCHEMA_VERSION, "channel": channel,
        "requested_cursor": state["cursor"], "source_url": url,
        "fetched_at": fetched_at, "response": response, **metadata,
    }
    state = dict(state)
    state["raw_offset"] = append_gzip(
        shard_path(root, channel, "raw-pages", state["shard"]), json_bytes(envelope)
    )
    state["plays_offset"] = append_gzip(
        shard_path(root, channel, "plays", state["shard"]),
        b"".join(json_bytes(record) for record in records),
    )
    times = [record["broadcast_at"] for record in records if record["broadcast_at"]]
    if times:
        state["earliest_broadcast_at"] = min(times + [state["earliest_broadcast_at"] or min(times)])
        state["latest_broadcast_at"] = max(times + [state["latest_broadcast_at"] or max(times)])
    state["pages"] += 1
    state["shard_pages"] += 1
    state["plays"] += len(records)
    state["repeated_event_rows"] = state.get("repeated_event_rows", 0) + (
        len(records) - len({record["event_id"] for record in records})
    )
    state["invalid_timestamps"] += sum(record["broadcast_at"] is None for record in records)
    state["empty_pages"] = 0 if records else state["empty_pages"] + 1
    if records:
        state["cursor"] = records[-1]["event_id"]
    state["status"] = "exhausted" if state["empty_pages"] >= empty_confirmations else "running"
    state["updated_at"] = fetched_at
    atomic_json(root / f"channel-{channel}" / "checkpoint.json", state)
    return state


def write_manifest(root, states, status, started_at, error=None):
    manifest = {
        "schema_version": SCHEMA_VERSION, "started_at": started_at,
        "updated_at": utc_now(), "status": status,
        "source": "Radio Paradise public channel broadcast history",
        "scope": "All history exposed by backward pagination for the selected channels",
        "format": "UTF-8 JSON Lines in concatenated gzip members; one play or API page per line",
        "identity": ["channel", "event_id", "broadcast_at"],
        "identity_note": "Upstream event IDs can repeat with different playback times. All source rows are preserved; total_plays is a row count, not a deduplicated event count.",
        "timestamp_unit": "Unix milliseconds in data.play_time",
        "coverage_note": "Exhausted means repeated empty pages at the observed API boundary; it does not prove a complete station archive.",
        "channels": {channel: {"name": CHANNELS[channel], **state}
                     for channel, state in states.items()},
        "total_plays": sum(state["plays"] for state in states.values()),
        "total_pages": sum(state["pages"] for state in states.values()),
    }
    if error:
        manifest["error"] = error
    atomic_json(root / "manifest.json", manifest)


def request_stop(_signum, _frame):
    global STOP_REQUESTED
    STOP_REQUESTED = True


def collect(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    with (root / "collector.lock").open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("Another collector owns this archive") from error
        manifest_path = root / "manifest.json"
        previous = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
        if previous and set(previous["channels"]) != set(args.channels):
            raise ValueError("Resume with the same channels or use a different output directory")
        atomic_json(root / "process.json", {"pid": os.getpid(), "started_at": utc_now()})
        states = {}
        for channel in args.channels:
            checkpoint = root / f"channel-{channel}" / "checkpoint.json"
            state = json.loads(checkpoint.read_text()) if checkpoint.exists() else initial_channel_state()
            recover_streams(root, channel, state)
            states[channel] = state
        started_at = previous.get("started_at", utc_now())
        api = PublicApi(args.interval)
        count = 0
        status = "running"
        write_manifest(root, states, status, started_at)
        try:
            while not STOP_REQUESTED:
                active = [channel for channel, state in states.items() if state["status"] != "exhausted"]
                if not active:
                    status = "exhausted"
                    break
                for channel in active:
                    if STOP_REQUESTED or (args.max_pages and count >= args.max_pages):
                        break
                    state = states[channel]
                    url = request_url(channel, state["cursor"])
                    response, metadata = api.fetch(url)
                    states[channel] = commit_page(
                        root, channel, state, response, metadata, url, args.empty_confirmations
                    )
                    count += 1
                    write_manifest(root, states, status, started_at)
                    if count <= 3 or count % 100 == 0 or states[channel]["status"] == "exhausted":
                        print(f"{utc_now()} channel={channel} pages={states[channel]['pages']} "
                              f"plays={states[channel]['plays']} "
                              f"earliest={states[channel]['earliest_broadcast_at']} "
                              f"status={states[channel]['status']}", flush=True)
                if args.max_pages and count >= args.max_pages:
                    status = "paused"
                    break
            if STOP_REQUESTED:
                status = "paused"
        except InterruptedError:
            status = "paused"
        except Exception as error:
            write_manifest(root, states, "failed", started_at, str(error))
            raise
        write_manifest(root, states, status, started_at)
        print(f"{utc_now()} {status}: {sum(state['plays'] for state in states.values())} plays", flush=True)
        if status == "exhausted":
            try:
                verify_locked(args, root)
            except Exception as error:
                write_manifest(root, states, "verification_failed", started_at, str(error))
                raise


def verify(args):
    root = args.output.resolve()
    with (root / "collector.lock").open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("Pause the collector before verifying a stable archive") from error
        verify_locked(args, root)


def verify_locked(args, root):
    report = {"checked_at": utc_now(), "channels": {}, "files": []}
    for channel in args.channels:
        checkpoint = json.loads((root / f"channel-{channel}" / "checkpoint.json").read_text())
        pages, plays, invalid_times, repeated_events = 0, 0, 0, 0
        previous_event = None
        expected_records = hashlib.sha256()
        actual_records = hashlib.sha256()
        raw_cursor = None
        earliest, latest = None, None
        for kind in ("raw-pages", "plays"):
            rows = 0
            for path in sorted((root / f"channel-{channel}" / kind).glob("*.jsonl.gz")):
                if path.stat().st_size == 0:
                    continue
                with path.open("rb") as compressed:
                    digest = hashlib.file_digest(compressed, "sha256").hexdigest()
                with gzip.open(path, "rt", encoding="utf-8") as stream:
                    for line in stream:
                        record = json.loads(line)
                        rows += 1
                        if kind == "raw-pages":
                            if record["requested_cursor"] != raw_cursor:
                                raise ValueError(f"Discontinuous page cursors in {path}")
                            normalized = normalize_page(
                                record["response"], channel, record["requested_cursor"],
                                record["source_url"], record["fetched_at"],
                            )
                            plays += len(normalized)
                            for playback in normalized:
                                expected_records.update(json_bytes(playback))
                            if normalized:
                                raw_cursor = normalized[-1]["event_id"]
                            continue
                        actual_records.update(json_bytes(record))
                        if record["channel"] != channel:
                            raise ValueError(f"Incorrect channel in {path}")
                        event = int(record["event_id"])
                        if previous_event is not None and event > previous_event:
                            raise ValueError(f"Increasing event in {path}")
                        if event == previous_event:
                            repeated_events += 1
                        previous_event = event
                        timestamp = broadcast_time(record["data"].get("play_time"))
                        if timestamp != record["broadcast_at"]:
                            raise ValueError(f"Incorrect normalized timestamp in {path}")
                        if timestamp:
                            earliest = min(earliest or timestamp, timestamp)
                            latest = max(latest or timestamp, timestamp)
                        else:
                            invalid_times += 1
                report["files"].append({"path": str(path.relative_to(root)),
                                        "bytes": path.stat().st_size, "sha256": digest})
            if kind == "raw-pages":
                pages = rows
            elif rows != checkpoint["plays"]:
                raise ValueError(f"Play count differs from checkpoint for channel {channel}")
        if pages != checkpoint["pages"] or plays != checkpoint["plays"]:
            raise ValueError(f"Raw page count differs from checkpoint for channel {channel}")
        if expected_records.digest() != actual_records.digest():
            raise ValueError(f"Normalized records differ from raw responses for channel {channel}")
        if raw_cursor != checkpoint["cursor"]:
            raise ValueError(f"Final cursor differs from checkpoint for channel {channel}")
        if earliest != checkpoint["earliest_broadcast_at"] or latest != checkpoint["latest_broadcast_at"]:
            raise ValueError(f"Date range differs from checkpoint for channel {channel}")
        if repeated_events != checkpoint.get("repeated_event_rows", 0):
            raise ValueError(f"Repeated event count differs from checkpoint for channel {channel}")
        report["channels"][channel] = {"plays": plays, "pages": pages,
                                      "earliest_broadcast_at": earliest,
                                      "latest_broadcast_at": latest,
                                      "invalid_timestamps": invalid_times,
                                      "repeated_event_rows": repeated_events}
    report["status"] = "verified"
    atomic_json(root / "verification.json", report)
    print(json.dumps({key: value for key, value in report.items() if key != "files"}, indent=2))


def detach(args):
    args.output.mkdir(parents=True, exist_ok=True)
    command = [sys.executable, "-u", str(Path(__file__).resolve())]
    command += [argument for argument in sys.argv[1:] if argument != "--detach"]
    with (args.output / "download.log").open("ab") as log:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    print(f"Started collector PID {process.pid}; log: {args.output.resolve() / 'download.log'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("collect", "verify"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--channels", default="0,1,2")
    parser.add_argument("--interval", type=float, default=1.0,
                        help="Minimum global request-start interval in seconds (at least 1)")
    parser.add_argument("--max-pages", type=int, default=0, help="Stop after this many new pages; 0 is unlimited")
    parser.add_argument("--empty-confirmations", type=int, default=3)
    parser.add_argument("--detach", action="store_true", help="Run collector independently and log to the archive directory")
    args = parser.parse_args()
    args.channels = args.channels.split(",")
    if (not args.channels or len(set(args.channels)) != len(args.channels)
            or any(channel not in CHANNELS for channel in args.channels)):
        parser.error("Channels must be a unique subset of 0,1,2")
    if args.interval < 1 or args.max_pages < 0 or args.empty_confirmations < 3:
        parser.error("Use interval >= 1, max-pages >= 0, and empty-confirmations >= 3")
    if args.detach:
        if args.command != "collect":
            parser.error("Only collection can be detached")
        detach(args)
        return
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    if args.command == "collect":
        collect(args)
    else:
        verify(args)


if __name__ == "__main__":
    main()
