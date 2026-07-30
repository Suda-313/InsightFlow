#!/usr/bin/env python3
"""从 Postgres 导出 RAG 金标 seed 用的语料 manifest（UTF-8 JSON）。"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL_FILE = ROOT / "scripts" / "export-corpus-manifest.sql"
OUT_FILE = ROOT / "evaluation" / "rag" / "gold" / "corpus-manifest.json"
CONTAINER = "yuqiagent-postgres-1"


def main() -> int:
    sql = SQL_FILE.read_text(encoding="utf-8")
    proc = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "insightflow", "-d", "insightflow", "-t", "-A"],
        input=sql.encode("utf-8"),
        capture_output=True,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr.decode("utf-8", errors="replace"))
        return proc.returncode

    raw = proc.stdout.decode("utf-8").strip()
    if not raw:
        sys.stderr.write("empty query result\n")
        return 1

    data = json.loads(raw)
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with OUT_FILE.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    stats = data.get("statistics", {})
    print(f"wrote {OUT_FILE}")
    print(
        "documents={document_count} versions={published_version_count} chunks={chunk_count}".format(
            **stats
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
