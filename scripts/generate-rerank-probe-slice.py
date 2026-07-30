"""从 v3b 评测结果筛选 rerank-probe 子集：Top50 命中、Top8 未中、gold RRF rank 9-20。"""
import json
from collections import defaultdict
from pathlib import Path

V3B_RUN = Path("output/rag-gold-runs/lexical-v3b/rag-gold-run-1f18bf03-0774-623a-9c58-73f370818aa4.json")
OUT = Path("evaluation/rag/gold/slices/rerank-probe-15.txt")

QUOTA = {
    "SINGLE_DOCUMENT_FACT": 8,
    "CROSS_DOCUMENT": 4,
    "VERSION_CONFLICT": 2,
    "OPERATION_PROCESS": 1,
}

run = json.loads(V3B_RUN.read_text(encoding="utf-8"))
candidates = []
for case in run["caseResults"]:
    if case.get("expectedEvidenceCount", 0) == 0:
        continue
    rd = case.get("retrievalDiagnostics") or {}
    if not rd.get("candidateHitAt50"):
        continue
    if case.get("chunkHitAt8"):
        continue
    rank = rd.get("goldChunkRrfRank") or 0
    if rank < 9 or rank > 20:
        continue
    candidates.append(
        {
            "case_key": case["caseKey"],
            "question_type": case["questionType"],
            "rank": rank,
        }
    )

candidates.sort(key=lambda item: item["rank"])
picked = []
remaining = dict(QUOTA)
by_type = defaultdict(list)
for item in candidates:
    by_type[item["question_type"]].append(item)

for qtype, limit in QUOTA.items():
    for item in by_type[qtype][:limit]:
        picked.append(item)

# 若某题型不足，用其余 SINGLE_FACT 补齐到 15
if len(picked) < 15:
    picked_keys = {item["case_key"] for item in picked}
    extras = [
        item
        for item in candidates
        if item["case_key"] not in picked_keys and item["question_type"] == "SINGLE_DOCUMENT_FACT"
    ]
    for item in extras:
        if len(picked) >= 15:
            break
        picked.append(item)

picked = picked[:15]
picked.sort(key=lambda item: item["rank"])

lines = [
    "# Rerank probe: cand50 hit, top8 miss, gold RRF rank 9-20 (from v3b run 1f18bf03)",
]
for item in picked:
    lines.append(
        f"# {item['case_key']} type={item['question_type']} gold_rrf_rank={item['rank']}"
    )
    lines.append(item["case_key"])

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"Wrote {len(picked)} cases to {OUT}")
for item in picked:
    print(f"  {item['case_key']} {item['question_type']} rank={item['rank']}")
