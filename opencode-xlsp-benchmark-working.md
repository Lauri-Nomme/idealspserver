# Working State — xlsp Optimization Benchmark

## Task
Analyze opencode.db vs xlsp capabilities, produce benchmark report.

## Files created
- `/vokk/home/lauri/dev/opencode-xlsp-benchmark-report.md` (467 lines, comprehensive report)
- `/vokk/home/lauri/dev/opencode-xlsp-benchmark-working.md` (this file)

## Data sources
- `opencode.db` (682 MB SQLite, 334 sessions, 45,296 tool calls)
- `cli.ts` + 15 supporting files in `tools/xlsp/` (1,487 lines total)

## Key findings (TL;DR)

| Metric | Value |
|---|---|
| Total tool call wall time | ~253M ms (70h) |
| grep wall time | 78.8M ms (21.9h) — **31.1% of all time** |
| grep avg call | 31.3s (8.3× slower than bash) |
| idealspserver grep | 76.3M ms (21.2h) — **73.7% of project tool time** |
| xlsp warm avg | 38–500ms |
| xlsp errors | 0% (vs built-in lsp 34.6%) |
| Est. savings with xlsp | ~70M ms (19.4h) wall time |

## Queries used

1. Schema: `.tables`, `PRAGMA table_info(*)`
2. Tool counts: `SELECT json_extract(data, '$.tool'), count(*) FROM part WHERE type='tool' GROUP BY 1`
3. Timing: Extracted `state.time.end - state.time.start` per tool call
4. xlsp ops: Grouped by `state.input.operation`
5. Project filter: Joined `session.project_id` to `project.worktree`

## Next steps (optional)
- Implement xlsp tool routing in opencode agent configuration
- Add persistent xlsp daemon to eliminate cold-start overhead
- Remove the broken built-in `lsp` tool
