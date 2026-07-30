"""对比 rerank probe 与 v3b RRF-only 基线（同 15 case）。"""
import json
from pathlib import Path

BASELINE = Path(
    "output/rag-gold-runs/lexical-v3b/rag-gold-run-1f18bf03-0774-623a-9c58-73f370818aa4.json"
)
RERANK = Path(
    "output/rag-gold-runs/rerank-probe-15/rag-gold-run-1f18bf2b-3e29-64f1-9ed0-61918729d704.json"
)
SLICE = Path("evaluation/rag/gold/slices/rerank-probe-15.txt")

keys = [
    line.strip()
    for line in SLICE.read_text(encoding="utf-8").splitlines()
    if line.strip() and not line.startswith("#")
]

base_cases = {c["caseKey"]: c for c in json.loads(BASELINE.read_text(encoding="utf-8"))["caseResults"]}
rerank_cases = {c["caseKey"]: c for c in json.loads(RERANK.read_text(encoding="utf-8"))["caseResults"]}
rerank_meta = json.loads(RERANK.read_text(encoding="utf-8"))

gained = lost = unchanged_hit = unchanged_miss = 0
rows = []
for key in keys:
    b = base_cases[key]
    r = rerank_cases[key]
    b8 = bool(b.get("chunkHitAt8"))
    r8 = bool(r.get("chunkHitAt8"))
    brd = b.get("retrievalDiagnostics") or {}
    rrd = r.get("retrievalDiagnostics") or {}
    if not b8 and r8:
        gained += 1
        delta = "GAINED"
    elif b8 and not r8:
        lost += 1
        delta = "LOST"
    elif b8 and r8:
        unchanged_hit += 1
        delta = "stay_hit"
    else:
        unchanged_miss += 1
        delta = "stay_miss"
    rows.append(
        {
            "key": key,
            "type": b["questionType"],
            "delta": delta,
            "rrf_rank": brd.get("goldChunkRrfRank"),
            "rerank_before": rrd.get("rerankBeforeRank"),
            "rerank_after": rrd.get("rerankAfterRank"),
            "fallback": rrd.get("rerankFallbackUsed"),
            "reranker": rrd.get("rerankerName"),
        }
    )

n = len(keys)
print("=== rerank-probe-15 vs v3b rrf-only ===")
print(f"cases: {n}")
print(f"chunk R@8: rrf-only {sum(1 for k in keys if base_cases[k].get('chunkHitAt8'))}/{n} -> rerank {sum(1 for k in keys if rerank_cases[k].get('chunkHitAt8'))}/{n}")
print(f"GAINED: {gained}  LOST: {lost}  stay_hit: {unchanged_hit}  stay_miss: {unchanged_miss}")
print(f"retrievalVersion: {rerank_meta.get('retrievalVersion')}")
ext = rerank_meta["metrics"]["extended"]
print(f"rerankFallbackRate: {ext.get('rerankFallbackRate')}")
print(f"rerank P50/P95 ms: {ext.get('rerankLatencyP50Ms')}/{ext.get('rerankLatencyP95Ms')}")
print(f"retrieval P50/P95 ms: {ext.get('retrievalP50Ms')}/{ext.get('retrievalP95Ms')}")
print()
for row in rows:
    print(
        f"{row['key']} {row['type'][:6]:6} {row['delta']:9} "
        f"rrf={row['rrf_rank']} rerank {row['rerank_before']}->{row['rerank_after']} "
        f"fallback={row['fallback']} reranker={row['reranker']}"
    )
