"""Offline DSPy compile step for marola's "why is this the best hour" summary.

DSPy is Python-only (no JVM port exists — see docs/FUTURE-WORK.md §10 for the Scala-ecosystem gap
and the proposed `ds4s` port) and its optimizer is a compile-time step, not a runtime dependency, so
it runs here, once, against a real LLM, and writes out a small JSON artifact (instructions + few-shot
demos) — not model weights, just an optimized prompt. The Kyo/Scala service (`core/src/main/scala`,
`marola.llm.CompiledPrompt`) loads that artifact and replays it through the same model at request
time via a plain structured-output call, with no Python in the runtime path — see
docs/ARCHITECTURE.md §5a.

Run for real against a local Ollama model (see README.md's Status section) — zero cost by default.
It calls the model repeatedly to bootstrap few-shot demos, so against a paid endpoint it costs real
(if small) money. Point `MAROLA_DSPY_MODEL` at whichever model marola will actually run at request
time so the optimized prompt matches the model that'll replay it; the default is the same local
`llama3.2` the Scala side defaults to.

Usage:
    cd dspy
    python3 -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt

    # Plain OpenAI (quickest to try locally):
    export OPENAI_API_KEY=sk-...
    python compile_recommendation_prompt.py

    # Azure OpenAI / Foundry instead (DSPy uses LiteLLM under the hood —
    # https://docs.litellm.ai/docs/providers/azure — these are LiteLLM's env
    # var names, not this repo's usual FOUNDRY_* ones):
    export AZURE_API_KEY=...
    export AZURE_API_BASE=https://<your-resource>.openai.azure.com
    export AZURE_API_VERSION=2026-01-01-preview
    export MAROLA_DSPY_MODEL=azure/<your-deployment-name>
    python compile_recommendation_prompt.py

    # Optional: trace every LLM call this script makes to Langfuse
    # (https://langfuse.com) — see _init_langfuse_tracing()'s docstring for why that's
    # specifically useful for a DSPy optimizer run. Omit these entirely to skip tracing.
    export MAROLA_LANGFUSE_PUBLIC_KEY=pk-lf-...
    export MAROLA_LANGFUSE_SECRET_KEY=sk-lf-...
    export MAROLA_LANGFUSE_BASE_URL=https://cloud.langfuse.com  # or your self-hosted instance
"""

from __future__ import annotations

import os

import dspy

INPUT_FIELDS = (
    "beach_name",
    "hour_local",
    "sea_temp_c",
    "wind_kmh",
    "wave_height_m",
    "jellyfish_risk",
    "whale_sighting_likelihood",
    "score",
)


class SummarizeSwimConditions(dspy.Signature):
    """Explain, in one or two friendly sentences, why this hour/beach is (or isn't) a good time
    for open-water swimming tomorrow — for someone who already trusts the numbers (they're shown
    separately) and just wants the human-readable takeaway. Always call out jellyfish risk when
    it's Moderate or High. Mention whale sighting likelihood only when it's Moderate or High —
    it's a nice-to-know, not a safety factor, so don't let it crowd out the jellyfish/conditions
    takeaway. Never invent conditions not given."""

    beach_name: str = dspy.InputField()
    hour_local: str = dspy.InputField(desc="e.g. 'Sat 5 Sep, 10:00'")
    sea_temp_c: str = dspy.InputField()
    wind_kmh: str = dspy.InputField()
    wave_height_m: str = dspy.InputField()
    jellyfish_risk: str = dspy.InputField(desc="Low, Moderate, or High")
    whale_sighting_likelihood: str = dspy.InputField(
        desc="Low, Moderate, or High — informational only, never affects the score"
    )
    score: str = dspy.InputField(desc="0-100 swimability score, already computed")

    summary: str = dspy.OutputField(desc="1-2 sentences, plain language, no bullet points")


