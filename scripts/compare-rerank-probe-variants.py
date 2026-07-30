"""对比 rerank probe 多方案：RRF-only / rrf0 / fusion0.25 / div0.05。"""
import json
from pathlib import Path

BASELINE = Path(
    "output/rag-gold-runs/lexical-v3b/rag-gold-run-1f18bf03-0774-623a-9c58-73f370818aa4.json"
)
RRF0 = Path(
    "output/rag-gold-runs/rerank-probe-15/rag-gold-run-1f18bf2b-3e29-64f1-9ed0-61918729d704.json"
)
FUSION = Path(
    "output/rag-gold-runs/rerank-probe-15-fusion025/rag-gold-run-1f18bf3e-46f6-6590-8472-3bab91e2f81e.json"
)
DIV005 = Path(
    "output/rag-gold-runs/rerank-probe-15-div005/rag-gold-run-1f18bf42-8697-6d9f-b768-b72edf0bb9b4.json"
)
SLICE = Path("evaluation/rag/gold/slices/rerank-probe-15.txt")


def load_cases(path: Path) -> dict:
    return {c["caseKey"]: c for c in json.loads(path.read_text(encoding="utf-8"))["caseResults"]}


def chunk_hits(cases: dict, keys: list[str]) -> int:
    return sum(1 for key in keys if cases[key].get("chunkHitAt8"))


def cross_dual_hits(cases: dict, keys: list[str]) -> tuple[int, int]:
    cross = [key for key in keys if cases[key]["questionType"] == "CROSS_DOCUMENT"]
    dual = 0
    for key in cross:
        diag = cases[key].get("retrievalDiagnostics") or {}
        if diag.get("finalCrossDocumentDualHitAt8"):
            dual += 1
    return dual, len(cross)


def compare_label(base_hit: bool, variant_hit: bool) -> str:
    if not base_hit and variant_hit:
        return "GAINED"
    if base_hit and not variant_hit:
        return "LOST"
    if base_hit and variant_hit:
        return "stay_hit"
    return "stay_miss"


def main() -> None:
    keys = [
        line.strip()
        for line in SLICE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    base = load_cases(BASELINE)
    variants = {
        "rrf0": load_cases(RRF0),
        "fusion025": load_cases(FUSION),
        "div005": load_cases(DIV005),
    }
    n = len(keys)

    print("=== rerank-probe-15: RRF-only vs rerank variants ===")
    print(f"cases: {n}")
    hits = {"baseline": chunk_hits(base, keys)}
    hits.update({name: chunk_hits(cases, keys) for name, cases in variants.items()})
    print(
        "chunk R@8: "
        + f"rrf-only {hits['baseline']}/{n} "
        + " ".join(f"-> {name} {hits[name]}/{n}" for name in variants)
    )
    for name, cases in variants.items():
        gained = sum(
            1
            for key in keys
            if not base[key].get("chunkHitAt8") and cases[key].get("chunkHitAt8")
        )
        lost = sum(
            1
            for key in keys
            if base[key].get("chunkHitAt8") and not cases[key].get("chunkHitAt8")
        )
        print(f"{name} vs baseline: gained={gained} lost={lost}")

    dual_base, cross_n = cross_dual_hits(base, keys)
    print(f"CROSS dual-hit@8 (baseline {dual_base}/{cross_n})", end="")
    for name, cases in variants.items():
        dual, _ = cross_dual_hits(cases, keys)
        print(f" -> {name} {dual}/{cross_n}", end="")
    print()
    print()
    header = f"{'case':8} {'type':6} " + " ".join(f"{name:9}" for name in variants) + " rrf r0 fu div"
    print(header)
    for key in keys:
        b = base[key]
        brd = b.get("retrievalDiagnostics") or {}
        labels = [
            compare_label(bool(b.get("chunkHitAt8")), bool(variants[name][key].get("chunkHitAt8")))
            for name in variants
        ]
        after = [
            (variants[name][key].get("retrievalDiagnostics") or {}).get("rerankAfterRank")
            for name in variants
        ]
        print(
            f"{key:8} {b['questionType'][:6]:6} "
            + " ".join(f"{label:9}" for label in labels)
            + f" rrf={brd.get('goldChunkRrfRank')} "
            + " ".join(str(rank) for rank in after)
        )


if __name__ == "__main__":
    main()
