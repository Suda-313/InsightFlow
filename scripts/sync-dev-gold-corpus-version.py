#!/usr/bin/env python3
"""Bump dev-240 seed evidence version_no to match published corpus manifest."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "evaluation" / "rag" / "gold" / "corpus-manifest.json"
DEFAULT_SEED = ROOT / "evaluation" / "rag" / "gold" / "seeds" / "ops-rag-v1-dev-240.json"


def published_version_no(manifest: dict, document_ref: str) -> int:
    for doc in manifest["documents"]:
        if doc["document_ref"] != document_ref:
            continue
        for ver in doc["versions"]:
            if ver.get("status") == "PUBLISHED":
                return ver["version_no"]
    raise KeyError(document_ref)


def chunk_exists(manifest: dict, document_ref: str, version_no: int, chunk_no: int) -> bool:
    for doc in manifest["documents"]:
        if doc["document_ref"] != document_ref:
            continue
        for ver in doc["versions"]:
            if ver.get("version_no") != version_no:
                continue
            return any(c["chunk_no"] == chunk_no for c in ver.get("chunks", []))
    return False


def main() -> int:
    seed_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SEED
    if not seed_path.is_absolute():
        seed_path = ROOT / seed_path
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    seed = json.loads(seed_path.read_text(encoding="utf-8"))
    updates = 0
    for case in seed["cases"]:
        for ev in case.get("evidences", []):
            if ev.get("granularity") != "CHUNK" and ev.get("chunk_no") is None:
                continue
            if "document_ref" not in ev:
                continue
            target = published_version_no(manifest, ev["document_ref"])
            old = ev.get("version_no")
            if old != target:
                ev["version_no"] = target
                updates += 1
            chunk_no = ev.get("chunk_no")
            if chunk_no is not None and not chunk_exists(
                manifest, ev["document_ref"], target, chunk_no
            ):
                print(
                    f"ERROR missing chunk: {case['case_key']} "
                    f"{ev['document_ref']} v{target} chunk_no={chunk_no}",
                    file=sys.stderr,
                )
                return 1
    seed_path.write_text(json.dumps(seed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"updated {updates} evidence version_no fields in {seed_path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
