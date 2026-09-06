---
name: voice-note-ingest
description: Transcribe a local voice note (WhatsApp .ogg, or similar) with local Whisper, write the transcript next to the audio, and anonymize it before it goes anywhere else. Use when a MIP or a task references audio that needs a real transcript, or when explicitly asked to transcribe a voice memo. Never commits the audio itself.
---

# Voice note ingest

Transcribe one local audio file with a local Whisper backend — no network call, no upload, no
third-party speech-to-text API. This is deliberately small: it does one thing (speech to text in
the source language) and stops. Everything else — translation, feature drafting, anonymization —
is a separate step, on purpose, so this skill stays auditable and doesn't silently do more than it
says.

## What it does

```bash
python3 .claude/skills/voice-note-ingest/scripts/transcribe.py <audio> [--model small] [--language pt]
```

- Tries `faster_whisper` first, falls back to `whisper` (openai-whisper) if that's what's
  importable, and exits 2 with an install hint if neither is — it does not vendor or download a
  model on your behalf beyond what the chosen library does on first use.
- `--model` (default `small`): Whisper model size. `small` is the pragmatic default for a short
  voice note on a laptop CPU — big enough to get pt-BR right most of the time, small enough not to
  need a GPU or a long wait. Go to `medium`/`large` only if `small` visibly mangles names or
  numbers.
- `--language` (default `pt`): the source language code. Set explicitly rather than relying on
  Whisper's auto-detect — a short, noisy voice note is exactly the case where auto-detect guesses
  wrong, and a wrong guess silently degrades the whole transcript rather than failing loudly.
- Output: `<audio>.txt` written next to the audio file (suffix replaced, e.g.
  `note.ogg` → `note.txt`). Nothing is printed to stdout except the path written, so this composes
  cleanly in a pipeline.

## What it deliberately does not do

- **No translation via Whisper's `--task translate`.** Whisper's built-in translation path only
  ever targets English, gives no way to inspect or correct the intermediate transcript, and mixes
  two different failure modes (mishearing vs. mistranslating) into one output with no way to tell
  which happened. Transcribe in the source language with this script, read the result, then
  translate it yourself (as the calling skill, or by hand) — a second, separate, invertible step.
- **No anonymization.** The raw transcript can and often will contain a name. Before this
  transcript reaches a MIP, a commit, or any other repo-tracked file: replace every name except
  the repo owner's own (this repo's convention is "M. Hoffmann" or a first name of theirs — see
  `AGENTS.md`) with a neutral placeholder like "a collaborator". Do this by hand, reading the
  transcript, not with a regex — a script cannot reliably tell a name from an ordinary word in
  Portuguese any better than in English.
- **No audio in git, ever.** The source `.ogg`/`.wav`/etc. must not be committed, not even
  temporarily — voice notes are personal data and the repo is public. Keep the audio outside the
  worktree (or `.gitignore`d) and delete it once the transcript is confirmed good; only the
  anonymized `.txt` (or its distilled content, e.g. inside a MIP) belongs in a commit.

## Running the self-test

The script has a `--self-test` mode that exercises argument parsing and the output-path logic
only — it loads no model and needs no audio file or installed backend:

```bash
python3 .claude/skills/voice-note-ingest/scripts/transcribe.py --self-test
```

This is a skill helper, not repo tooling — it is *not* wired into `just quality`/`quality-other`
or CI. Run it by hand after touching the script, before trusting it on a real voice note.

## Installing a backend

Neither `faster_whisper` nor `whisper` is a repo dependency (this skill is optional, personal-data
tooling, not something every `nix develop` shell needs). Install one inside the repo's own venv,
not globally:

```bash
pip install faster-whisper
```

`faster-whisper` is the preferred backend (CTranslate2-based, notably faster on CPU than
openai-whisper for the same model size); `openai-whisper` works as a fallback if that's what's
already on the machine.