# Hand-labeled examples double as this module's eval set — see docs/FUTURE-WORK.md §4.1 for the gap
# between "doubles as an eval set" and an actual held-out dspy.Evaluate loop. Numbers below are
# illustrative, not pulled from a real forecast.
TRAINSET = [
    dspy.Example(
        beach_name="Arpoador",
        hour_local="Sat 5 Sep, 07:00",
        sea_temp_c="24.1",
        wind_kmh="6",
        wave_height_m="0.4",
        jellyfish_risk="Low",
        whale_sighting_likelihood="High",
        score="92",
        summary=(
            "Great pick — calm, warm water and light wind at 7am, with low jellyfish risk. "
            "Conditions are also calm enough that you've got a real shot at spotting a whale "
            "this time of year. One of the best windows tomorrow."
        ),
    ).with_inputs(*INPUT_FIELDS),
    dspy.Example(
        beach_name="Praia Vermelha",
        hour_local="Sat 5 Sep, 15:00",
        sea_temp_c="26.8",
        wind_kmh="9",
        wave_height_m="0.3",
        jellyfish_risk="High",
        whale_sighting_likelihood="Low",
        score="55",
        summary=(
            "Water looks calm and warm, but that same calm is exactly what tends to bring "
            "jellyfish in — worth a visual check from the sand before wading in."
        ),
    ).with_inputs(*INPUT_FIELDS),
    dspy.Example(
        beach_name="Praia do Leme",
        hour_local="Sat 5 Sep, 18:00",
        sea_temp_c="21.5",
        wind_kmh="32",
        wave_height_m="1.8",
        jellyfish_risk="Low",
        whale_sighting_likelihood="Low",
        score="20",
        summary=(
            "Skip this one — strong wind and rough 1.8m seas by 6pm make for a genuinely tough, "
            "choppy swim."
        ),
    ).with_inputs(*INPUT_FIELDS),
]


REVIEW_INPUT_FIELDS = INPUT_FIELDS + ("summary",)


class ReviewSwimSummary(dspy.Signature):
    """Review a generated swim-conditions summary against the structured facts it was based on —
    the reviewer/critic pass marola's own summarization step (SummarizeSwimConditions) doesn't get
    to grade itself on. Check: (1) does it mention jellyfish risk when Moderate/High? (2) does it
    mention whale sighting likelihood only when Moderate/High, without crowding out the
    jellyfish/conditions takeaway? (3) does it avoid asserting anything not present in the given
    facts? Respond with ONLY a compact JSON object, no other text, no code fences:
    {"score": <0-100 integer, higher = better>, "verdict": "approve" or "revise", "final_summary":
    "<the original summary unchanged if approved, or a corrected one-to-two-sentence replacement
    if revise>"}."""

    beach_name: str = dspy.InputField()
    hour_local: str = dspy.InputField()
    sea_temp_c: str = dspy.InputField()
    wind_kmh: str = dspy.InputField()
    wave_height_m: str = dspy.InputField()
    jellyfish_risk: str = dspy.InputField()
    whale_sighting_likelihood: str = dspy.InputField()
    score: str = dspy.InputField()
    summary: str = dspy.InputField(desc="the draft summary to review")

    review_json: str = dspy.OutputField(
        desc='compact JSON only: {"score": int, "verdict": "approve"|"revise", "final_summary": str}'
    )


