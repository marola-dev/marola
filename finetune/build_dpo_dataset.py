"""Build marola's DPO preference dataset (MIP-0025 §4.3, Layer 3) from `core/llm/Reviewer.scala`'s
own historical reject/revise decisions.

Real historical decisions are already captured, once, as the DSPy-compiled `review_prompt.json`
demos (core/src/main/resources/review_prompt.json) — bootstrap examples where a real Ollama
reviewer graded a real draft summary. Wherever a demo's verdict wasn't "approve", the reviewer's
own `final_summary` is a real correction of a real flawed draft: that is exactly a (chosen,
rejected) preference pair, with no invented text on either side. An "approve" demo carries no
preference signal (the draft was fine as-is) and yields no pair — one pair per reject/revise
decision, never more, never fabricated when there isn't one.

Run:  python build_dpo_dataset.py             (or `just finetune-dpo-dataset`)
Self-test:  python build_dpo_dataset.py --self-test   (or `just quality-other`)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dataset as bd  # reuse INPUT_FIELDS/render_inputs — same conditions shape

RESOURCES = bd.RESOURCES
OUT = Path(__file__).resolve().parent / "data"

DPO_INPUT_FIELDS = bd.INPUT_FIELDS


def review_records(path: Path) -> list[dict]:
    """Real Reviewer decisions: one dict per review_prompt.json demo, in ReviewResult's own shape
    (Reviewer.scala's `ReviewResult(finalSummary, score, verdict)`) plus the draft it reviewed."""
    doc = json.loads(path.read_text(encoding="utf-8"))
    records = []
    for demo in doc.get("demos", []):
        if "review_json" not in demo or "summary" not in demo:
            continue
        review = json.loads(demo["review_json"])
        records.append(
            {
                "conditions": {k: demo[k] for k in DPO_INPUT_FIELDS if k in demo},
                "draft_summary": demo["summary"],
                "score": review.get("score"),
                "verdict": review.get("verdict", "approve"),
                "final_summary": review.get("final_summary", demo["summary"]),
            }
        )
    return records


def preference_pairs(records: list[dict]) -> list[dict]:
    """One (chosen, rejected) pair per real reject/revise record; none for "approve"."""
    pairs = []
    for r in records:
        if r["verdict"] == "approve":
            continue
        if r["final_summary"] == r["draft_summary"]:
            continue  # flagged but the text didn't actually change — no real preference signal
        prompt = bd.render_inputs(r["conditions"], DPO_INPUT_FIELDS)
        pairs.append(
            {"prompt": prompt, "chosen": r["final_summary"], "rejected": r["draft_summary"]}
        )
    return pairs


def main() -> None:
    records = review_records(RESOURCES / "review_prompt.json")
    pairs = preference_pairs(records)
    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / "dpo_pairs.jsonl").open("w", encoding="utf-8") as f:
        for p in pairs:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    print(
        f"wrote {len(pairs)} DPO preference pairs (from {len(records)} reviewer decisions) to {OUT}"
    )


# --- self-test: a small fixture of Reviewer decisions, not the real ones ---------------------
_FIXTURE_RECORDS = [
    {
        "conditions": {"beach_name": "Fixture Beach A", "score": "90"},
        "draft_summary": "Great conditions, go for it.",
        "score": 90,
        "verdict": "approve",
        "final_summary": "Great conditions, go for it.",
    },
    {
        "conditions": {"beach_name": "Fixture Beach B", "score": "40"},
        "draft_summary": "Nice and calm, a pleasant swim.",
        "score": 35,
        "verdict": "revise",
        "final_summary": "Calm but high jellyfish risk — worth a second look before swimming.",
    },
    {
        "conditions": {"beach_name": "Fixture Beach C", "score": "5"},
        "draft_summary": "Great day for a swim.",
        "score": 5,
        "verdict": "reject",
        "final_summary": "Unsafe: strong riptide and rough seas — do not swim.",
    },
]


def _self_test() -> None:
    # Fixture with reject/revise events: exactly one pair per non-approve event, nothing invented.
    pairs = preference_pairs(_FIXTURE_RECORDS)
    assert len(pairs) == 2, f"expected 2 pairs (one revise + one reject), got {len(pairs)}"
    chosen_texts = {p["chosen"] for p in pairs}
    assert _FIXTURE_RECORDS[1]["final_summary"] in chosen_texts
    assert _FIXTURE_RECORDS[2]["final_summary"] in chosen_texts
    for p in pairs:
        assert p["chosen"] != p["rejected"], "chosen and rejected must differ"

    # A fixture with only "approve" events: no reject/revise → zero pairs, none invented.
    approve_only = [r for r in _FIXTURE_RECORDS if r["verdict"] == "approve"]
    assert preference_pairs(approve_only) == [], "an all-approve fixture must yield zero pairs"

    # Real source check: review_prompt.json's own demos really do have reject/revise decisions,
    # and every generated pair's text matches one of them verbatim — no rewording, no invention.
    real_records = review_records(RESOURCES / "review_prompt.json")
    real_pairs = preference_pairs(real_records)
    assert real_pairs, "no real reject/revise decisions found in review_prompt.json's demos"
    real_texts = {
        (r["final_summary"], r["draft_summary"])
        for r in real_records
        if r["verdict"] != "approve" and r["final_summary"] != r["draft_summary"]
    }
    generated_texts = {(p["chosen"], p["rejected"]) for p in real_pairs}
    assert generated_texts <= real_texts, "a generated pair's text doesn't match a real decision"

    print(
        f"self-test OK: {len(pairs)} pairs from the fixture, {len(real_pairs)} from "
        f"review_prompt.json's real reviewer decisions, all verbatim"
    )


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        _self_test()
    else:
        main()
