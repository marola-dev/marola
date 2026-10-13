#!/usr/bin/env python3
"""Count sentence and paragraph lengths in Markdown against the STE100 limits.

A counter, not an STE100 validator: it knows nothing of the approved vocabulary, tenses,
-ing forms or phrasal verbs (references/ste100.md). Line numbers are the block's first line.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

LIST_ITEM = re.compile(r"^\s*(?:[-*+]|(\d+)[.)])\s+")
FENCE = re.compile(r"^\s*(```|~~~)")
# A full stop after these does not end a sentence.
ABBREVIATIONS = ("e.g.", "i.e.", "vs.", "cf.", "etc.", "approx.", "no.", "fig.", "al.")
SENTENCE_END = re.compile(r"(?<=[.!?])[\"')\]]*\s+(?=[\"'(\[]*[A-Z0-9À-Þ§])")


@dataclass
class Block:
    line: int
    text: str
    procedural: bool


@dataclass
class Finding:
    line: int
    message: str


def blocks(markdown: str, tables: bool = False) -> list[Block]:
    """Paragraphs and list items, without code, headings, quotations and front matter."""
    out: list[Block] = []
    current: Block | None = None
    in_fence = in_front_matter = in_comment = False

    def flush() -> None:
        nonlocal current
        if current and current.text.strip():
            out.append(current)
        current = None

    for number, raw in enumerate(markdown.splitlines(), start=1):
        if number == 1 and raw.strip() == "---":
            in_front_matter = True
            continue
        if in_front_matter:
            in_front_matter = raw.strip() not in ("---", "...")
            continue
        if FENCE.match(raw):
            in_fence = not in_fence
            flush()
            continue
        if in_fence:
            continue
        if in_comment:
            in_comment = "-->" not in raw
            continue
        line = re.sub(r"<!--.*?-->", "", raw)
        if "<!--" in line:
            in_comment = True
            line = line.split("<!--")[0]
        stripped = line.strip()
        is_table = stripped.startswith("|")
        if not stripped or stripped.startswith(("#", ">")) or (is_table and not tables):
            flush()
            continue
        if is_table:
            flush()
            for cell in stripped.strip("|").split("|"):
                if re.search(r"[A-Za-z]", cell) and not re.fullmatch(r"[\s:-]+", cell):
                    out.append(Block(number, cell.strip(), False))
            continue
        item = LIST_ITEM.match(line)
        if item:
            flush()
            current = Block(number, line[item.end() :], item.group(1) is not None)
        elif current:
            current.text += " " + stripped
        else:
            current = Block(number, stripped, False)
    flush()
    return out


def plain(text: str) -> str:
    """Inline code and a link each count as their visible words; a URL counts as one word."""
    text = re.sub(r"`[^`]*`", "CODE", text)
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"https?://\S+", "URL", text)
    return re.sub(r"[*_]{1,3}", "", text)


def sentences(text: str) -> list[str]:
    text = plain(text)
    for abbreviation in ABBREVIATIONS:
        text = re.sub(re.escape(abbreviation), abbreviation.replace(".", "\x00"), text, flags=re.I)
    return [s.replace("\x00", ".").strip() for s in SENTENCE_END.split(text) if s.strip()]


def words(sentence: str) -> int:
    return sum(1 for token in sentence.split() if re.search(r"\w", token))


def check(
    markdown: str,
    descriptive: int = 25,
    procedural: int = 20,
    paragraph: int = 6,
    tables: bool = False,
) -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    total = 0
    for block in blocks(markdown, tables):
        found = sentences(block.text)
        total += len(found)
        limit = procedural if block.procedural else descriptive
        for sentence in found:
            count = words(sentence)
            if count > limit:
                findings.append(
                    Finding(block.line, f"{count} words (limit {limit}): {sentence[:70]}")
                )
        if len(found) > paragraph:
            findings.append(
                Finding(block.line, f"{len(found)} sentences in one paragraph (limit {paragraph})")
            )
    return findings, total


def self_test() -> int:
    long_sentence = " ".join(["word"] * 26) + "."
    sample = "\n".join(
        [
            "---",
            "title: " + long_sentence,
            "---",
            "# " + long_sentence,
            "",
            "Short one. Use `a b c d e f` here, e.g. this stays one sentence.",
            "",
            long_sentence,
            "",
            "```",
            long_sentence,
            "```",
            "",
            "> " + long_sentence,
            "",
            "1. " + " ".join(["step"] * 21) + ".",
            "2. " + " ".join(["step"] * 20) + ".",
            "- " + " ".join(["item"] * 25) + ".",
            "",
            "| a | " + long_sentence + " |",
            "",
            "A b. C d. E f. G h. I j. K l. M n.",
            "<!-- " + long_sentence + " -->",
        ]
    )
    findings, total = check(sample)
    got = [(f.line, f.message.split(":")[0]) for f in findings]
    want = [
        (8, "26 words (limit 25)"),
        (16, "21 words (limit 20)"),
        (22, "7 sentences in one paragraph (limit 6)"),
    ]
    with_tables, _ = check(sample, tables=True)
    checks = {
        "findings": got == want,
        "sentence total": total == 13,
        "abbreviation": len(sentences("Use it, e.g. Here. Then stop.")) == 2,
        "code span is one word": words(plain("Run `just release 1.2.3` now.")) == 3,
        "link text counts, target does not": words(plain("See [the docs](http://x.y/a b).")) == 3,
        "tables on request": len(with_tables) == len(findings) + 1,
    }
    for name, ok in checks.items():
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
    if not checks["findings"]:
        print(f"  got  {got}\n  want {want}")
    return 0 if all(checks.values()) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("files", nargs="*", type=Path)
    parser.add_argument("--max-desc", type=int, default=25, help="words, descriptive sentence")
    parser.add_argument("--max-proc", type=int, default=20, help="words, numbered-step sentence")
    parser.add_argument("--max-para", type=int, default=6, help="sentences in a paragraph")
    parser.add_argument("--tables", action="store_true", help="also count table cells")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.files:
        parser.error("give at least one Markdown file, or --self-test")
    failed = False
    for path in args.files:
        findings, total = check(
            path.read_text(encoding="utf-8"),
            args.max_desc,
            args.max_proc,
            args.max_para,
            args.tables,
        )
        for finding in findings:
            print(f"{path}:{finding.line}: {finding.message}")
        print(f"{path}: {len(findings)} findings in {total} sentences")
        failed = failed or bool(findings)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
