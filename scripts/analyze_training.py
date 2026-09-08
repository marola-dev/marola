#!/usr/bin/env python3
"""Turn a marola-sea training run into a report you can act on — and hand back to an agent.

Both trainers set `report_to=[]` (finetune/train_lora.py, finetune/train_dpo.py), so there is no
MLflow or W&B run to open: the evidence is whatever the job left on disk. That is more than the
scrollback, though. Hugging Face's Trainer writes `trainer_state.json` next to the adapter and
inside every `checkpoint-N/`, and its `log_history` is the whole run as structured records —
every logged train loss, every eval, and the final throughput summary. This reads that (falling
back to scraping stdout when a state file is missing) and answers the question the raw numbers do
not: *what should the next run change?*

    python3 scripts/analyze_training.py ../marola-checkpoints/tiny/adapter
    python3 scripts/analyze_training.py <dir-or-state.json> ... --markdown report.md --json report.json

The findings are deliberately phrased as a config diff — `num_train_epochs`, `learning_rate`,
`beta` — because that is what the next run actually needs, and what is useful to paste back into a
session as "here is what happened, what do we change?".
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# HF logs a dict per logging_steps; DPO adds the reward keys trl's DPOTrainer emits.
TRAIN_KEY, EVAL_KEY = "loss", "eval_loss"
# How much the loss must still be falling across the last logged steps to call a run
# undertrained. A couple of percent is ordinary step-to-step noise on a converged curve.
STILL_FALLING = 0.05
DPO_ACC_KEY = "rewards/accuracies"


def find_state(target: Path) -> Path | None:
    """trainer_state.json for a run: in the output dir, else the newest checkpoint-N inside it."""
    if target.is_file():
        return target
    direct = target / "trainer_state.json"
    if direct.is_file():
        return direct
    checkpoints = sorted(
        (p for p in target.glob("checkpoint-*") if (p / "trainer_state.json").is_file()),
        key=lambda p: int(p.name.split("-")[-1]),
    )
    return checkpoints[-1] / "trainer_state.json" if checkpoints else None


def parse_stdout(text: str) -> list[dict]:
    """Fallback for a run whose state file is gone: HF prints each log dict with single quotes."""
    records = []
    for match in re.finditer(r"\{'(?:loss|eval_loss|train_runtime)'.*?\}", text):
        try:
            records.append(json.loads(match.group(0).replace("'", '"')))
        except json.JSONDecodeError:
            continue
    return records


def load_run(target: Path) -> dict:
    state = find_state(target)
    if state is not None:
        data = json.loads(state.read_text())
        return {
            "name": target.name if target.is_dir() else target.parent.name,
            "source": str(state),
            "log_history": data.get("log_history", []),
            "epochs_run": data.get("epoch"),
            "global_step": data.get("global_step"),
        }
    logs = sorted(target.glob("*.log")) if target.is_dir() else []
    if logs:
        history = [r for f in logs for r in parse_stdout(f.read_text(errors="replace"))]
        return {
            "name": target.name,
            "source": f"{logs[0]} (stdout)",
            "log_history": history,
            "epochs_run": None,
            "global_step": None,
        }
    raise FileNotFoundError(f"no trainer_state.json or *.log under {target}")


def series(history: list[dict], key: str) -> list[tuple[float, float]]:
    """(epoch, value) pairs for one metric, in order, skipping records that lack it."""
    return [(r.get("epoch", i), r[key]) for i, r in enumerate(history) if key in r]


def summarize(run: dict) -> dict:
    history = run["log_history"]
    train, ev = series(history, TRAIN_KEY), series(history, EVAL_KEY)
    final = next((r for r in reversed(history) if "train_runtime" in r), {})
    dpo = series(history, DPO_ACC_KEY)
    return {
        "name": run["name"],
        "source": run["source"],
        "kind": "dpo" if dpo else "sft",
        "steps": run.get("global_step") or len(train),
        "epochs_run": run.get("epochs_run"),
        "train_loss": [v for _, v in train],
        "eval_loss": [v for _, v in ev],
        "eval_epochs": [e for e, _ in ev],
        "grad_norm": [v for _, v in series(history, "grad_norm")],
        "dpo_accuracy": [v for _, v in dpo],
        "runtime_s": final.get("train_runtime"),
        "samples_per_s": final.get("train_samples_per_second"),
    }


def _trend(values: list[float], tail: int = 3) -> float:
    """How much the metric moved across its last `tail` points, as a fraction of its own scale."""
    if len(values) < tail + 1:
        return 0.0
    recent, earlier = values[-tail:], values[-(tail + 1)]
    return (earlier - sum(recent) / len(recent)) / abs(earlier) if earlier else 0.0


def diagnose(s: dict) -> list[dict]:
    """Findings as {severity, title, evidence, suggestion} — suggestion names a config knob."""
    out: list[dict] = []
    train, ev, steps = s["train_loss"], s["eval_loss"], s["steps"]

    # The failure a small corpus actually hits: too few optimizer steps to learn anything.
    if steps and steps < 50:
        out.append(
            {
                "severity": "high",
                "title": "the run was too short to learn much",
                "evidence": f"{steps} optimizer steps in total",
                "suggestion": "raise num_train_epochs, or lower gradient_accumulation_steps "
                "(SFT uses 4, DPO 2) so the same data yields more steps; a few dozen "
                "steps mostly measures the initialisation, not the dataset",
            }
        )

    overfit = len(ev) >= 2 and ev[-1] > min(ev) * 1.02
    if overfit:
        best = ev.index(min(ev))
        out.append(
            {
                "severity": "high",
                "title": "eval loss turned back up — overfitting",
                "evidence": f"best eval_loss {min(ev):.4f} at epoch "
                f"{s['eval_epochs'][best]:.0f}, ended at {ev[-1]:.4f}",
                "suggestion": f"train for ~{s['eval_epochs'][best]:.0f} epochs instead, or add data; "
                "the later epochs made the model worse on held-out examples",
            }
        )

    if len(train) >= 4:
        drop = (train[0] - train[-1]) / train[0] if train[0] else 0.0
        if drop < 0.05:
            out.append(
                {
                    "severity": "high",
                    "title": "train loss barely moved",
                    "evidence": f"{train[0]:.4f} -> {train[-1]:.4f} ({drop * 100:.1f}%)",
                    "suggestion": "raise learning_rate (SFT default 2e-4 for LoRA), or check the "
                    "dataset actually loaded — a flat curve from step 0 is usually one "
                    "of those two, not a model problem",
                }
            )
        # Not `elif overfit`: a train loss still falling while eval rises IS the
        # overfitting above, and telling you to train longer would contradict it.
        elif _trend(train) > STILL_FALLING and not overfit:
            out.append(
                {
                    "severity": "info",
                    "title": "still improving when it stopped — undertrained",
                    "evidence": f"loss fell {_trend(train) * 100:.1f}% across the last logged steps",
                    "suggestion": "raise num_train_epochs; the curve had not flattened yet",
                }
            )

    if s["grad_norm"]:
        peak, median = max(s["grad_norm"]), sorted(s["grad_norm"])[len(s["grad_norm"]) // 2]
        if median and peak > median * 10:
            out.append(
                {
                    "severity": "medium",
                    "title": "gradient-norm spikes",
                    "evidence": f"peak {peak:.2f} against a median of {median:.2f}",
                    "suggestion": "lower learning_rate or add warmup_ratio=0.03; spikes this size "
                    "mean some steps moved the weights far more than the rest",
                }
            )

    if s["kind"] == "dpo" and s["dpo_accuracy"]:
        final_acc = sum(s["dpo_accuracy"][-3:]) / len(s["dpo_accuracy"][-3:])
        if final_acc < 0.6:
            out.append(
                {
                    "severity": "high",
                    "title": "DPO is not separating chosen from rejected",
                    "evidence": f"rewards/accuracies averaged {final_acc:.2f} at the end (0.5 = chance)",
                    "suggestion": "raise beta (default 0.1) so the preference signal counts for more, "
                    "or check build_dpo_dataset.py — pairs that are near-identical give "
                    "the trainer nothing to separate",
                }
            )

    if not out:
        out.append(
            {
                "severity": "info",
                "title": "nothing anomalous in the curves",
                "evidence": "loss fell and eval did not diverge",
                "suggestion": "scale up: the next question is the preset, not the schedule",
            }
        )
    return out


def render_markdown(runs: list[tuple[dict, list[dict]]]) -> str:
    rank = {"high": "🔴", "medium": "🟡", "info": "🔵"}
    lines = ["# marola-sea training report", ""]
    for s, findings in runs:
        lines += [
            f"## {s['name']} ({s['kind'].upper()})",
            "",
            f"- source: `{s['source']}`",
            f"- steps: {s['steps']}, epochs run: {s['epochs_run'] or 'n/a'}",
        ]
        if s["train_loss"]:
            lines.append(f"- train loss: {s['train_loss'][0]:.4f} → {s['train_loss'][-1]:.4f}")
        if s["eval_loss"]:
            lines.append(
                f"- eval loss: {s['eval_loss'][0]:.4f} → {s['eval_loss'][-1]:.4f} "
                f"(best {min(s['eval_loss']):.4f})"
            )
        if s["dpo_accuracy"]:
            lines.append(f"- DPO reward accuracy: ends at {s['dpo_accuracy'][-1]:.2f}")
        if s["runtime_s"]:
            lines.append(
                f"- runtime: {s['runtime_s'] / 60:.1f} min"
                + (f", {s['samples_per_s']:.2f} samples/s" if s["samples_per_s"] else "")
            )
        lines += ["", "### What to change next run", ""]
        for f in findings:
            lines += [
                f"**{rank.get(f['severity'], '•')} {f['title']}**  ",
                f"{f['evidence']}  ",
                f"→ {f['suggestion']}",
                "",
            ]
    return "\n".join(lines)


def self_test() -> int:
    fails = 0

    def ok(got, want, what):
        nonlocal fails
        if got == want:
            print(f"  ok   {what}")
        else:
            print(f"  FAIL {what} — got {got!r} want {want!r}")
            fails += 1

    def titles(summary):
        return [f["title"] for f in diagnose(summary)]

    base = {
        "name": "t",
        "source": "x",
        "kind": "sft",
        "steps": 400,
        "epochs_run": 3,
        "train_loss": [],
        "eval_loss": [],
        "eval_epochs": [],
        "grad_norm": [],
        "dpo_accuracy": [],
        "runtime_s": None,
        "samples_per_s": None,
    }

    ok(
        titles({**base, "steps": 12}),
        ["the run was too short to learn much"],
        "a 12-step run is flagged — the failure a small corpus actually hits",
    )
    ok(
        "the run was too short to learn much" in titles({**base, "steps": 400}),
        False,
        "a 400-step run is not flagged for length",
    )

    over = {
        **base,
        "eval_loss": [2.0, 1.2, 1.5],
        "eval_epochs": [1, 2, 3],
        "train_loss": [3.0, 2.0, 1.0, 0.4],
    }
    ok(
        "eval loss turned back up — overfitting" in titles(over),
        True,
        "eval loss rising while train loss falls is called overfitting",
    )
    ok(
        "2" in diagnose(over)[0]["suggestion"],
        True,
        "and the suggestion names the epoch the run should have stopped at",
    )

    ok(
        "train loss barely moved" in titles({**base, "train_loss": [2.0, 1.99, 1.98, 1.99]}),
        True,
        "a flat train curve is flagged as a learning-rate or dataset problem",
    )
    ok(
        "train loss barely moved" in titles({**base, "train_loss": [3.0, 2.0, 1.0, 0.5]}),
        False,
        "a curve that fell by half is not",
    )

    spikes = {**base, "train_loss": [3.0, 2.0, 1.0, 0.5], "grad_norm": [0.5, 0.5, 0.6, 40.0, 0.5]}
    ok("gradient-norm spikes" in titles(spikes), True, "a 40x gradient spike is flagged")
    ok(
        "gradient-norm spikes" in titles({**base, "grad_norm": [0.5, 0.6, 0.55, 0.7]}),
        False,
        "a steady gradient norm is not",
    )

    dpo = {
        **base,
        "kind": "dpo",
        "train_loss": [0.7, 0.6, 0.5, 0.45],
        "dpo_accuracy": [0.5, 0.52, 0.51],
    }
    ok(
        "DPO is not separating chosen from rejected" in titles(dpo),
        True,
        "DPO reward accuracy at chance is flagged — the run learned no preference",
    )
    ok(
        "DPO is not separating chosen from rejected"
        in titles({**dpo, "dpo_accuracy": [0.8, 0.85, 0.9]}),
        False,
        "a DPO run that does separate them is not flagged",
    )

    ok(
        titles({**base, "train_loss": [3.0, 2.0, 1.0, 0.5, 0.49, 0.49, 0.49]}),
        ["nothing anomalous in the curves"],
        "a healthy converged run reports nothing to change",
    )

    ok(
        "still improving when it stopped — undertrained" in titles(over),
        False,
        "an overfitting run is never also told to train longer — the advice would contradict",
    )
    ok(
        "still improving when it stopped — undertrained"
        in titles({**base, "train_loss": [3.0, 2.5, 2.0, 1.5, 1.0]}),
        True,
        "but a run that is genuinely still falling, with no eval divergence, is",
    )

    ok(
        len(parse_stdout("{'loss': 1.5, 'epoch': 0.5}\nnoise\n{'eval_loss': 1.2, 'epoch': 1.0}")),
        2,
        "stdout fallback recovers HF's single-quoted log dicts",
    )
    ok(
        parse_stdout("{'loss': 1.5, 'epoch': 0.5}")[0]["loss"],
        1.5,
        "and their values survive the quote conversion",
    )
    ok(len(parse_stdout("no log lines here at all")), 0, "prose is not mistaken for a log record")

    hist = [
        {"loss": 2.0, "epoch": 0.5},
        {"eval_loss": 1.8, "epoch": 1.0},
        {"train_runtime": 120.0, "train_samples_per_second": 4.0},
    ]
    s = summarize(
        {"name": "r", "source": "s", "log_history": hist, "epochs_run": 1, "global_step": 10}
    )
    ok(
        (s["train_loss"], s["eval_loss"], s["runtime_s"]),
        ([2.0], [1.8], 120.0),
        "summarize splits train, eval and the final throughput record apart",
    )
    ok(s["kind"], "sft", "a run with no reward keys is classified as SFT")
    ok(
        summarize(
            {
                "name": "r",
                "source": "s",
                "log_history": [{DPO_ACC_KEY: 0.7, "epoch": 1}],
                "epochs_run": 1,
                "global_step": 1,
            }
        )["kind"],
        "dpo",
        "and one with them as DPO",
    )

    ok(
        render_markdown([(s, diagnose(s))]).startswith("# marola-sea training report"),
        True,
        "the markdown report renders",
    )

    print(
        "analyze_training self-test: ok"
        if not fails
        else f"analyze_training self-test: {fails} failure(s)",
        file=sys.stderr if fails else sys.stdout,
    )
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("runs", nargs="*", type=Path, help="adapter dirs, or trainer_state.json files")
    ap.add_argument("--markdown", type=Path, help="write the report here (default: stdout)")
    ap.add_argument("--json", type=Path, help="also write the structured summary here")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.runs:
        ap.error("give at least one run directory (or --self-test)")

    analyzed = []
    for target in args.runs:
        try:
            s = summarize(load_run(target))
        except (FileNotFoundError, json.JSONDecodeError) as e:
            print(f"analyze_training: skipping {target}: {e}", file=sys.stderr)
            continue
        analyzed.append((s, diagnose(s)))
    if not analyzed:
        print("analyze_training: no readable runs", file=sys.stderr)
        return 1

    report = render_markdown(analyzed)
    if args.markdown:
        args.markdown.write_text(report)
        print(f"wrote {args.markdown}")
    else:
        print(report)
    if args.json:
        args.json.write_text(
            json.dumps([{"summary": s, "findings": f} for s, f in analyzed], indent=2)
        )
        print(f"wrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
