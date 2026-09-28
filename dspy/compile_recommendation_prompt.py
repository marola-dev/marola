"""Offline DSPy compile step for marola's "why is this the best hour" summary and its reviewer.

DSPy is Python-only, so the optimizer runs here once and writes JSON artifacts (instructions +
few-shot demos) that `marola.llm.CompiledPrompt`/`Reviewer` replay at request time — no Python in
the runtime path (docs/2-Building-marola/ARCHITECTURE.md §5a).

The default model is deliberately non-Llama (SmolLM2, Apache-2.0): the bootstrapped demos feed
`finetune/build_dataset.py`, and Llama 3.2's Community Licence §1.b.i reaches outputs used to train
a model. Point MAROLA_DSPY_MODEL at the model marola will actually run; against a paid endpoint the
bootstrap calls cost real money.

Usage (see dspy/README.md):
    pip install -r requirements.txt
    python compile_recommendation_prompt.py              # local Ollama by default
    MAROLA_DSPY_MODEL=<litellm-model-id> python ...      # any LiteLLM provider, with its own env keys
    python compile_recommendation_prompt.py --self-test  # offline, no LLM, no MLflow

Optional, both opt-in via env and silent when unset:
    MAROLA_LANGFUSE_PUBLIC_KEY / _SECRET_KEY / _BASE_URL  -> trace every LLM call to Langfuse
    MAROLA_MLFLOW_TRACKING_URI (e.g. from `just mlflow-up`) -> one MLflow run per compile
"""

from __future__ import annotations

import argparse
import json
import os
import sys

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


# Hand-labeled examples double as this module's eval set — see docs/4-Research-and-plans/FUTURE-WORK.md §4.1 for the gap
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


DEFAULT_DSPY_MODEL = "ollama_chat/smollm2:360m"


def review_json_is_well_formed_and_sound(example, prediction, trace=None) -> float:
    """A reviewer that can't be parsed is useless, so structural validity outweighs the verdict."""
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
    """Deterministic: jellyfish (a safety factor) must be mentioned when Moderate/High; whales
    (a nice-to-know) weigh less."""
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
    """Optimizer runs call the model many times; tracing shows why it picked its demos. Must run
    before dspy.configure() (https://langfuse.com/integrations/frameworks/dspy)."""
    if not os.environ.get("MAROLA_LANGFUSE_PUBLIC_KEY"):
        return

    # Langfuse's SDK reads LANGFUSE_*, not this repo's MAROLA_ prefix.
    os.environ.setdefault("LANGFUSE_PUBLIC_KEY", os.environ["MAROLA_LANGFUSE_PUBLIC_KEY"])
    if "MAROLA_LANGFUSE_SECRET_KEY" in os.environ:
        os.environ.setdefault("LANGFUSE_SECRET_KEY", os.environ["MAROLA_LANGFUSE_SECRET_KEY"])
    if "MAROLA_LANGFUSE_BASE_URL" in os.environ:
        os.environ.setdefault("LANGFUSE_BASE_URL", os.environ["MAROLA_LANGFUSE_BASE_URL"])

    from langfuse import get_client

    langfuse = get_client()
    try:
        authenticated = langfuse.auth_check()
    except Exception as exc:  # noqa: BLE001 — optional observability must not fail the compile
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


def _mlflow_tracking_configured() -> bool:
    return bool(os.environ.get("MAROLA_MLFLOW_TRACKING_URI"))


def _log_compile_run_to_mlflow(
    *,
    experiment: str,
    run_name: str,
    params: dict[str, str],
    metrics: dict[str, float],
    artifact_path: str,
) -> None:
    """Hand-logged rather than `mlflow.dspy.autolog(log_compiles=True)`: autolog logs no aggregate
    metric score and names its artifacts differently from the prompt JSONs marola loads."""
    if not _mlflow_tracking_configured():
        return
    import mlflow

    mlflow.set_tracking_uri(os.environ["MAROLA_MLFLOW_TRACKING_URI"])
    mlflow.set_experiment(experiment)
    try:
        with mlflow.start_run(run_name=run_name):
            mlflow.log_params(params)
            mlflow.log_metrics(metrics)
            mlflow.log_artifact(artifact_path)
        print(f"MLflow: logged run {run_name!r} -> experiment {experiment!r}")
    except Exception as exc:  # noqa: BLE001 — optional observability must not fail the compile
        print(f"warning: MLflow logging raised ({exc!r}) — continuing without it.")


