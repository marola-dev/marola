---
name: changelog
description: Write the changelog section for the next release from the commits since the last tag. Use when asked for release notes or a changelog.
---

# changelog

1. Run `scripts/changelog.sh` to list the commits since the last tag, grouped by type.
2. Rewrite each group as user-facing lines: what changed for the reader, not the diff.
3. Put breaking changes first, under their own heading.
