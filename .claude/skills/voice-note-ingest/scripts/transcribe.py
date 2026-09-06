#!/usr/bin/env python3
"""Transcribe a local audio file with a local Whisper backend.

Thin wrapper, not a Whisper reimplementation: it tries `faster_whisper` first, then falls back to
`whisper` (openai-whisper) if that's what's importable, and does nothing else. No network call,
no upload — the audio never leaves the machine. See the sibling SKILL.md for why this script
transcribes only (never translates) and for the anonymization step that must happen after this
runs, by hand, before the output touches the repo.

Usage:
    python3 transcribe.py <audio> [--model small] [--language pt]
    python3 transcribe.py --self-test
"""

from __future__ import annotations

import sys
from argparse import ArgumentParser, Namespace
from pathlib import Path


def build_arg_parser() -> ArgumentParser:
    parser = ArgumentParser(
        description="Transcribe a local audio file with local Whisper (no network, no upload)."
    )
    parser.add_argument("audio", nargs="?", help="path to the audio file to transcribe")
    parser.add_argument("--model", default="small", help="Whisper model size (default: small)")
    parser.add_argument(
        "--language", default="pt", help="source language code, e.g. pt, en (default: pt)"
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="exercise argument parsing and output-path logic only; loads no model",
    )
    return parser


def output_path_for(audio_path: Path) -> Path:
    """Where the transcript goes: `<audio>` with its suffix replaced by `.txt`, next to the file."""
    return audio_path.with_suffix(".txt")


def load_backend() -> str | None:
    """Name of the first importable local Whisper backend, or None if neither is installed."""
    try:
        import faster_whisper  # noqa: F401

        return "faster_whisper"
    except ImportError:
        pass
    try:
        import whisper  # noqa: F401

        return "whisper"
    except ImportError:
        pass
    return None


def transcribe_with_faster_whisper(audio_path: Path, model_size: str, language: str) -> str:
    from faster_whisper import WhisperModel

    model = WhisperModel(model_size)
    segments, _info = model.transcribe(str(audio_path), language=language)
    return "".join(segment.text for segment in segments).strip()


def transcribe_with_whisper(audio_path: Path, model_size: str, language: str) -> str:
    import whisper

    model = whisper.load_model(model_size)
    result = model.transcribe(str(audio_path), language=language)
    return str(result["text"]).strip()


def run_self_test() -> int:
    """Argument parsing and output-path logic only — no model is loaded."""
    parser = build_arg_parser()

    args: Namespace = parser.parse_args(
        ["--self-test", "--model", "medium", "--language", "en", "x.ogg"]
    )
    assert args.model == "medium"
    assert args.language == "en"
    assert args.self_test is True

    defaults: Namespace = parser.parse_args(["some.ogg"])
    assert defaults.model == "small"
    assert defaults.language == "pt"
    assert defaults.self_test is False

    assert output_path_for(Path("x.ogg")) == Path("x.txt")
    assert output_path_for(Path("dir/note.WhatsApp.ogg")) == Path("dir/note.WhatsApp.txt")
    assert output_path_for(Path("no-extension")) == Path("no-extension.txt")

    print("self-test: ok (argument parsing and output-path logic only, no model loaded)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    if not args.audio:
        parser.error("audio is required unless --self-test is given")

    backend = load_backend()
    if backend is None:
        print(
            "No local Whisper backend importable. Install one inside the repo's venv:\n"
            "  pip install faster-whisper",
            file=sys.stderr,
        )
        return 2

    audio_path = Path(args.audio)
    if not audio_path.exists():
        print(f"Audio file not found: {audio_path}", file=sys.stderr)
        return 1

    if backend == "faster_whisper":
        text = transcribe_with_faster_whisper(audio_path, args.model, args.language)
    else:
        text = transcribe_with_whisper(audio_path, args.model, args.language)

    out_path = output_path_for(audio_path)
    out_path.write_text(text, encoding="utf-8")
    print(f"Wrote {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
