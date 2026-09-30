"""Pin the font Kroki measures Mermaid labels in. See #511.

`import mkdocs` only succeeds inside the container; guarded so `scripts/mkdocs.sh --self-test`
can load this file with the host's bare python3.
"""

from __future__ import annotations

import json
import re

try:
    from mkdocs.plugins import event_priority
except ImportError:  # pragma: no cover - only hit outside the mkdocs container

    def event_priority(priority):
        return lambda f: f


FONT_FAMILY = '"Open Sans", "trebuchet ms", verdana, arial, sans-serif'
_INIT_LINE = "%%{init: " + json.dumps({"fontFamily": FONT_FAMILY}) + "}%%"

# Mirrors mkdocs_kroki_plugin's own kroki/parsing.py _FENCE_RE (1.7.0): requiring the closing
# fence to repeat the opening's exact indent and backtick/tilde run is what keeps a ```mermaid```
# example quoted inside a longer fence (DIAGRAMS.md's "Source" blocks) from being rewritten too.
_FENCE_RE = re.compile(
    r"(?P<fence>^(?P<indent>[ ]*)(?:````*|~~~~*))[ ]*"
    r"(?P<lang>[\w#.+-]*)[^\n]*\n"
    r"(?P<code>.*?)(?<=\n)"
    r"(?P=fence)[ ]*$",
    re.IGNORECASE | re.DOTALL | re.MULTILINE,
)


def pin_mermaid_font(markdown: str) -> str:
    """Prepend the font-pin init line to every top-level ```mermaid fence."""
    pieces: list[str] = []
    last_end = 0
    for match in _FENCE_RE.finditer(markdown):
        pieces.append(markdown[last_end : match.start()])
        lang = (match.group("lang") or "").strip().lower()
        code = match.group("code")
        if lang == "mermaid" and not code.startswith(_INIT_LINE):
            head = markdown[match.start() : match.start("code")]
            tail = markdown[match.end("code") : match.end()]
            pieces.append(head + match.group("indent") + _INIT_LINE + "\n" + code + tail)
        else:
            pieces.append(match.group(0))
        last_end = match.end()
    pieces.append(markdown[last_end:])
    return "".join(pieces)


@event_priority(50)
def on_page_markdown(markdown, page, config, files):
    return pin_mermaid_font(markdown)
