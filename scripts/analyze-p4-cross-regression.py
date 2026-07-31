#!/usr/bin/env python3
"""Compare P3 vs P4 cross-dev-slice misses and check v3/v4 chunk drift for gold evidence."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "evaluation" / "rag" / "gold" / "corpus-manifest.json"
SEED = ROOT / "evaluation" / "rag" / "gold" / "seeds" / "ops-rag-v1-dev-240.json"
P3_RUN = ROOT / "output/rag-gold-runs/cross-dev-slice-p3-coverage/rag-gold-run-1f18c030-91c8-698a-9ec8-bb6a2ad40ea4.json"
P4_RUN = ROOT / "output/rag-gold-runs/cross-dev-slice-p4-v4gold/rag-gold-run-1f18c141-0c75-66c4-be79-a1f3f8bbd65f.json"
MISS_CASES = ["dev-146", "dev-147", "dev-154"]


def build_index(manifest: dict, version_no: int) -> dict:
    idx: dict[tuple[str, int, int], dict] = {}
    for doc in manifest["documents"]:
        ref = doc["document_ref"]
        for ver in doc["versions"]:
            if ver.get("version_no") != version_no:
                continue
            for chunk in ver.get("chunks", []):
                idx[(ref, version_no, chunk["chunk_no"])] = chunk
    return idx


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    seed = json.loads(SEED.read_text(encoding="utf-8"))
    v3 = build_index(manifest, 3)
    v4 = build_index(manifest, 4)
    case_map = {c["case_key"]: c for c in seed["cases"]}

    print("=== Gold evidence chunk_no stability (v3 vs v4 same chunk_no) ===")
    for case_key in MISS_CASES:
        case = case_map[case_key]
        print(f"\n--- {case_key} ---")
        seen: set[tuple[str, int]] = set()
        for ev in case["evidences"]:
            if ev.get("granularity") != "CHUNK":
                continue
            dedupe = (ev["document_ref"], ev["chunk_no"])
            if dedupe in seen:
                continue
            seen.add(dedupe)
            c3 = v3.get((ev["document_ref"], 3, ev["chunk_no"]))
            c4 = v4.get((ev["document_ref"], 4, ev["chunk_no"]))
            if c4 is None:
                print(f"  MISSING v4 chunk: {ev['document_ref']} chunk_no={ev['chunk_no']}")
                continue
            same_prefix = (
                c3 is not None
                and (c3.get("content_preview") or "")[:120]
                == (c4.get("content_preview") or "")[:120]
            )
            print(
                f"  {ev['document_ref']} chunk={ev['chunk_no']} req={ev.get('requirement_key')}"
            )
            print(f"    v3 heading: {c3.get('section_heading') if c3 else None!r}")
            print(f"    v4 heading: {c4.get('section_heading')!r}")
            print(f"    content prefix same: {same_prefix}")
            print(f"    v4 uuid: {c4.get('chunk_public_id', '?')}")
            preview = (c4.get("content_preview") or "")[:100]
            print(f"    v4 preview: {preview}...")

    p3 = json.loads(P3_RUN.read_text(encoding="utf-8"))
    p4 = json.loads(P4_RUN.read_text(encoding="utf-8"))
    p3m = {c["caseKey"]: c for c in p3["caseResults"]}
    p4m = {c["caseKey"]: c for c in p4["caseResults"]}

    print("\n=== P3 vs P4 retrieval diagnostics (miss cases) ===")
    for case_key in MISS_CASES:
        a, b = p3m[case_key], p4m[case_key]
        print(f"\n--- {case_key} ---")
        print(f"  chunkHit@8: P3={a['chunkHitAt8']} -> P4={b['chunkHitAt8']}")
        da, db = a.get("retrievalDiagnostics") or {}, b.get("retrievalDiagnostics") or {}
        print(
            f"  goldChunkRrfRank: P3={da.get('goldChunkRrfRank')} -> P4={db.get('goldChunkRrfRank')}"
        )
        print(
            f"  candidateHit@50: P3={da.get('candidateHitAt50')} -> P4={db.get('candidateHitAt50')}"
        )
        for label, diag in [("P3", da), ("P4", db)]:
            groups = diag.get("requirementGroups") or []
            if not groups:
                continue
            parts = []
            for g in groups:
                parts.append(
                    f"{g['groupKey']} rrf={g.get('rrfFirstRank')} "
                    f"final={g.get('finalFirstRank')} ok={g.get('satisfiedAt8')}"
                )
            print(f"  {label} groups: " + ", ".join(parts))

    # cases that regressed from hit to miss
    print("\n=== Chunk R@8 regressions (P3 hit -> P4 miss) ===")
    for case_key in p3m:
        if p3m[case_key]["chunkHitAt8"] and not p4m[case_key]["chunkHitAt8"]:
            print(f"  {case_key}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
