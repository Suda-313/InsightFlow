#!/usr/bin/env python3
"""汇总 Phase 4A 消融结果 JSON 摘要中的关键检索指标。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


VARIANTS = ["p1-baseline", "p2-identifier", "p3-subquota", "p2p3-full"]
SLICES = ["cross-dev-slice", "dev-fast-40", "dev-240", "val-80"]
METRIC_KEYS = [
    ("chunkRecallAt8", "chunk@8"),
    ("primaryRecallAt8", "primary@8"),
    ("finalEvidenceCoverageAt8", "finalCov@8"),
    ("finalCrossDocumentDualHitAt8", "crossDual@8"),
]


def load_latest_summary(run_dir: Path) -> dict | None:
    files = sorted(run_dir.glob("rag-gold-run-*.json"), key=lambda p: p.stat().st_mtime)
    if not files:
        return None
    with files[-1].open(encoding="utf-8") as f:
        return json.load(f)


def extract_row(summary: dict) -> dict:
    ext = summary.get("metrics", {}).get("extended") or {}
    row = {
        "runPublicId": summary.get("runPublicId"),
        "retrievalVersion": summary.get("retrievalVersion"),
        "checksum": ext.get("checksum"),
    }
    for key, _label in METRIC_KEYS:
        value = ext.get(key)
        row[key] = None if value is None else round(float(value) * 100, 1)
    funnel = ext.get("retrievalFunnel") or {}
    cand = funnel.get("candidateChunkRecallAt50")
    row["candidateChunk@50"] = None if cand is None else round(float(cand) * 100, 1)
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize Phase 4A ablation runs")
    parser.add_argument(
        "--root",
        default="output/rag-gold-runs/phase4a",
        help="Phase 4A output root directory",
    )
    args = parser.parse_args()
    root = Path(args.root)

    print(f"Phase 4A summary under {root.resolve()}\n")

    for slice_name in SLICES:
        print(f"## {slice_name}")
        header = ["variant"] + [label for _, label in METRIC_KEYS] + ["cand@50", "checksum"]
        print("| " + " | ".join(header) + " |")
        print("| " + " | ".join(["---"] * len(header)) + " |")
        for variant in VARIANTS:
            run_dir = root / variant / slice_name
            summary = load_latest_summary(run_dir)
            if summary is None:
                print(f"| {variant} | (missing) |")
                continue
            row = extract_row(summary)
            cells = [variant]
            for key, _ in METRIC_KEYS:
                v = row.get(key)
                cells.append("-" if v is None else f"{v:.1f}%")
            cand = row.get("candidateChunk@50")
            cells.append("-" if cand is None else f"{cand:.1f}%")
            checksum = row.get("checksum") or "-"
            cells.append(checksum[:12] + "…" if len(checksum) > 12 else checksum)
            print("| " + " | ".join(cells) + " |")
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