# Two of these are deliberately drafts with a real flaw, not already-good summaries — a reviewer
# that only ever sees good input never learns what "revise" looks like.
REVIEW_TRAINSET = [
    dspy.Example(
        beach_name="Arpoador",
        hour_local="Sat 5 Sep, 07:00",
        sea_temp_c="24.1",
        wind_kmh="6",
        wave_height_m="0.4",
        jellyfish_risk="Low",
        whale_sighting_likelihood="High",
        score="92",
        summary=(
            "Great pick — calm, warm water and light wind at 7am, with low jellyfish risk. "
            "Conditions are also calm enough that you've got a real shot at spotting a whale "
            "this time of year."
        ),
        review_json=(
            '{"score": 95, "verdict": "approve", "final_summary": "Great pick — calm, warm '
            "water and light wind at 7am, with low jellyfish risk. Conditions are also calm "
            "enough that you've got a real shot at spotting a whale this time of year.\"}"
        ),
    ).with_inputs(*REVIEW_INPUT_FIELDS),
    dspy.Example(
        # Flaw: jellyfish_risk is High but the draft never mentions it — must be caught and fixed.
        beach_name="Praia Vermelha",
        hour_local="Sat 5 Sep, 15:00",
        sea_temp_c="26.8",
        wind_kmh="9",
        wave_height_m="0.3",
        jellyfish_risk="High",
        whale_sighting_likelihood="Low",
        score="55",
        summary="Nice and calm at 3pm, warm water too — a pleasant time for a swim.",
        review_json=(
            '{"score": 30, "verdict": "revise", "final_summary": "Water is calm and warm at 3pm, '
            "but jellyfish risk is High right now — worth a visual check from the sand "
            'before wading in."}'
        ),
    ).with_inputs(*REVIEW_INPUT_FIELDS),
    dspy.Example(
        # Flaw: invents a "riptide" warning not present in any input field — a hallucination check.
        beach_name="Praia do Leme",
        hour_local="Sat 5 Sep, 18:00",
        sea_temp_c="21.5",
        wind_kmh="32",
        wave_height_m="1.8",
        jellyfish_risk="Low",
        whale_sighting_likelihood="Low",
        score="20",
        summary="Skip this one — strong wind, rough 1.8m seas, and a dangerous riptide by 6pm.",
        review_json=(
            '{"score": 40, "verdict": "revise", "final_summary": "Skip this one — strong '
            'wind and rough 1.8m seas by 6pm make for a genuinely tough, choppy swim."}'
        ),
    ).with_inputs(*REVIEW_INPUT_FIELDS),
]


def review_json_is_well_formed_and_sound(example, prediction, trace=None) -> float:
    """Deterministic metric: valid JSON with the three required keys is worth more than getting
    the verdict itself right — a reviewer that can't be parsed is useless regardless of how good
    its judgment is, so structural validity is checked first and weighted heaviest."""
    import json

    text = (prediction.review_json or "").strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return 0.0
    try:
        parsed = json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return 0.0
    if not all(k in parsed for k in ("score", "verdict", "final_summary")):
        return 0.3
    score = 0.7
    expected = json.loads(example.review_json)  # always valid — it's our own trainset literal
    if parsed.get("verdict") == expected.get("verdict"):
        score += 0.3
    return score


def jellyfish_and_whale_mentioned_when_relevant(example, prediction, trace=None) -> float:
    """Cheap, deterministic metric (no second LLM call needed to judge quality): reward non-empty
    summaries, reward mentioning jellyfish whenever the computed risk is Moderate/High — the whole
    point of surfacing that heuristic is that it reaches the reader — and likewise for whale
    sighting likelihood, weighted lower since it's a nice-to-know, not a safety factor (see the
    Signature's own instruction not to let it crowd out the jellyfish/conditions takeaway)."""
    text = (prediction.summary or "").strip()
    if not text:
        return 0.0
    score = 1.0
    if example.jellyfish_risk in ("Moderate", "High") and "jelly" not in text.lower():
        score -= 0.7
    if example.whale_sighting_likelihood in ("Moderate", "High") and "whale" not in text.lower():
        score -= 0.3
    return max(score, 0.0)


