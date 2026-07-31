import json
from pathlib import Path

baseline = Path(r"d:/yuqiagent/output/rag-gold-runs/dev-240-p3-subquota/rag-gold-run-1f18c1c9-f9dc-6311-abb1-c5b83d30a4a2.json")
new = Path(r"d:/yuqiagent/output/rag-gold-runs/w2-acceptance/dev-240-post-w2/rag-gold-run-1f18c8b2-a568-6a36-bc53-715284481be0.json")

def load(p):
    d=json.load(open(p,encoding="utf-8"))
    e=d["metrics"]["extended"]
    cases={c["caseKey"]:c for c in d["caseResults"]}
    cand50=sum(1 for c in d["caseResults"] if (c.get("retrievalDiagnostics") or {}).get("candidateHitAt50"))/len(d["caseResults"])
    return d, e, cases, cand50

b_d,b_e,b_cases,b_c50=load(baseline)
n_d,n_e,n_cases,n_c50=load(new)

metrics=[
 "primaryRecallAt8","chunkRecallAt8","documentRecallAt8","finalEvidenceCoverageAt8","requirementGroupCoverageAt8"
]
print("baseline_run", b_d["runPublicId"])
print("new_run", n_d["runPublicId"])
print("baseline_checksum", b_e.get("checksum"))
print("new_checksum", n_e.get("checksum"))

print("\nMETRIC TABLE")
header = ["metric","baseline","new","match"]
print("\t".join(header))
for k in metrics:
    bv=b_e.get(k); nv=n_e.get(k)
    match = bv==nv
    print(k, bv, nv, match, sep="\t")
print("candidateHitAt50_mean", b_c50, n_c50, b_c50==n_c50, sep="\t")

for k in ["falseAbstentionRate","correctAbstentionRate","evidenceGateEnabled"]:
    print(k, "baseline", b_e.get(k, b_d["metrics"].get(k)), "new", n_e.get(k, n_d["metrics"].get(k)))

diffs=[]
for key in sorted(b_cases.keys()):
    bc=b_cases[key]; nc=n_cases.get(key)
    if not nc:
        diffs.append((key, {"missing": True}))
        continue
    bd=bc.get("retrievalDiagnostics") or {}
    nd=nc.get("retrievalDiagnostics") or {}
    row={}
    for f in ["chunkHitAt8","documentHitAt8"]:
        if bc.get(f)!=nc.get(f):
            row[f]=(bc.get(f), nc.get(f))
    if bd.get("finalTop8ChunkIds")!=nd.get("finalTop8ChunkIds"):
        row["top8_chunks"]=True
    if bd.get("subQueries")!=nd.get("subQueries"):
        row["subQueries"]=(bd.get("subQueries"), nd.get("subQueries"))
    if row:
        diffs.append((key, row, bc.get("questionType")))

print("per_case_diffs", len(diffs))
for item in diffs[:20]:
    print(item)

all_identical = all(b_e.get(k)==n_e.get(k) for k in metrics) and b_c50==n_c50 and len(diffs)==0
print("PASS", all_identical)