def _log_prompt_compile_run(
    *, model: str, compiled, trainset: list, metric, artifact_path: str, run_name: str
) -> None:
    """Gated first: the dspy.Evaluate pass costs extra LLM calls, only paid when MLflow is on."""
    if not _mlflow_tracking_configured():
        return
    result = dspy.Evaluate(devset=trainset, metric=metric, display_progress=False)(compiled)
    _log_compile_run_to_mlflow(
        experiment=os.environ.get("MAROLA_MLFLOW_EXPERIMENT", "marola/prompt-compile"),
        run_name=run_name,
        params={
            "model": model,
            "optimizer": "BootstrapFewShot",
            "trainset_size": str(len(trainset)),
        },
        metrics={"metric_score": float(result.score)},
        artifact_path=artifact_path,
    )


def self_test() -> int:
    """Unset MAROLA_MLFLOW_TRACKING_URI must skip MLflow entirely: no import, no network."""
    saved = os.environ.pop("MAROLA_MLFLOW_TRACKING_URI", None)
    try:
        assert _mlflow_tracking_configured() is False
        _log_compile_run_to_mlflow(
            experiment="marola/prompt-compile",
            run_name="summarize",
            params={},
            metrics={},
            artifact_path="/nonexistent/recommendation_prompt.json",
        )
        os.environ["MAROLA_MLFLOW_TRACKING_URI"] = "http://127.0.0.1:5000"
        assert _mlflow_tracking_configured() is True
    finally:
        os.environ.pop("MAROLA_MLFLOW_TRACKING_URI", None)
        if saved is not None:
            os.environ["MAROLA_MLFLOW_TRACKING_URI"] = saved

    print("compile_recommendation_prompt self-test: ok")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "--self-test",
        action="store_true",
        help="run the offline self-test (no LLM call, no MLflow server) and exit",
    )
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    _init_langfuse_tracing()

    model = os.environ.get("MAROLA_DSPY_MODEL", DEFAULT_DSPY_MODEL)
    api_base = os.environ.get("MAROLA_DSPY_API_BASE")  # e.g. http://localhost:11434, Ollama-only
    lm_kwargs = {"api_base": api_base} if api_base else {}
    dspy.configure(lm=dspy.LM(model, **lm_kwargs))

    resources_dir = os.path.join(
        os.path.dirname(__file__), "..", "core", "src", "main", "resources"
    )
    # The reviewer is a second, fresh pass: a model grading its own answer in the same call
    # catches its own mistakes less reliably (docs/4-Research-and-plans/FUTURE-WORK.md §4.2).
    for run_name, signature, metric, trainset, filename in (
        (
            "summarize",
            SummarizeSwimConditions,
            jellyfish_and_whale_mentioned_when_relevant,
            TRAINSET,
            "recommendation_prompt.json",
        ),
        (
            "review",
            ReviewSwimSummary,
            review_json_is_well_formed_and_sound,
            REVIEW_TRAINSET,
            "review_prompt.json",
        ),
    ):
        optimizer = dspy.teleprompt.BootstrapFewShot(metric=metric, max_bootstrapped_demos=3)
        compiled = optimizer.compile(student=dspy.Predict(signature), trainset=trainset)
        path = os.path.join(resources_dir, filename)
        compiled.save(path)
        print(f"Compiled {run_name} artifact written to {os.path.abspath(path)}")
        _log_prompt_compile_run(
            model=model,
            compiled=compiled,
            trainset=trainset,
            metric=metric,
            artifact_path=path,
            run_name=run_name,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
