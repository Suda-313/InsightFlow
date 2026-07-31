#!/usr/bin/env python3
"""Dump cross-dev-slice per-case retrieval diagnostics for Phase 4C triage."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def load_latest_summary(run_dir: Path) -> dict | None:
    files = sorted(run_dir.glob("rag-gold-run-*.json"), key=lambda p: p.stat().st_mtime)
    if not files:
        return None
    with files[-1].open(encoding="utf-8") as f:
        return json.load(f)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", required=True, help="Directory containing rag-gold-run-*.json")
    args = parser.parse_args()
    summary = load_latest_summary(Path(args.run_dir))
    if summary is None:
        print("No summary found")
        return 1

    ext = summary.get("metrics", {}).get("extended") or {}
    print(f"run={summary.get('runPublicId')} version={summary.get('retrievalVersion')}")
    print(f"chunk@8={ext.get('chunkRecallAt8')} checksum={ext.get('checksum')}\n")

    for case in summary.get("caseResults") or []:
        rd = case.get("retrievalDiagnostics") or {}
        groups = rd.get("requirementGroups") or []
        ginfo = "; ".join(
            f"{g.get('groupKey')}: rrf={g.get('rrfFirstRank')} "
            f"final={g.get('finalFirstRank')} ok={g.get('satisfiedAt8')}"
            for g in groups
        )
        print(
            f"{case.get('caseKey')} hit={case.get('chunkHitAt8')} "
            f"cand50={rd.get('candidateHitAt50')} goldRrf={rd.get('goldChunkRrfRank')} | {ginfo}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
