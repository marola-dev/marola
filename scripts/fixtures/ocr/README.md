# OCR fixtures (`scripts/ocr-post.py --self-test`)

**Where the schema came from.** Read from alibaba/open-code-review at tag **v1.12.7** (released
2026-09-19) on 2026-09-19:

- `internal/model/review.go` — `LlmComment`: `path`, `content`, `suggestion_code`,
  `existing_code`, `start_line`, `end_line`, `category`, `severity`.
- `cmd/opencodereview/output.go` — `jsonOutput`: `status`, `llm{provider,model}`, `trace_id`,
  `message`, `summary{files_reviewed,comments,total_tokens,input_tokens,output_tokens,elapsed,…}`,
  `tool_calls`, `comments`, `warnings`, `project_summary`, `session_id`, `manifest`.
- `scripts/github-actions/post-review-comments.js` — upstream's own consumer, which is where the
  *posting* rules come from: category/severity enumerations, the severity rank
  (critical > high > medium > low), "inline-able when `start_line` or `end_line` ≥ 1",
  multi-line as `start_line` + `line` on `side: "RIGHT"`, and a `suggestion` block only when
  `suggestion_code` **and** `existing_code` are both present.

**These files were built from those structs, not captured from a real `ocr review` run.** MIP-0060
§7.1's go/no-go had not produced a sample when they were written. Replace `normal.json` with a
sanitised real output when one exists, and say so here.

| File | What it exercises |
|---|---|
| `diff.patch` | The PR diff every fixture is judged against — two files, one hunk each |
| `normal.json` | Three in-diff findings, a `suggestion_code`/`existing_code` pair, and one finding whose text tries an `@mention` ping and a forged `<!-- marola-ocr -->` marker |
| `out-of-diff.json` | One in-diff finding plus one in a file the PR never touched |
| `many.json` | 18 findings over mixed severities — more than the default cap of 15 |
| `malformed.json` | Truncated mid-object, as if `ocr` was killed while writing |
| `empty.json` | Zero bytes — `ocr` produced no output at all |
