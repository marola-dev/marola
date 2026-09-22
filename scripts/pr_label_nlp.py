#!/usr/bin/env python3
"""pr_label_nlp — a cheap, local, marola-aware NLP classifier for the `area/*` PR labels.

    scripts/pr_label_nlp.py --pr 168                    # classify a real PR (needs `gh auth status` OK)
    scripts/pr_label_nlp.py --title "..." --body "..."   # classify arbitrary text, no `gh` call
    scripts/pr_label_nlp.py --pr 168 --top 3             # print the top 3 candidates, not just the best
    scripts/pr_label_nlp.py --self-test                  # offline check against fixed text, no network

scripts/lib/pr_labels.sh's own header explains why the taxonomy it drives is deterministic
("never guessed from prose, never an LLM call") — that stays true for what actually gets applied
to a real PR. This script exists to answer a different, narrower question: could a cheap, local
NLP method (no API, no network, no GPU — TF-IDF + cosine similarity, scikit-learn) do a
comparably good job at the one part of the taxonomy that genuinely comes from prose, `area/*`,
which today only resolves via a PR's MIP number and falls back to `area/unscoped` otherwise?

Method: TF-IDF vectorizes a real PR's title + body + commit subjects against a small per-label
corpus (grep-generated below, so it's marola vocabulary, not generic English — the actual jargon
this repo's own PRs use: PRÓPRIA/IMPRÓPRIA, Overpass, Kyo, DSPy, MLflow,
MCP, and so on) and reports the cosine-nearest label(s). It is used by
scripts/backfill-pr-labels.sh's `--nlp` flag strictly as a side-by-side comparison against the
deterministic result — never applied to a real PR unless `--nlp-apply-unscoped` is also passed,
and even then only to fill a genuine area/unscoped gap, never to override a confident deterministic
call. Same "judge, never veto" shape as llm/Reviewer.scala over Swimability.scala's score.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PR_LABELS_SH = REPO_ROOT / "scripts" / "lib" / "pr_labels.sh"

# Marola-domain vocabulary per area label, on top of scripts/lib/pr_labels.sh's own one-line
# taxonomy description — this is what makes the classifier "aware of marola context" rather than
# a generic bag-of-words model. Kept here (not in the .sh file) since it's NLP-specific tuning,
# not part of the deterministic taxonomy's own source of truth.
AREA_CONTEXT_KEYWORDS = {
    "area/conditions": "sea weather tide forecast open-meteo swell wind wave temperature cache latency swimability score",
    "area/water-quality": "bathing water quality sampling point ima inea inema propria impropria enterococci veto unfit",
    "area/sea-life": "jellyfish whale sighting heuristic sea lore corpus marine life season",
    "area/safety": "safety footer hazard escalation rip current warning veto never overturn",
    "area/accessibility": "parking toilets shower lifeguard facilities osm overpass amenity beach",
    "area/map-site": "map static site marker leaflet tooltip legend index.html app.js style.css board",
    "area/telegram-bot": "telegram bot reply digest subscription matching chat",
    "area/outreach": "book exporter instagram waitlist promotion readme site copy marketing",
    "area/ml-infra": "mlflow llm4s dspy fine-tuned finetune model forecasting research benchmark lora sft dpo",
    "area/dev-tooling": "claude code opencode agentic tooling dev workflow skill hook justfile ci",
    "area/positioning": "product naming positioning slogan brand",
}


def load_taxonomy_descriptions() -> dict[str, str]:
    """Source scripts/lib/pr_labels.sh and print PR_LABEL_TAXONOMY so the two classifiers never
    drift apart on label names/descriptions — this script only adds vocabulary, never invents a
    label the deterministic taxonomy doesn't already define."""
    out = subprocess.run(
        ["bash", "-c", f'source "{PR_LABELS_SH}" && printf "%s\\n" "${{PR_LABEL_TAXONOMY[@]}}"'],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    descriptions = {}
    for line in out.splitlines():
        parts = line.split(":", 2)
        if len(parts) == 3:
            name, _color, desc = parts
            descriptions[name] = desc
    return descriptions


def build_area_corpus() -> dict[str, str]:
    descriptions = load_taxonomy_descriptions()
    corpus = {}
    for label, keywords in AREA_CONTEXT_KEYWORDS.items():
        corpus[label] = f"{descriptions.get(label, '')} {keywords}"
    return corpus


def classify(text: str, top: int = 1) -> list[tuple[str, float]]:
    """Returns up to `top` (label, cosine_similarity) pairs, highest similarity first. An empty
    list means nothing scored above zero similarity — the caller decides what that means."""
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.metrics.pairwise import cosine_similarity

    corpus = build_area_corpus()
    labels = list(corpus.keys())
    documents = [corpus[label] for label in labels] + [text]
    vectorizer = TfidfVectorizer(stop_words="english", lowercase=True)
    matrix = vectorizer.fit_transform(documents)
    pr_vector = matrix[-1]
    label_vectors = matrix[:-1]
    similarities = cosine_similarity(pr_vector, label_vectors)[0]
    ranked = sorted(zip(labels, similarities, strict=True), key=lambda pair: pair[1], reverse=True)
    return [(label, float(score)) for label, score in ranked[:top] if score > 0]


def fetch_pr_text(pr_number: str) -> str:
    out = subprocess.run(
        ["gh", "pr", "view", pr_number, "--json", "title,body,commits"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    data = json.loads(out)
    subjects = " ".join(c.get("messageHeadline", "") for c in data.get("commits", []))
    return f"{data.get('title', '')} {data.get('body', '') or ''} {subjects}"


def self_test() -> int:
    fails = 0
    skipped = False

    def ok(got, want, label):
        nonlocal fails
        status = "ok" if got == want else "FAIL"
        if got != want:
            fails += 1
        print(f"  {status}   {label}" + ("" if got == want else f" — got '{got}', want '{want}'"))

    cases = [
        ("Fix jellyfish sighting heuristic — whale season peak hour", "area/sea-life"),
        ("Telegram bot: daily digest subscription for swim matching", "area/telegram-bot"),
        (
            "INEA bathing water quality PDF parser — PRÓPRIA/IMPRÓPRIA sampling points",
            "area/water-quality",
        ),
        ("Add parking and lifeguard facilities from Overpass OSM amenities", "area/accessibility"),
        ("MLflow benchmark run for the fine-tuned DPO model on Ollama", "area/ml-infra"),
        ("Claude Code hook: format-on-write, skills, agentic dev workflow", "area/dev-tooling"),
        ("Safety footer copy: rip current hazard warning, veto text", "area/safety"),
        ("Map marker tooltip and legend on the static site index.html", "area/map-site"),
    ]
    # This is a real, small test corpus check against the actual scikit-learn/vectorizer pipeline —
    # requires scikit-learn importable, but no network and no `gh` call, matching every other
    # script's --self-test convention in this repo (offline, deterministic given fixed input).
    # scikit-learn is an optional dependency here: it is not in flake.nix's devShell and not on
    # the CI runner, so a missing import is an environment fact, not a failure. Skip only the
    # classifier cases and still run the parsing checks below — returning 1 for a skip used to
    # abort `repo_stats.py`'s Python-coverage measurement (it runs every --self-test under
    # `check=True`) and take the whole repo-stats job with it.
    try:
        for text, expected in cases:
            ranked = classify(text, top=1)
            got = ranked[0][0] if ranked else None
            ok(got, expected, f"classify('{text[:40]}...') picks {expected}")
    except ImportError as exc:
        print(
            f"  SKIP  scikit-learn not importable ({exc}) — classifier cases skipped, "
            "parsing checks still run",
            file=sys.stderr,
        )
        skipped = True

    # Pure parsing check, no scikit-learn needed.
    descriptions = load_taxonomy_descriptions()
    ok(
        "area/water-quality" in descriptions,
        True,
        "load_taxonomy_descriptions finds area/water-quality from scripts/lib/pr_labels.sh",
    )
    for label in AREA_CONTEXT_KEYWORDS:
        if label not in descriptions:
            fails += 1
            print(f"  FAIL  AREA_CONTEXT_KEYWORDS has '{label}' which is not in the real taxonomy")

    if fails == 0:
        print("pr_label_nlp self-test: ok" + (" (classifier cases skipped)" if skipped else ""))
        return 0
    print(f"pr_label_nlp self-test: {fails} failure(s)", file=sys.stderr)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--pr", help="PR number to classify (needs gh auth status OK)")
    parser.add_argument("--title", default="", help="classify arbitrary text instead of a real PR")
    parser.add_argument("--body", default="", help="paired with --title")
    parser.add_argument(
        "--top", type=int, default=1, help="how many candidate labels to print (default 1)"
    )
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument(
        "--self-test", action="store_true", help="offline check, no network, no gh call"
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if args.pr:
        text = fetch_pr_text(args.pr)
    elif args.title or args.body:
        text = f"{args.title} {args.body}"
    else:
        parser.error("need --pr, or --title/--body, or --self-test")
        return 2

    ranked = classify(text, top=args.top)
    if args.json:
        print(
            json.dumps([{"label": label, "similarity": round(score, 4)} for label, score in ranked])
        )
    elif not ranked:
        print(
            "pr_label_nlp: no area label scored above zero similarity — text may be too short or off-domain"
        )
    else:
        for label, score in ranked:
            print(f"{label}\t{score:.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
