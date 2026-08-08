---
id: 1c9ddc14-90a5-4421-9c8e-fa2842b28345
created: '2026-08-08T12:27:17.267Z'
modified: '2026-08-08T12:27:17.267Z'
memory_type: context
tags: []
---
Root cause of CI Python failures (tests 6/9/42/371 at commit 51c7908, run 31250050961): idea/indexFinished fired at dumb-mode end / smart mode, but the file-based word/symbol index completes later in the background, so fresh servers served incomplete results.

Committed f91d0bb "fix: gate indexFinished on real index readiness, not just smart mode":
- MiscUtil.isSearchIndexReady(project): GENERIC probe (no project-specific symbols) - DumbService.isDumb false AND FileBasedIndex.getInstance().getFileBeingCurrentlyIndexed()==null, in ReadAction.compute, try/catch->false.
- MiscUtil.ensureIndexUpToDate(project): loop 10x/300s, runReadAction{FileBasedIndex.ensureUpToDate(StubUpdatingIndex.INDEX_ID,...); ensureUpToDate(IdIndex.NAME,...)}.
- LspServer.notifyIndexFinishedWhenReady(project): background AppExecutorUtil task, deadline 110s, requires 3 CONSECUTIVE idle samples (isSearchIndexReady) + waitForProjectStability(30s) between, else ensureIndexUpToDate + sleep 2s. Sends idea/indexFinished on ready or timeout. Wired into initialize "already smart" branch AND exitDumbMode (with null-project guard).
- IMPORTANT: avoid ADDING import lines to LspServer.java above the class declaration - hardcoded test positions (tests 5,16,371 use line 52 for the LspServer class; tests 4,9 use line 54 for LOG). An added `import com.intellij.util.concurrency.AppExecutorUtil;` shifted the class from 0-idx 52 to 53 and broke 5/16/371. Fixed by fully-qualifying AppExecutorUtil (no import line). Keep file's pre-class line count == HEAD.
- LOCAL COLD-SERVER VERIFICATION (clear ~/.cache/JetBrains/IntelliJIdea2026.1/index then restart idealsp.service): comprehensive 52 PASSED / 0 failed / 2 known (3x3 consecutive cold runs 52/52). Unit tests 116 total, 114 passed, 2 skipped.
- Pushed to master.

Also learned: references commands ran through EditorUtil.computeWithEditor + TargetElementUtil.findTargetElement; on cold startup caret can land on whitespace (stale doc/PSI) -> target null -> empty. Ended up NOT being the blocker once the +1 import line shift was removed.

CI runs for f91d0bb: Release 31257376020, Server tests 31257376010 - status pending. Previous failing run 31250050961 (51c7908) = Java green, Python failed 4 tests (6,9,42,371).