def _init_langfuse_tracing() -> None:
    """Traces every LLM call this compile step makes (each bootstrap attempt against TRAINSET,
    each metric-scored candidate) to Langfuse — genuinely useful here specifically because
    BootstrapFewShot/MIPROv2 call the model many times per run and "why did the optimizer pick
    these demos" is otherwise opaque. Optional and silent-by-default: if
    MAROLA_LANGFUSE_PUBLIC_KEY isn't set (e.g. running this without Langfuse set up at all),
    this no-ops rather than failing the whole compile step over an observability nice-to-have.

    MUST run before dspy.configure() — per Langfuse's own DSPy integration docs
    (https://langfuse.com/integrations/frameworks/dspy), the OpenInference instrumentor has to be
    installed before any DSPy LM calls happen for spans to be captured from the start.

    Needs `langfuse` and `openinference-instrumentation-dspy` (see requirements.txt) — both use
    Langfuse's OTEL-based Python SDK v3, confirmed against a real `langfuse==4.15.1` install
    (pinned loosely below, same rationale as DSPy's own pin).
    """
    if not os.environ.get("MAROLA_LANGFUSE_PUBLIC_KEY"):
        return

    # Langfuse's SDK reads its own LANGFUSE_* env var names (see langfuse/README.md), not this
    # repo's MAROLA_-prefixed convention (AppConfig.scala, .env.example) — bridge here rather
    # than asking the operator to set both.
    os.environ.setdefault("LANGFUSE_PUBLIC_KEY", os.environ["MAROLA_LANGFUSE_PUBLIC_KEY"])
    if "MAROLA_LANGFUSE_SECRET_KEY" in os.environ:
        os.environ.setdefault("LANGFUSE_SECRET_KEY", os.environ["MAROLA_LANGFUSE_SECRET_KEY"])
    if "MAROLA_LANGFUSE_BASE_URL" in os.environ:
        os.environ.setdefault("LANGFUSE_BASE_URL", os.environ["MAROLA_LANGFUSE_BASE_URL"])

    from langfuse import get_client

    langfuse = get_client()
    try:
        authenticated = langfuse.auth_check()
    except Exception as exc:  # noqa: BLE001 — deliberately broad: any reachability/auth problem
        # with an *optional* observability integration should not fail the whole compile step.
        print(f"warning: Langfuse auth_check() raised ({exc!r}) — continuing without tracing.")
        return
    if not authenticated:
        print(
            "warning: Langfuse credentials set but auth_check() returned false — continuing "
            "without tracing rather than failing the compile step."
        )
        return

    from openinference.instrumentation.dspy import DSPyInstrumentor

    DSPyInstrumentor().instrument()
    print(
        f"Langfuse tracing enabled -> {os.environ.get('LANGFUSE_BASE_URL', 'https://cloud.langfuse.com')}"
    )


def main() -> None:
    _init_langfuse_tracing()  # must run before dspy.configure() — see that function's docstring

    # Default is a local Ollama model (ollama_chat/<name>, LiteLLM's Ollama chat-endpoint prefix —
    # confirmed end-to-end against a real local Ollama install; see dspy/README.md's Status
    # section) so this compile step needs zero Azure/OpenAI account by default, matching the rest
    # of marola's local-first design (see ARCHITECTURE.md §5/§6). "llama3.2" is a small, commonly
    # pulled model (`ollama pull llama3.2`) — override MAROLA_DSPY_MODEL to whatever you actually
    # have, or to Foundry/OpenAI instead (see this file's module docstring for both).
    model = os.environ.get("MAROLA_DSPY_MODEL", "ollama_chat/llama3.2")
    api_base = os.environ.get("MAROLA_DSPY_API_BASE")  # e.g. http://localhost:11434, Ollama-only
    lm_kwargs = {"api_base": api_base} if api_base else {}
    dspy.configure(lm=dspy.LM(model, **lm_kwargs))

    program = dspy.Predict(SummarizeSwimConditions)

    optimizer = dspy.teleprompt.BootstrapFewShot(
        metric=jellyfish_and_whale_mentioned_when_relevant,
        max_bootstrapped_demos=3,
    )
    compiled = optimizer.compile(student=program, trainset=TRAINSET)

    resources_dir = os.path.join(
        os.path.dirname(__file__), "..", "core", "src", "main", "resources"
    )
    summary_path = os.path.join(resources_dir, "recommendation_prompt.json")
    compiled.save(summary_path)
    print(f"Compiled prompt artifact written to {os.path.abspath(summary_path)}")

    # Second program: the reviewer/critic pass — marola.llm.Reviewer replays this artifact against
    # the draft summary the first program produced, before either is shown to a user. See
    # ARCHITECTURE.md §5a and FUTURE-WORK.md §4.2 for why this exists as a second LLM pass rather
    # than folding review logic into SummarizeSwimConditions itself: a model grading its own answer
    # in the same call can't catch its own mistakes as reliably as a fresh pass focused only on
    # checking, not generating.
    review_program = dspy.Predict(ReviewSwimSummary)
    review_optimizer = dspy.teleprompt.BootstrapFewShot(
        metric=review_json_is_well_formed_and_sound,
        max_bootstrapped_demos=3,
    )
    compiled_review = review_optimizer.compile(student=review_program, trainset=REVIEW_TRAINSET)
    review_path = os.path.join(resources_dir, "review_prompt.json")
    compiled_review.save(review_path)
    print(f"Compiled review-prompt artifact written to {os.path.abspath(review_path)}")


if __name__ == "__main__":
    main()
