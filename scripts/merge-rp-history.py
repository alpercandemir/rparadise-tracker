#!/usr/bin/env python3
"""Merge staged RP history into the real tables of the offline local rehearsal DB."""

import argparse
import importlib.util
import json
import subprocess
from pathlib import Path


spec = importlib.util.spec_from_file_location("rp_import", Path(__file__).with_name("import-rp-history.py"))
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)
MERGE_SQL = Path(__file__).with_suffix(".sql")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="rparadise-history-rehearsal")
    parser.add_argument("--report", type=Path,
                        default=Path("data/rp-history/all-available/local-real-table-merge.json"))
    args = parser.parse_args()
    label = subprocess.run(
        ["docker", "inspect", "--format", '{{index .Config.Labels "com.rparadise.purpose"}}', args.container],
        check=True, text=True, stdout=subprocess.PIPE).stdout.strip()
    if label != "history-rehearsal":
        parser.error("This runner only targets the labeled local history rehearsal container")
    database = archive.Database(args.container, "history_rehearsal", "history_rehearsal")
    if database.query("SELECT current_database();") != "history_rehearsal":
        parser.error("Unexpected local database")
    clients = database.query("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                             "AND pid <> pg_backend_pid() AND backend_type='client backend';")
    if clients != "0":
        parser.error("Stop the local backend and close other database connections before merging")
    result = json.loads(database.query(MERGE_SQL.read_text()))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
