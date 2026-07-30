#!/usr/bin/env python3
"""Fix seed evidence chunk_no after corpus republish removed trailing anchor chunks."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "evaluation" / "rag" / "gold" / "corpus-manifest.json"
SEED_PATHS = [
    ROOT / "evaluation" / "rag" / "gold" / "seeds" / "ops-rag-v1-dev-240.json",
    ROOT / "evaluation" / "rag" / "gold" / "seeds" / "ops-rag-v1-val-80.json",
    ROOT / "evaluation" / "rag" / "gold" / "seeds" / "ops-rag-v1-frozen-80.json",
]


def main() -> int:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    index: dict[tuple[str, int], dict[int, dict]] = {}
    for doc in manifest["documents"]:
        ref = doc["document_ref"]
        for ver in doc["versions"]:
            if ver.get("status") != "PUBLISHED":
                continue
            index[(ref, ver["version_no"])] = {c["chunk_no"]: c for c in ver["chunks"]}

    fixes: list[tuple[str, str, str, int, int]] = []
    for seed_path in SEED_PATHS:
        seed = json.loads(seed_path.read_text(encoding="utf-8"))
        changed = False
        for case in seed["cases"]:
            for ev in case.get("evidences", []):
                cn = ev.get("chunk_no")
                if cn is None:
                    continue
                key = (ev["document_ref"], ev["version_no"])
                chunks = index.get(key)
                if chunks is None:
                    raise SystemExit(f"missing version: {case['case_key']} {key}")
                if cn in chunks:
                    continue
                max_cn = max(chunks)
                if cn != max_cn + 1:
                    raise SystemExit(
                        f"unhandled chunk ref: {case['case_key']} {key} chunk_no={cn} max={max_cn}"
                    )
                fixes.append((seed_path.name, case["case_key"], ev["document_ref"], cn, max_cn))
                ev["chunk_no"] = max_cn
                changed = True
        if changed:
            seed_path.write_text(json.dumps(seed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"fixed {len(fixes)} evidence refs")
    for item in fixes:
        print(item)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
