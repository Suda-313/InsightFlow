import json
from pathlib import Path

BASELINE = Path("output/rag-gold-runs/lexical-v3b/rag-gold-run-1f18bf03-0774-623a-9c58-73f370818aa4.json")
RERANK = Path(
    "output/rag-gold-runs/lexical-v3b-fast40-rerank/rag-gold-run-1f18bf2f-3d4e-6605-bc3b-2bd4e51d59b5.json"
)
SLICE = Path("evaluation/rag/gold/slices/dev-fast-40.txt")

keys = [
    line.strip()
    for line in SLICE.read_text(encoding="utf-8").splitlines()
    if line.strip() and not line.startswith("#")
]
base = {c["caseKey"]: c for c in json.loads(BASELINE.read_text(encoding="utf-8"))["caseResults"]}
rer = {c["caseKey"]: c for c in json.loads(RERANK.read_text(encoding="utf-8"))["caseResults"]}
ext = json.loads(RERANK.read_text(encoding="utf-8"))["metrics"]["extended"]


def ratio(hits, total):
    return hits / total if total else 0.0


n = len(keys)
b_chunk = sum(1 for key in keys if base[key].get("chunkHitAt8"))
r_chunk = sum(1 for key in keys if rer[key].get("chunkHitAt8"))
b_doc = sum(1 for key in keys if base[key].get("documentHitAt8"))
r_doc = sum(1 for key in keys if rer[key].get("documentHitAt8"))
gained = lost = 0
cross_keys = [key for key in keys if base[key]["questionType"] == "CROSS_DOCUMENT"]
cross_dual_b = cross_dual_r = 0
for key in cross_keys:
    base_diag = base[key].get("retrievalDiagnostics") or {}
    rer_diag = rer[key].get("retrievalDiagnostics") or {}
    if base_diag.get("finalCrossDocumentDualHitAt8"):
        cross_dual_b += 1
    if rer_diag.get("finalCrossDocumentDualHitAt8"):
        cross_dual_r += 1
for key in keys:
    base_hit = base[key].get("chunkHitAt8")
    rer_hit = rer[key].get("chunkHitAt8")
    if not base_hit and rer_hit:
        gained += 1
    if base_hit and not rer_hit:
        lost += 1

print("=== dev-fast-40: v3b rrf-only vs lexical v3 + rerank ===")
print(f"cases: {n}")
print(
    f"chunk R@8: {b_chunk}/{n} ({ratio(b_chunk, n):.1%}) -> "
    f"{r_chunk}/{n} ({ratio(r_chunk, n):.1%}) "
    f"delta {(ratio(r_chunk, n) - ratio(b_chunk, n)) * 100:+.1f}pp"
)
print(f"doc R@8: {b_doc}/{n} ({ratio(b_doc, n):.1%}) -> {r_doc}/{n} ({ratio(r_doc, n):.1%})")
print(f"gained={gained} lost={lost}")
print(f"CROSS dual-hit@8: {cross_dual_b}/{len(cross_keys)} -> {cross_dual_r}/{len(cross_keys)}")
print(f"rerankFallbackRate: {ext.get('rerankFallbackRate')}")
print(f"rerank P50/P95: {ext.get('rerankLatencyP50Ms')}/{ext.get('rerankLatencyP95Ms')} ms")
print(f"retrieval P50/P95: {ext.get('retrievalP50Ms')}/{ext.get('retrievalP95Ms')} ms")
print(f"finalEvidenceCoverage@8: {ext.get('finalEvidenceCoverageAt8')}")
